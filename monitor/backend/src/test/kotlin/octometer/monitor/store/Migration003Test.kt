package octometer.monitor.store

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Issue #205: migration 003 adds the table app_path, the distinct path
// values of one app with the first-seen time of each value (design
// decision D5 of 2026-09-27, decision 3 of the correction round).
class Migration003Test {

    private val tempDir = Files.createTempDirectory("octometer-migration-003-test-").toFile()
    private val dataDir = File(tempDir, "data")

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a new database gets user_version 5 and the table app_path`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            // Issue #226 added migration 004; a fresh open now runs
            // every migration, up to the current latest version.
            assertEquals(5, database.write { userVersion(it) })
            assertTrue(database.write { tableExists(it, "app_path") }, "the table app_path exists")
        } finally {
            database.close()
        }
    }

    @Test
    fun `app_path holds app_id, path, and first_seen_at, and a second insert of the same pair is rejected`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            val appId = insertApp(database, "demo")
            insertAppPath(database, appId, "/checkout", 1_700_000_000_000L)

            assertEquals(setOf("/checkout"), readAppPathValues(database, appId))
            assertFailsWith<SQLException> { insertAppPath(database, appId, "/checkout", 1_700_000_100_000L) }
        } finally {
            database.close()
        }
    }

    // deleteApp of AppRegistryService relies on ON DELETE CASCADE
    // (PRAGMA foreign_keys=ON of SqlitePragmas.kt) to remove each
    // app_path row of a deleted app, with no new statement.
    @Test
    fun `a deleted app row cascades to its app_path rows`() = runBlocking {
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            val appId = insertApp(database, "demo")
            insertAppPath(database, appId, "/checkout", 1_700_000_000_000L)

            database.write { writer ->
                writer.prepareStatement("DELETE FROM app WHERE id = ?").use { delete ->
                    delete.setLong(1, appId)
                    delete.executeUpdate()
                }
            }

            assertEquals(emptySet(), readAppPathValues(database, appId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `a second start applies no migration`() = runBlocking {
        SqliteDatabase.open(dataDir.absolutePath).close()

        val secondStart = SqliteDatabase.open(dataDir.absolutePath)
        try {
            assertEquals(5, secondStart.write { userVersion(it) })
        } finally {
            secondStart.close()
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                result.next()
                result.getInt(1)
            }
        }

    private fun tableExists(connection: Connection, name: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { select ->
            select.setString(1, name)
            select.executeQuery().use { it.next() }
        }

    private suspend fun insertApp(database: SqliteDatabase, name: String): Long =
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
            ).use { insert ->
                insert.setString(1, name)
                insert.setString(2, "db")
                insert.setString(3, "octometer_events")
                insert.setLong(4, 1_700_000_000_000L)
                insert.executeUpdate()
            }
            writer.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private suspend fun insertAppPath(database: SqliteDatabase, appId: Long, path: String, firstSeenAt: Long) {
        database.write { writer ->
            writer.prepareStatement(
                "INSERT INTO app_path (app_id, path, first_seen_at) VALUES (?, ?, ?)",
            ).use { insert ->
                insert.setLong(1, appId)
                insert.setString(2, path)
                insert.setLong(3, firstSeenAt)
                insert.executeUpdate()
            }
        }
    }

    private suspend fun readAppPathValues(database: SqliteDatabase, appId: Long): Set<String> =
        database.read { reader ->
            reader.prepareStatement("SELECT path FROM app_path WHERE app_id = ?").use { select ->
                select.setLong(1, appId)
                select.executeQuery().use { result ->
                    val values = mutableSetOf<String>()
                    while (result.next()) values += result.getString(1)
                    values
                }
            }
        }
}
