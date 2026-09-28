package octometer.monitor.mongo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bson.Document

/** The collection set of the one-collection role, the form of the fixture of contract/fixtures/connection-status-m0.json. */
private val EVENTS_ONLY = setOf("octometer_events")

/** The collection set of the two-collection role of design decision D47 (issue #276). */
private val EVENTS_AND_CONFIG = setOf("octometer_events", "octometer_config")

/**
 * The unit test of the pure function [evaluatePrivileges] (design
 * decision D9, issue #30; the two collections of design decision D47,
 * issue #276). It reads no MongoDB server and no Docker.
 */
class PrivilegeCheckTest {

    @Test
    fun `the fixture of the real M0 run gives OK`() {
        val fixture = readFixture("connection-status-m0.json")
        val connectionStatus = fixture.get("connectionStatus", Document::class.java)
        val visibleCollections = collectionNames(fixture.get("listCollections", Document::class.java))

        val verdict = evaluatePrivileges(connectionStatus, visibleCollections, "exampledb", EVENTS_ONLY)

        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `the fixture of the two-collection role gives OK`() {
        val fixture = readFixture("connection-status-two-collections.json")
        val connectionStatus = fixture.get("connectionStatus", Document::class.java)
        val visibleCollections = collectionNames(fixture.get("listCollections", Document::class.java))

        val verdict = evaluatePrivileges(connectionStatus, visibleCollections, "exampledb", EVENTS_AND_CONFIG)

        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 1 fails for a list that holds a name outside the two collections`() {
        val verdict = evaluatePrivileges(okConnectionStatus(), listOf("a-different-collection"), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_CHECK1_FAILED)
    }

    @Test
    fun `check 1 passes for a list that holds only the configured collection`() {
        val verdict = evaluatePrivileges(okConnectionStatus(), listOf("octometer_events"), "exampledb", EVENTS_ONLY)
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 1 passes for a list that holds only the config collection`() {
        val verdict = evaluatePrivileges(twoCollectionConnectionStatus(), listOf("octometer_config"), "exampledb", EVENTS_AND_CONFIG)
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 1 passes for the two collections in the reverse order`() {
        val verdict = evaluatePrivileges(
            twoCollectionConnectionStatus(),
            listOf("octometer_config", "octometer_events"),
            "exampledb",
            EVENTS_AND_CONFIG,
        )
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 1 fails for a third collection beside the two configured ones`() {
        val verdict = evaluatePrivileges(
            twoCollectionConnectionStatus(),
            listOf("octometer_events", "octometer_config", "a-third-collection"),
            "exampledb",
            EVENTS_AND_CONFIG,
        )
        assertFailed(verdict, REASON_CHECK1_FAILED)
    }

    @Test
    fun `check 2 passes for find on the config collection alone`() {
        val connectionStatus = connectionStatusWith(privilege("exampledb", "octometer_config", listOf("find")))
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_AND_CONFIG)
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 2 passes for find on each of the two collections`() {
        val verdict = evaluatePrivileges(twoCollectionConnectionStatus(), emptyList(), "exampledb", EVENTS_AND_CONFIG)
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    @Test
    fun `check 2 fails for a third privilege entry beside the two configured collections`() {
        val connectionStatus = connectionStatusWith(
            privilege("exampledb", "octometer_events", listOf("find")),
            privilege("exampledb", "octometer_config", listOf("find")),
            privilege("exampledb", "octometer_events", listOf("find")),
        )
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_AND_CONFIG)
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for a second entry that repeats the same collection`() {
        val connectionStatus = connectionStatusWith(
            privilege("exampledb", "octometer_events", listOf("find")),
            privilege("exampledb", "octometer_events", listOf("find")),
        )
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_AND_CONFIG)
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for a second action`() {
        val connectionStatus = connectionStatusWith(privilege("exampledb", "octometer_events", listOf("find", "insert")))
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for a cluster resource`() {
        val privilegeDoc = Document("resource", Document("cluster", true)).append("actions", listOf(FIND_ACTION_FOR_TEST))
        val verdict = evaluatePrivileges(connectionStatusWith(privilegeDoc), emptyList(), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `check 2 fails for an empty collection`() {
        val connectionStatus = connectionStatusWith(privilege("exampledb", "", listOf("find")))
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_CHECK2_FAILED)
    }

    @Test
    fun `an absent privilege list and an empty visible list give no evidence`() {
        val connectionStatus = Document("authInfo", Document())
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_NO_EVIDENCE)
    }

    @Test
    fun `an empty privilege list and an empty visible list give no evidence`() {
        val connectionStatus = connectionStatusWith()
        val verdict = evaluatePrivileges(connectionStatus, emptyList(), "exampledb", EVENTS_ONLY)
        assertFailed(verdict, REASON_NO_EVIDENCE)
    }

    @Test
    fun `an absent privilege list with the configured collection visible passes on check 1 alone`() {
        val connectionStatus = Document("authInfo", Document())
        val verdict = evaluatePrivileges(connectionStatus, listOf("octometer_events"), "exampledb", EVENTS_ONLY)
        assertEquals(PrivilegeVerdict.Ok, verdict)
    }

    private fun assertFailed(verdict: PrivilegeVerdict, reason: String) {
        assertTrue(verdict is PrivilegeVerdict.Failed, "Expected a failed verdict, got $verdict.")
        assertEquals(reason, verdict.reason)
    }

    private fun okConnectionStatus(): Document = connectionStatusWith(privilege("exampledb", "octometer_events", listOf("find")))

    private fun twoCollectionConnectionStatus(): Document = connectionStatusWith(
        privilege("exampledb", "octometer_events", listOf("find")),
        privilege("exampledb", "octometer_config", listOf("find")),
    )

    private fun connectionStatusWith(vararg privileges: Document): Document =
        Document("authInfo", Document("authenticatedUserPrivileges", privileges.toList()))

    private fun privilege(database: String, collection: String, actions: List<String>): Document =
        Document("resource", Document("db", database).append("collection", collection)).append("actions", actions)

    /**
     * Reads the collection names of the `listCollections` field of a
     * fixture, in the reduced shape of `check-privileges.js`: a list of
     * `{name, type}` documents, never a plain list of strings.
     */
    @Suppress("UNCHECKED_CAST")
    private fun collectionNames(listCollections: Document): List<String> =
        (listCollections.get("collections") as List<Document>).map { it.getString("name") }

    /**
     * Reads a fixture of `contract/fixtures/`, with the module folder
     * `monitor/backend` as the working directory (the implementer
     * rules of this repository). A missing file gives a clear message,
     * never a bare stack trace.
     */
    private fun readFixture(fileName: String): Document {
        val file = File("../../contract/fixtures/$fileName")
        check(file.exists()) { "The fixture file is absent: ${file.path}" }
        return Document.parse(file.readText())
    }
}

private const val FIND_ACTION_FOR_TEST = "find"
