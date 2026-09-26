package octometer.monitor.mongo

import com.mongodb.MongoCredential
import com.mongodb.MongoSecurityException
import com.mongodb.MongoServerException
import com.mongodb.MongoSocketOpenException
import com.mongodb.MongoTimeoutException
import com.mongodb.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString

/**
 * The exception map of design decision D8 (issue #28, the maintainer's
 * decision 1). Each test here gives the real driver exception, with no
 * MongoDB server and no Docker, because [mongoFailureStatus] reads only
 * the type and the fields of the exception, never a network call.
 * [MongoAppReaderContainerTest] proves the same rules again against a
 * real server: a wrong password, a stopped container, and an unknown
 * exception.
 */
class MongoFailureMappingTest {

    @Test
    fun `MongoSecurityException gives UNAUTHORIZED, with no code`() {
        val credential = MongoCredential.createCredential("reader", "admin", CharArray(0))
        val failure = MongoSecurityException(credential, "denied")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_UNAUTHORIZED, mapped.status)
        assertEquals(null, mapped.code)
    }

    @Test
    fun `a command error code 13 gives UNAUTHORIZED, with that code`() {
        val failure = commandException(13, "not authorized on exampledb")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_UNAUTHORIZED, mapped.status)
        assertEquals(13, mapped.code)
    }

    @Test
    fun `a command error code 8000 with the text not allowed to do action gives UNAUTHORIZED`() {
        val failure = commandException(8000, "user is not allowed to do action [find]")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_UNAUTHORIZED, mapped.status)
        assertEquals(8000, mapped.code)
    }

    @Test
    fun `a command error code 8000 with a different text gives ERROR, not UNAUTHORIZED`() {
        val failure = commandException(8000, "some other server text")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_ERROR, mapped.status)
        assertEquals(8000, mapped.code)
    }

    @Test
    fun `a command error code that names neither rule gives ERROR, with that code`() {
        val failure = commandException(99, "some other server error")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_ERROR, mapped.status)
        assertEquals(99, mapped.code)
    }

    @Test
    fun `a timeout gives the candidate UNREACHABLE, with no code`() {
        val failure = MongoTimeoutException("Timed out while waiting for a server.")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_UNREACHABLE, mapped.status)
        assertEquals(null, mapped.code)
    }

    @Test
    fun `a socket failure gives the candidate UNREACHABLE, with no code`() {
        val failure = MongoSocketOpenException("Exception opening socket", ServerAddress(), RuntimeException("connect refused"))

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_UNREACHABLE, mapped.status)
        assertEquals(null, mapped.code)
    }

    @Test
    fun `an unknown exception gives ERROR, with no code`() {
        val failure = IllegalStateException("a probe failure")

        val mapped = mongoFailureStatus(failure)

        assertEquals(STATUS_ERROR, mapped.status)
        assertEquals(null, mapped.code)
    }

    private fun commandException(code: Int, errorMessage: String): MongoServerException {
        val response = BsonDocument()
            .append("ok", BsonInt32(0))
            .append("code", BsonInt32(code))
            .append("errmsg", BsonString(errorMessage))
        return com.mongodb.MongoCommandException(response, ServerAddress())
    }
}
