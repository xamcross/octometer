package octometer.kit.ktor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import octometer.kit.core.ingest.IngestEvent
import octometer.kit.core.ingest.IngestException
import octometer.kit.core.store.DeletionResult
import octometer.kit.core.store.EventLogStore

/**
 * Tests of `DefectSafeEventLogStore` (MINOR 6 of the MongoDB review of
 * pull request #157). The ingest route never calls `deleteByUserId`, so
 * no test of `IngestRouteTest` reaches this branch of the wrapper.
 */
class DefectSafeEventLogStoreTest {

    @Test
    fun `deleteByUserId passes the answer of the delegate through`() {
        val expected = DeletionResult(2, 1, true)
        val delegate = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                throw UnsupportedOperationException()
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                assertEquals("user-1", userId)
                return expected
            }
        }

        val result = DefectSafeEventLogStore(delegate).deleteByUserId("user-1")

        assertSame(expected, result)
    }

    @Test
    fun `deleteByUserId wraps an IngestException of the delegate`() {
        val cause = IngestException(IngestException.Reason.INVALID_JSON, "a test failure")
        val delegate = object : EventLogStore {
            override fun append(events: List<IngestEvent>, userId: String?) {
                throw UnsupportedOperationException()
            }

            override fun deleteByUserId(userId: String): DeletionResult {
                throw cause
            }
        }

        val thrown = assertFailsWith<IllegalStateException> {
            DefectSafeEventLogStore(delegate).deleteByUserId("user-1")
        }

        assertSame(cause, thrown.cause)
    }
}
