package octometer.monitor.poll

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import octometer.monitor.registerTempRoot
import octometer.monitor.store.SqliteDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one SQLite-backed test of [PollScheduler]'s store seam (issue #17,
 * decision 7; MAJOR 7 of the Kotlin review). This class proves the SQL
 * text of [SqlitePollStore]. Each test of [PollSchedulerTest] gives an
 * in-memory fake instead, so no test there mixes virtual time with this
 * real writer thread.
 */
class SqlitePollStoreTest {

    private val root = Files.createTempDirectory("octometer-poll-store-test-").toFile().also { registerTempRoot(it) }
    private val dataDir = File(root, "data").absolutePath

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `readApps gives one AppRow for each app row, with a null cursor and a null next_poll_at kept as null`() =
        runBlocking {
            val database = SqliteDatabase.open(dataDir)
            try {
                val store = SqlitePollStore(database)
                val dueAppId = insertApp(database, "due-app", cursor = "cursor-1", nextPollAt = 1_000L)
                val newAppId = insertApp(database, "new-app", cursor = null, nextPollAt = null)

                val rows = store.readApps().associateBy { it.appId }

                assertEquals(AppRow(dueAppId, "db", "octometer_events", "cursor-1", 1_000L), rows.getValue(dueAppId))
                assertEquals(AppRow(newAppId, "db", "octometer_events", null, null), rows.getValue(newAppId))
            } finally {
                database.close()
            }
        }

    @Test
    fun `writeResult sets next_poll_at and the cursor, and touches zero rows for a deleted app`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val store = SqlitePollStore(database)
            val appId = insertApp(database, "demo", cursor = "old-cursor", nextPollAt = 0L)

            store.writeResult(appId, nextPollAt = 5_000L, cursor = "new-cursor")

            val row = store.readApps().single { it.appId == appId }
            assertEquals(5_000L, row.nextPollAt)
            assertEquals("new-cursor", row.cursor)

            // A deleted app gives zero updated rows, and this never
            // throws for that case (issue #17, decision 7).
            deleteApp(database, appId)
            store.writeResult(appId, nextPollAt = 6_000L, cursor = "unreachable-cursor")
        } finally {
            database.close()
        }
    }

    @Test
    fun `recordFailure sets next_poll_at only, and leaves the cursor unchanged`() = runBlocking {
        val database = SqliteDatabase.open(dataDir)
        try {
            val store = SqlitePollStore(database)
            val appId = insertApp(database, "demo", cursor = "old-cursor", nextPollAt = 0L)

            store.recordFailure(appId, nextPollAt = 5_000L)

            val row = store.readApps().single { it.appId == appId }
            assertEquals(5_000L, row.nextPollAt)
            assertEquals("old-cursor", row.cursor, "a failed cycle never moves the cursor")

            // A deleted app gives zero updated rows, and this never
            // throws for that case (issue #17, decision 7).
            deleteApp(database, appId)
            store.recordFailure(appId, nextPollAt = 6_000L)
            assertNull(store.readApps().find { it.appId == appId })
        } finally {
            database.close()
        }
    }
}

private suspend fun insertApp(
    database: SqliteDatabase,
    name: String,
    cursor: String?,
    nextPollAt: Long?,
    databaseName: String = "db",
    collectionName: String = "octometer_events",
): Long =
    database.write { writer ->
        writer.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at, next_poll_at, cursor) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, databaseName)
            insert.setString(3, collectionName)
            insert.setLong(4, 0L)
            if (nextPollAt != null) insert.setLong(5, nextPollAt) else insert.setNull(5, java.sql.Types.INTEGER)
            insert.setString(6, cursor)
            insert.executeUpdate()
        }
        lastInsertId(writer)
    }

private fun lastInsertId(writer: Connection): Long =
    writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            check(result.next()) { "No id after the insert." }
            result.getLong(1)
        }
    }

private suspend fun deleteApp(database: SqliteDatabase, appId: Long) {
    database.write { writer ->
        writer.prepareStatement("DELETE FROM app WHERE id = ?").use { delete ->
            delete.setLong(1, appId)
            delete.executeUpdate()
        }
    }
}
