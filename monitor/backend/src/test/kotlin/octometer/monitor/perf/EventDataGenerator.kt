package octometer.monitor.perf

import java.util.Random
import octometer.monitor.store.EventStore
import octometer.monitor.store.NewEvent
import octometer.monitor.store.SqliteDatabase

/** The row count of one page of [EventDataGenerator] (issue #63, the real write path). */
internal const val GENERATOR_PAGE_SIZE = 1_000

private const val APP_COUNT = 3
private const val USER_COUNT = 2_000
private const val ELEMENT_COUNT = 60
private const val ANONYMOUS_PERCENT = 70
private const val MIN_CLICKS_PER_SESSION = 1
private const val MAX_CLICKS_PER_SESSION = 20
private const val DAY_SPAN = 395
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val BASE_TS_MILLIS = 1_690_000_000_000L // an arbitrary fixed start, UTC
private const val SEED = 987_654_321L
private const val EVENT_ID_HEX_LENGTH = 24
private const val SESSION_START_ELEMENT = "octo:session-start"

private val PATH_PATTERNS: List<String> = (1..20).map { index -> "/octo-perf/page-%02d".format(index) }
private val REFERRER_HOSTS = listOf("google.com", "bing.com", "other")

/**
 * Writes test data for the perf test of issue #63, through the real
 * write path ([EventStore.commitPage]), in pages of [GENERATOR_PAGE_SIZE]
 * rows. It writes [APP_COUNT] apps, [USER_COUNT] user ids, and
 * [ELEMENT_COUNT] elements, with [ANONYMOUS_PERCENT] percent anonymous
 * events. Each session holds [MIN_CLICKS_PER_SESSION] to
 * [MAX_CLICKS_PER_SESSION] clicks, and one start row (`kind = 1`) with a
 * `path` and a `referrer_host`. The `ts` values spread over [DAY_SPAN]
 * days. A fixed [Random] seed gives the same data on two runs.
 *
 * The generator inserts the three app rows with a direct SQL statement
 * (the pattern of `EventStoreTest.kt`, about line 298): the "no direct
 * SQL" rule of the database brief holds for the event rows only. Each
 * page of one app checks the `true` return of `commitPage`, and it fails
 * fast on `false`: a mismatched cursor rolls the page back with no
 * throw, and the generator must not go on with a silently short page.
 */
class EventDataGenerator(private val database: SqliteDatabase) {

    private val random = Random(SEED)
    private val eventStore = EventStore(database)
    private var eventIdCounter = 0L

    /** Writes [totalEventCount] event rows, split about evenly over the three apps. Gives the three app ids. */
    suspend fun generate(totalEventCount: Long): List<Long> {
        val appIds = (1..APP_COUNT).map { index -> insertApp("perf-app-$index") }
        val perAppCounts = splitAcrossApps(totalEventCount, APP_COUNT)
        appIds.forEachIndexed { index, appId -> writeApp(appId, perAppCounts[index]) }
        return appIds
    }

    private fun splitAcrossApps(total: Long, appCount: Int): List<Long> {
        val base = total / appCount
        val remainder = total - base * appCount
        return (0 until appCount).map { index -> if (index == appCount - 1) base + remainder else base }
    }

    private suspend fun insertApp(name: String): Long =
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, name)
                insert.setString(2, "perf_db")
                insert.setString(3, "octometer_events")
                insert.setLong(4, BASE_TS_MILLIS)
                insert.executeUpdate()
            }
            writer.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private suspend fun writeApp(appId: Long, rowCount: Long) {
        var expectedCursor: String? = null
        var cursorCounter = 0L
        val buffer = ArrayList<NewEvent>(GENERATOR_PAGE_SIZE)

        suspend fun flush() {
            if (buffer.isEmpty()) return
            cursorCounter++
            val cursor = cursorCounter.toString()
            val committed = eventStore.commitPage(appId, ArrayList(buffer), cursor, expectedCursor)
            check(committed) {
                "commitPage refused a page of app $appId at cursor $cursor; the expected cursor no longer matched."
            }
            expectedCursor = cursor
            buffer.clear()
        }

        for (clicks in sessionPlan(rowCount)) {
            val sessionId = "perf-session-$appId-${nextEventId()}"
            val userId = pickUserId()
            var ts = randomStartTs()
            buffer += sessionStartEvent(sessionId, ts, userId)
            if (buffer.size >= GENERATOR_PAGE_SIZE) flush()
            repeat(clicks) {
                ts += 1 + random.nextInt(60_000)
                buffer += clickEvent(sessionId, ts, userId)
                if (buffer.size >= GENERATOR_PAGE_SIZE) flush()
            }
        }
        flush()
    }

    // Builds the click counts of each session of one app, so the sum of
    // (1 start row + its clicks) is exactly [totalRows]. Each session
    // holds MIN_CLICKS_PER_SESSION to MAX_CLICKS_PER_SESSION clicks, with
    // one exception: when one row remains after the loop, this function
    // adds it to the last session as one extra click, so the total stays
    // exact.
    private fun sessionPlan(totalRows: Long): List<Int> {
        val clicksPerSession = mutableListOf<Int>()
        var remaining = totalRows
        while (remaining > 1) {
            val maxClicks = minOf(MAX_CLICKS_PER_SESSION.toLong(), remaining - 1).toInt()
            val clicks = MIN_CLICKS_PER_SESSION + random.nextInt(maxClicks - MIN_CLICKS_PER_SESSION + 1)
            clicksPerSession += clicks
            remaining -= (1 + clicks)
        }
        if (remaining == 1L && clicksPerSession.isNotEmpty()) {
            clicksPerSession[clicksPerSession.size - 1] = clicksPerSession.last() + 1
        }
        return clicksPerSession
    }

    private fun pickUserId(): String? {
        if (random.nextInt(100) < ANONYMOUS_PERCENT) return null
        return "perf-user-${random.nextInt(USER_COUNT)}"
    }

    private fun randomStartTs(): Long = BASE_TS_MILLIS + (random.nextDouble() * DAY_SPAN * MILLIS_PER_DAY).toLong()

    private fun sessionStartEvent(sessionId: String, ts: Long, userId: String?) =
        NewEvent(
            eventId = nextEventId(),
            ts = ts,
            element = SESSION_START_ELEMENT,
            sessionId = sessionId,
            userId = userId,
            path = PATH_PATTERNS[random.nextInt(PATH_PATTERNS.size)],
            referrerHost = REFERRER_HOSTS[random.nextInt(REFERRER_HOSTS.size)],
            kind = 1,
        )

    private fun clickEvent(sessionId: String, ts: Long, userId: String?) =
        NewEvent(
            eventId = nextEventId(),
            ts = ts,
            element = "el-${random.nextInt(ELEMENT_COUNT)}",
            sessionId = sessionId,
            userId = userId,
            kind = 0,
        )

    // A unique 24-character hex value from a counter (issue #63, the
    // correction of the brief review): never a random value, so the
    // insert can never collide and drop a row in silence under
    // "ON CONFLICT DO NOTHING".
    private fun nextEventId(): String {
        eventIdCounter++
        return eventIdCounter.toString(16).padStart(EVENT_ID_HEX_LENGTH, '0')
    }
}
