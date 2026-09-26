package octometer.monitor.mongo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bson.Document

/**
 * The unit test of the pure function [evaluatePrivileges] (design
 * decision D9, issue #30). It reads no MongoDB server and no Docker.
 */
class PrivilegeCheckTest {

    @Test
    fun `the fixture of the real M0 run gives OK`() {
        val fixture = readFixture()
        val connectionStatus = fixture.get("connectionStatus", Document::class.java)
        val listCollections = fixture.get("listCollections", Document::class.java)
        @Suppress("UNCHECKED_CAST")
        val visibleCollections = listCollections.get("collections") as List<String>

        val verdict = evaluatePrivileges(connectionStatus, visibleCollections, "exampledb", "octometer_events")

        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 1 fails for a list that holds a name other than the configured collection`() {
        val verdict = evaluatePrivileges(okConnectionStatus(), listOf("a-different-collection"), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_CHECK1_FAILED)
    }

    @Test
    fun `check 1 passes for a list that holds only the configured collection`() {
        val verdict = evaluatePrivileges(okConnectionStatus(), listOf("octometer_events"), "exampledb", "octometer_events")
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 2 fails for a second privilege entry`() {
        val connectionStatus = connectionStatusWith(
            privilege("exampledb", "octometer_events", listOf("find")),
            privilege("exampledb", "octometer_events", listOf("find")),
        )
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for a second action`() {
        val connectionStatus = connectionStatusWith(privilege("exampledb", "octometer_events", listOf("find", "insert")))
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for a cluster resource`() {
        val privilegeDoc = Document("resource", Document("cluster", true)).append("actions", listOf(FIND_ACTION_FOR_TEST))
        val verdict = evaluatePrivileges(connectionStatusWith(privilegeDoc), emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for an empty collection`() {
        val connectionStatus = connectionStatusWith(privilege("exampledb", "", listOf("find")))
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `an absent privilege list and an empty visible list give no evidence`() {
        val connectionStatus = Document("authInfo", Document())
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_NO_EVIDENCE)
    }

    @Test
    fun `an empty privilege list and an empty visible list give no evidence`() {
        val connectionStatus = connectionStatusWith()
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", "octometer_events")
        assertFailed(verdict, REASON_NO_EVIDENCE)
    }

    @Test
    fun `an absent privilege list with the configured collection visible passes on check 1 alone`() {
        val connectionStatus = Document("authInfo", Document())
        val verdict = evaluatePrivileges(connectionStatus, listOf("octometer_events"), "exampledb", "octometer_events")
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    private fun assertFailed(verdict: PrivilegeVerdict, reason: String) {
        assertTrue(verdict is PrivilegeVerdict.Failed, "Expected a failed verdict, got $verdict.")
        assertEquals(reason, verdict.reason)
    }

    private fun okConnectionStatus(): Document = connectionStatusWith(privilege("exampledb", "octometer_events", listOf("find")))

    private fun connectionStatusWith(vararg privileges: Document): Document =
        Document("authInfo", Document("authenticatedUserPrivileges", privileges.toList()))

    private fun privilege(database: String, collection: String, actions: List<String>): Document =
        Document("resource", Document("db", database).append("collection", collection)).append("actions", actions)

    /**
     * Reads the fixture, with the module folder `monitor/backend` as the
     * working directory (the implementer rules of this repository). A
     * missing file gives a clear message, never a bare stack trace.
     */
    private fun readFixture(): Document {
        val file = File("../../contract/fixtures/connection-status-m0.json")
        check(file.exists()) { "The fixture file is absent: ${file.path}" }
        return Document.parse(file.readText())
    }
}

private const val FIND_ACTION_FOR_TEST = "find"
