package octometer.monitor.perf

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.runBlocking
import octometer.monitor.apps.loadAppTotals
import octometer.monitor.elements.ElementsFilter
import octometer.monitor.elements.loadElementTotals
import octometer.monitor.pages.countFirstPages
import octometer.monitor.pages.readFirstPagesPage
import octometer.monitor.sessions.countSessions
import octometer.monitor.sessions.readSessionsPage
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.users.countUserGroups
import octometer.monitor.users.readUserTotalsPage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

// The default sizes of issue #63, step 5, and decision 2 of the brief.
// The system property "octometer.perf.sizes" overrides this list, as a
// comma-separated list, for example "1000000".
private val DEFAULT_SIZES = listOf(1_000_000L, 10_000_000L)

// Decision 2: three runs of each statement, the median in milliseconds.
private const val TIMED_RUNS = 3

private const val PERF_ROOT = "build/perf"

/**
 * The perf test of issue #63. It builds one database per size, at
 * [DEFAULT_SIZES] or at the sizes of the system property
 * "octometer.perf.sizes", with [EventDataGenerator]. It runs each
 * statement of the three levels three times, through the route
 * functions, and records the median in milliseconds. It also keeps the
 * first (cold) run beside the median, for the report of the correction
 * of the brief review.
 *
 * This test asserts nothing about the time: a measurement is not a
 * limit. It asserts the row count of the database, so a defect of the
 * generator fails this test, not a silent short count.
 */
@Tag("perf")
class LevelQueryPerfTest {

    @Test
    fun measuresTheThreeLevelsAtEachSize() = runBlocking {
        val perfRoot = File(PERF_ROOT)
        perfRoot.mkdirs()
        for (size in readSizes()) {
            val rows = measureOneSize(perfRoot, size)
            val table = renderTable(size, rows)
            println(table)
            File(perfRoot, "result-$size.md").writeText(table)
        }
    }

    private suspend fun measureOneSize(perfRoot: File, size: Long): List<MeasuredRow> {
        val dataDir = File(perfRoot, "db-$size/data")
        dataDir.deleteRecursively()
        val database = SqliteDatabase.open(dataDir.absolutePath)
        try {
            val generator = EventDataGenerator(database)
            val appIds = generator.generate(size)
            assertRowCount(database, size)
            val appId = appIds.first()
            val rows = measureLevels(database, generator.accountId, appId)
            val fileSizeBytes = checkpointAndMeasureFileSize(database, dataDir)
            return rows.map { it.copy(fileSizeBytes = fileSizeBytes) }
        } finally {
            database.close()
            // Decision 4 of the correction: this deletes the database of
            // one size before the next size runs, so the 10 million run
            // never needs the disk space of two databases at once. The
            // result file already left this folder, under perfRoot
            // itself, so this delete never touches it.
            File(perfRoot, "db-$size").deleteRecursively()
        }
    }

    private suspend fun assertRowCount(database: SqliteDatabase, expected: Long) {
        val actual = database.read { reader ->
            reader.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM event").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }
        assertEquals(expected, actual, "The generator wrote $actual event rows, not the requested $expected.")
    }

    private suspend fun measureLevels(database: SqliteDatabase, accountId: Long, appId: Long): List<MeasuredRow> {
        val rows = mutableListOf<MeasuredRow>()
        rows += measureRow("Level 1 (app totals)", database) { loadAppTotals(database, accountId) }
        rows += measureConnectionRow("Level 2 count (users)", database) { reader ->
            countUserGroups(reader, appId, null)
        }
        rows += measureConnectionRow("Level 2 page (users)", database) { reader ->
            readUserTotalsPage(reader, appId, null, 1)
        }
        rows += measureRow("Level 3 (elements)", database) {
            loadElementTotals(database, accountId, appId, ElementsFilter.Anonymous)
        }
        rows += measureConnectionRow("First pages count", database) { reader ->
            countFirstPages(reader, appId)
        }
        rows += measureConnectionRow("First pages page", database) { reader ->
            readFirstPagesPage(reader, appId, 1)
        }
        rows += measureConnectionRow("Sessions count", database) { reader ->
            countSessions(reader, appId, null)
        }
        rows += measureConnectionRow("Sessions page", database) { reader ->
            readSessionsPage(reader, appId, null, 1)
        }
        return rows
    }

    private suspend fun measureRow(level: String, database: SqliteDatabase, block: suspend () -> Unit): MeasuredRow {
        val samples = LongArray(TIMED_RUNS)
        repeat(TIMED_RUNS) { index ->
            val start = System.nanoTime()
            block()
            samples[index] = (System.nanoTime() - start) / 1_000_000
        }
        return MeasuredRow(level, samples[0], median(samples))
    }

    private suspend fun measureConnectionRow(
        level: String,
        database: SqliteDatabase,
        block: (Connection) -> Unit,
    ): MeasuredRow {
        val samples = LongArray(TIMED_RUNS)
        repeat(TIMED_RUNS) { index ->
            var elapsedMs = 0L
            database.read { reader ->
                val start = System.nanoTime()
                block(reader)
                elapsedMs = (System.nanoTime() - start) / 1_000_000
            }
            samples[index] = elapsedMs
        }
        return MeasuredRow(level, samples[0], median(samples))
    }

    private fun median(samples: LongArray): Long {
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    // PRAGMA wal_checkpoint(TRUNCATE), then Files.size of octometer.db,
    // as decision 1 of the brief asks for the byte size per event.
    private suspend fun checkpointAndMeasureFileSize(database: SqliteDatabase, dataDir: File): Long {
        database.write { writer ->
            writer.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
        return Files.size(File(dataDir, "octometer.db").toPath())
    }

    private fun readSizes(): List<Long> {
        val raw = System.getProperty("octometer.perf.sizes")
        if (raw.isNullOrBlank()) return DEFAULT_SIZES
        return raw.split(",").map { it.trim().toLong() }
    }

    private fun renderTable(size: Long, rows: List<MeasuredRow>): String {
        val header = "| Level | Size | First (ms) | Median (ms) | File size (bytes) | Bytes/event |"
        val divider = "|---|---|---|---|---|---|"
        val body = rows.joinToString("\n") { row ->
            val bytesPerEvent = row.fileSizeBytes / size
            "| ${row.level} | $size | ${row.firstMs} | ${row.medianMs} | ${row.fileSizeBytes} | $bytesPerEvent |"
        }
        return listOf(header, divider, body).joinToString("\n")
    }
}

private data class MeasuredRow(
    val level: String,
    val firstMs: Long,
    val medianMs: Long,
    val fileSizeBytes: Long = 0L,
)
