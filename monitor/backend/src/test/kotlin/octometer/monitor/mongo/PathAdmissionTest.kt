package octometer.monitor.mongo

import kotlin.test.Test
import kotlin.test.assertEquals

// Issue #205, decision 4 of the correction round: the per-app, per-cycle
// cap on the count of distinct path values. Each case here calls admit
// directly, with no database and no reader.
class PathAdmissionTest {

    @Test
    fun `the 200th distinct value joins`() {
        val admission = PathAdmission(emptySet(), cap = 200)
        repeat(199) { index -> admission.admit("/path-$index") }

        val result = admission.admit("/path-199")

        assertEquals("/path-199", result)
        assertEquals(200, admission.drainNewValues().size)
    }

    @Test
    fun `the 201st distinct value lands as OTHER_PATH`() {
        val admission = PathAdmission(emptySet(), cap = 200)
        repeat(200) { index -> admission.admit("/path-$index") }

        val result = admission.admit("/path-200")

        assertEquals(OTHER_PATH, result)
    }

    @Test
    fun `a known value passes at the cap`() {
        val admission = PathAdmission(emptySet(), cap = 200)
        repeat(200) { index -> admission.admit("/path-$index") }

        val result = admission.admit("/path-0")

        assertEquals("/path-0", result, "a value already in the set must pass, even at the cap")
    }

    @Test
    fun `a value already loaded from the store counts toward the cap`() {
        val admission = PathAdmission(setOf("/loaded"), cap = 1)

        val result = admission.admit("/new")

        assertEquals(OTHER_PATH, result, "the loaded value already fills the cap of 1")
    }

    @Test
    fun `drainNewValues gives each admitted value once, and clears for the next page`() {
        val admission = PathAdmission(emptySet(), cap = 200)
        admission.admit("/checkout")
        admission.admit("/checkout")
        admission.admit("/cart")

        val firstDrain = admission.drainNewValues()
        val secondDrain = admission.drainNewValues()

        assertEquals(setOf("/checkout", "/cart"), firstDrain)
        assertEquals(emptySet(), secondDrain, "a second drain with no new admit must give an empty set")
    }
}
