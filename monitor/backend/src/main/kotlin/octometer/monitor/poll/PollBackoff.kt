package octometer.monitor.poll

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import octometer.monitor.mongo.STATUS_UNREACHABLE

/** The backoff cap of design decision D6: 300 seconds. */
private const val MAX_BACKOFF_SECONDS = 300.0

/** The jitter of design decision D6 and the maintainer's decision 3: 10 percent. */
private const val JITTER_FRACTION = 0.1

/**
 * Applies the failed-cycle-count rule of the maintainer's decision 2 to
 * one candidate status. `UNREACHABLE` shows only after 2 failed cycles
 * in sequence; the first failed cycle keeps [oldStatus] instead.
 * `UNAUTHORIZED` and `ERROR` apply at once, with no such rule.
 *
 * [failuresBefore] is [octometer.monitor.poll.AppRow.consecutiveFailures]
 * of the row that [PollScheduler] read at the start of the tick, thus
 * the count of failed cycles before this one.
 */
internal fun failureStatus(candidateStatus: String, oldStatus: String?, failuresBefore: Int): String? =
    if (candidateStatus == STATUS_UNREACHABLE && failuresBefore == 0) oldStatus else candidateStatus

/**
 * The delay of one failed poll cycle (design decision D6, the
 * maintainer's decision 3): `interval * 2^failures`, capped at 300
 * seconds, with a jitter of 10 percent from [random]. [failuresBefore]
 * is the failed-cycle count before this cycle. A test gives a seeded
 * [random], so the result stays reproducible.
 */
internal fun backoffDelayMillis(intervalSeconds: Long, failuresBefore: Int, random: Random): Long {
    // 2.0.pow(failuresBefore) already passes the 300-second cap well
    // before failuresBefore reaches 30. The coerce below keeps the
    // power call inside a safe double range for a much larger count.
    val multiplier = 2.0.pow(failuresBefore.coerceAtMost(30))
    val baseSeconds = min(intervalSeconds * multiplier, MAX_BACKOFF_SECONDS)
    val jitterFraction = random.nextDouble(-JITTER_FRACTION, JITTER_FRACTION)
    return (baseSeconds * 1000.0 * (1.0 + jitterFraction)).roundToLong()
}
