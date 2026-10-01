package octometer.monitor.collect

import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import octometer.monitor.store.SqliteDatabase

/** The daily cap of design decision D50: 20 000 events for one app, one UTC day. */
const val DAILY_EVENT_CAP = 20_000L

private val DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

/**
 * The persisted daily cap of design decision D50 (issue #297): a
 * counter in the registry for one app and one UTC day, in the table
 * `daily_event_count` (migration `008_route_patterns_and_daily_count.sql`).
 * Unlike the in-memory anonymous caps of design decision D43, this
 * counter survives a restart of the monitor.
 */
class DailyEventCountStore(private val database: SqliteDatabase, private val clock: Clock = Clock.systemUTC()) {

    // The drop counter of GET /api/apps/{id} (droppedToday, the brief
    // review of 2026-10-01, M5). The migration of this issue adds one
    // SQLite column only (count, the accepted-event total of the day,
    // read below against DAILY_EVENT_CAP); this map is a process-life
    // counter of the drops, not a persisted, day-scoped one. It resets
    // at each restart of the monitor, a known simplification: the
    // pull request text states it, and issue #299 or a later issue can
    // persist it beside route_patterns once an app needs an exact count
    // across a restart.
    private val droppedTodayCounters = ConcurrentHashMap<Long, AtomicLong>()

    /** Records one dropped batch of [appId] for the drop counter of [droppedToday]. */
    fun recordDrop(appId: Long) {
        droppedTodayCounters.computeIfAbsent(appId) { AtomicLong() }.incrementAndGet()
    }

    /** The process-life drop count of [appId] (see the field comment above). */
    fun droppedToday(appId: Long): Long = droppedTodayCounters[appId]?.get() ?: 0L

    /**
     * Adds [eventCount] to the counter of [appId] for today (UTC), when
     * the counter would stay at or under [DAILY_EVENT_CAP]. Returns
     * `false`, with no write, when the batch would push the counter
     * above the cap; the caller then answers 204 and stores nothing
     * (design decision D50).
     */
    suspend fun tryIncrement(appId: Long, eventCount: Int): Boolean =
        database.write { writer ->
            val day = today()
            val current = readCount(writer, appId, day)
            val next = current + eventCount
            if (next > DAILY_EVENT_CAP) {
                false
            } else {
                upsertCount(writer, appId, day, next)
                true
            }
        }

    /** The today-UTC count of [appId], for `GET /api/apps/{id}` (`droppedToday` of the brief review, M5). */
    suspend fun todayCount(appId: Long): Long =
        database.read { reader -> readCount(reader, appId, today()) }

    private fun today(): String = DAY_FORMATTER.format(Instant.ofEpochMilli(clock.millis()))

    private fun readCount(connection: Connection, appId: Long, day: String): Long =
        connection.prepareStatement("SELECT count FROM daily_event_count WHERE app_id = ? AND day = ?").use { select ->
            select.setLong(1, appId)
            select.setString(2, day)
            select.executeQuery().use { result -> if (result.next()) result.getLong("count") else 0L }
        }

    private fun upsertCount(connection: Connection, appId: Long, day: String, count: Long) {
        connection.prepareStatement(
            "INSERT INTO daily_event_count (app_id, day, count) VALUES (?, ?, ?) " +
                "ON CONFLICT(app_id, day) DO UPDATE SET count = excluded.count",
        ).use { upsert ->
            upsert.setLong(1, appId)
            upsert.setString(2, day)
            upsert.setLong(3, count)
            upsert.executeUpdate()
        }
    }
}
