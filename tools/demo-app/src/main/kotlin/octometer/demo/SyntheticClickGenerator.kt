package octometer.demo

import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.store.EventLogStore

/**
 * Writes synthetic clicks through the store, not through HTTP (issue #14,
 * step 5). One call writes 5 sessions for each of 5 demo user ids, so it
 * writes a minimum of 100 events for 5 user ids. Each session holds one
 * `octo:session-start` event and 5 click events, with a `path`. Each
 * session start time is inside the last 24 hours.
 */
object SyntheticClickGenerator {

    /** The 5 demo user ids of the acceptance criterion of issue #14. */
    val DEMO_USER_IDS: List<String> = listOf("amy", "ben", "cleo", "dax", "eve")

    private val CLICK_ELEMENTS = listOf("demo.button-one", "demo.button-two", "demo.button-three")
    private const val PATH = "/"
    private const val SESSIONS_PER_USER = 5
    private const val CLICKS_PER_SESSION = 5
    private const val SECONDS_IN_A_DAY = 24L * 60 * 60

    /**
     * Writes the synthetic events to [store], and returns the count of
     * written events. [random] and [now] let a test give a fixed source,
     * so the test result stays stable.
     */
    fun generate(store: EventLogStore, random: Random = Random.Default, now: Instant = Instant.now()): Int {
        var writtenCount = 0
        for (userId in DEMO_USER_IDS) {
            repeat(SESSIONS_PER_USER) {
                writtenCount += writeOneSession(store, userId, random, now)
            }
        }
        return writtenCount
    }

    private fun writeOneSession(store: EventLogStore, userId: String, random: Random, now: Instant): Int {
        val sessionId = UUID.randomUUID().toString()
        val sessionStart = now.minusSeconds(random.nextLong(0, SECONDS_IN_A_DAY))
        val events = ArrayList<IngestEvent>(CLICKS_PER_SESSION + 1)
        events.add(IngestEvent(sessionId, "octo:session-start", sessionStart, PATH, null))
        var clickTime = sessionStart
        repeat(CLICKS_PER_SESSION) {
            clickTime = clickTime.plusSeconds(random.nextLong(5, 120))
            val element = CLICK_ELEMENTS[random.nextInt(CLICK_ELEMENTS.size)]
            events.add(IngestEvent(sessionId, element, clickTime, PATH, null))
        }
        store.append(events, userId)
        return events.size
    }
}
