package octometer.monitor.retention

/** A fixed clock for a test. It never reads the real system clock. */
class FakeClock(private val fixedMillis: Long) : Clock {
    override fun nowMillis(): Long = fixedMillis
}
