package octometer.monitor.mongo

import com.mongodb.MongoCommandException
import com.mongodb.MongoSecurityException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException

/**
 * The status values of design decision D8, for a failed poll cycle.
 * [STATUS_OK] and [STATUS_INVALID_DATA] of [MongoAppReader] cover a
 * good cycle.
 */
internal const val STATUS_UNAUTHORIZED = "UNAUTHORIZED"
internal const val STATUS_UNREACHABLE = "UNREACHABLE"
internal const val STATUS_ERROR = "ERROR"

private const val ERROR_CODE_UNAUTHORIZED = 13
private const val ERROR_CODE_NOT_ALLOWED = 8000
private const val NOT_ALLOWED_TEXT = "not allowed to do action"

/**
 * The mapped status and the command error code of one failed MongoDB
 * read (design decision D8, the maintainer's decision 1). [status] is
 * the candidate status: [MongoAppReader.pollOnce]'s caller still applies
 * the failed-cycle-count rule of decision 5 of issue #28 before it
 * writes the app row. [code] is the numeric error code of a
 * [MongoCommandException], or `null` for each other exception.
 */
internal data class MongoFailureStatus(val status: String, val code: Int?)

/**
 * Maps [failure] to a [MongoFailureStatus] (design decision D8). Only
 * [octometer.monitor.mongo.MongoAppReader]'s own wrap site calls this
 * function, where the real exception still exists. The caller then
 * builds a [MongoReadFailedException] from the two result fields, with
 * no cause and no message text of [failure] (the maintainer's decision
 * 1). `OVERPRIVILEGED` names no rule here: design decision D9 checks the
 * privilege list on its own, not through a caught exception, and the
 * maintainer's decision 1 leaves it out of this map on purpose.
 */
internal fun mongoFailureStatus(failure: Throwable): MongoFailureStatus {
    if (failure is MongoSecurityException) return MongoFailureStatus(STATUS_UNAUTHORIZED, code = null)
    if (failure is MongoCommandException) {
        val code = failure.errorCode
        val deniedAction = code == ERROR_CODE_NOT_ALLOWED && failure.errorMessage.contains(NOT_ALLOWED_TEXT)
        val status = if (code == ERROR_CODE_UNAUTHORIZED || deniedAction) STATUS_UNAUTHORIZED else STATUS_ERROR
        return MongoFailureStatus(status, code)
    }
    if (failure is MongoTimeoutException || failure is MongoSocketException) {
        return MongoFailureStatus(STATUS_UNREACHABLE, code = null)
    }
    return MongoFailureStatus(STATUS_ERROR, code = null)
}
