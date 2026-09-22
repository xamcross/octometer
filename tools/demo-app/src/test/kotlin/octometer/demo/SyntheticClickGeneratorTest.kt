package octometer.demo

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import octometer.kit.core.store.InMemoryEventLogStore

/**
 * Tests of the synthetic click generator (issue #14, step 5). Each test
 * needs no Docker; it uses the in-memory store of `kit/jvm-core`.
 */
class SyntheticClickGeneratorTest {

    @Test
    fun `generate writes a minimum of 100 events for 5 user ids`() {
        val store = InMemoryEventLogStore()

        val written = SyntheticClickGenerator.generate(store, Random(1), Instant.parse("2026-09-22T12:00:00Z"))

        assertTrue(written >= 100, "The generator must write a minimum of 100 events.")
        assertEquals(written, store.events().size)
        val userIds = store.events().mapNotNull { it.userId() }.toSet()
        assertEquals(SyntheticClickGenerator.DEMO_USER_IDS.toSet(), userIds)
        assertEquals(5, userIds.size)
    }

    @Test
    fun `each session holds one session start event with a path`() {
        val store = InMemoryEventLogStore()

        SyntheticClickGenerator.generate(store, Random(2), Instant.parse("2026-09-22T12:00:00Z"))

        val sessionStarts = store.events().filter { it.element() == "octo:session-start" }
        assertEquals(25, sessionStarts.size, "5 user ids times 5 sessions gives 25 session starts.")
        for (sessionStart in sessionStarts) {
            assertEquals("/", sessionStart.path(), "Each session start must hold a path.")
        }
    }

    @Test
    fun `each event timestamp sits inside the last 24 hours`() {
        val store = InMemoryEventLogStore()
        val now = Instant.parse("2026-09-22T12:00:00Z")

        SyntheticClickGenerator.generate(store, Random(3), now)

        for (storedEvent in store.events()) {
            val ts = storedEvent.ts()
            assertTrue(!ts.isAfter(now), "An event timestamp must not sit after now.")
            assertTrue(!ts.isBefore(now.minusSeconds(24L * 60 * 60 + 600)), "An event timestamp must sit inside the last day.")
        }
    }
}
