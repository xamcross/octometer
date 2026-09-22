package octometer.monitor.retention

/**
 * Gives the current time, in epoch milliseconds. A test gives a fixed
 * value, so a test of the purge needs no real sleep and no real clock
 * (issue #59, step 4).
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** The real clock. The production code uses it. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
