package octometer.monitor.erasure

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.URLEncoder
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import octometer.monitor.allowedHost
import octometer.monitor.captureLogEvents
import octometer.monitor.module
import octometer.monitor.prodConfig
import octometer.monitor.store.SqliteDatabase
import octometer.monitor.testDataDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Issue #61, steps 4, 5, and 6. The route DELETE /api/apps/{appId}/events
// erases each event of one user id of one app, plus the anonymous events
// of a session of that user. It never quotes the user id in a log line.
//
// Each test opens the store one time to create its rows with a plain
// JDBC connection, and closes it before testApplication opens its own
// SqliteDatabase on the same folder. Two open writer connections on one
// SQLite file at the same time would give a lock error.
class UserErasureRoutesTest {

    @Test
    fun `DELETE removes the rows of the user and gives 200 with the deleted count`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")
        insertEvent(dataDir, appId, "e1", sessionId = "s1", userId = "user-a")
        insertEvent(dataDir, appId, "e2", sessionId = "s1", userId = "user-b")

        val response = runErase(dataDir, appId, "user-a")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, response.deletedField())
        assertEquals(listOf("user-b"), remainingUserIds(dataDir, appId))
    }

    @Test
    fun `DELETE also removes the anonymous rows of a session of the user`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")
        insertEvent(dataDir, appId, "e1", sessionId = "s1", userId = null)
        insertEvent(dataDir, appId, "e2", sessionId = "s1", userId = "user-a")
        insertEvent(dataDir, appId, "e3", sessionId = "s2", userId = null)

        val response = runErase(dataDir, appId, "user-a")

        assertEquals(2, response.deletedField())
        assertEquals(1, countEvents(dataDir, appId))
    }

    @Test
    fun `DELETE with an unknown app id gives 404`() {
        val dataDir = prepareStore()

        val response = runErase(dataDir, 999L, "user-a")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE with a non-numeric app id gives 400`() = testApplication {
        application { module(prodConfig()) }

        val response = client.delete("/api/apps/not-a-number/events?userId=user-a") {
            allowedHost()
            header(HttpHeaders.Origin, "http://localhost:7431")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `DELETE with no userId query parameter gives 400`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")

        lateinit var status: HttpStatusCode
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            status = client.delete("/api/apps/$appId/events") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
            }.status
        }

        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun `DELETE for a user with no events gives 200 with a deleted count of 0`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")

        val response = runErase(dataDir, appId, "no-such-user")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.deletedField())
    }

    @Test
    fun `DELETE matches a user id with special characters, url-encoded`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")
        val userId = "user+plus&amp <tag> 'quote'"
        insertEvent(dataDir, appId, "e1", sessionId = "s1", userId = userId)

        val response = runErase(dataDir, appId, userId)

        assertEquals(1, response.deletedField())
        assertEquals(0, countEvents(dataDir, appId))
    }

    // Acceptance criteria of #61: the user id is a bound parameter, and it
    // appears in no line of the captured log. The captured log holds no
    // query string either.
    @Test
    fun `no log line holds the user id or the query string of the request`() {
        val dataDir = prepareStore()
        val appId = insertApp(dataDir, "demo")
        val marker = "secret-user-id-f3c9a1"
        insertEvent(dataDir, appId, "e1", sessionId = "s1", userId = marker)

        lateinit var status: HttpStatusCode
        var events = emptyList<ch.qos.logback.classic.spi.ILoggingEvent>()
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            val (response, capturedEvents) = captureLogEvents {
                client.delete("/api/apps/$appId/events?userId=${urlEncode(marker)}") {
                    allowedHost()
                    header(HttpHeaders.Origin, "http://localhost:7431")
                }
            }
            status = response.status
            events = capturedEvents
        }

        assertEquals(HttpStatusCode.OK, status)
        for (event in events) {
            assertFalse(event.formattedMessage.contains(marker), "a log line held the user id: ${event.formattedMessage}")
            assertFalse(
                event.formattedMessage.contains("userId="),
                "a log line held the query string: ${event.formattedMessage}",
            )
        }
    }

    @Test
    fun `a byte search of the store files finds no user id after the erasure`() {
        val dataDir = prepareStore()
        val marker = "byte-search-user-id-9b21"
        val appId = insertApp(dataDir, "demo")
        insertEvent(dataDir, appId, "e1", sessionId = "s1", userId = marker)

        val response = runErase(dataDir, appId, marker)
        assertEquals(HttpStatusCode.OK, response.status)

        val needle = marker.toByteArray(Charsets.UTF_8)
        val storeFiles = File(dataDir).listFiles { file -> file.name.startsWith("octometer.db") }
        assertTrue(!storeFiles.isNullOrEmpty(), "expected the store files in $dataDir")
        for (file in storeFiles) {
            assertEquals(-1, indexOfBytes(file.readBytes(), needle), file.name)
        }
    }

    private fun runErase(dataDir: String, appId: Long, userId: String): CapturedResponse {
        var status: HttpStatusCode = HttpStatusCode.InternalServerError
        var body = ""
        testApplication {
            application { module(prodConfig(dataDir = dataDir)) }
            val response = client.delete("/api/apps/$appId/events?userId=${urlEncode(userId)}") {
                allowedHost()
                header(HttpHeaders.Origin, "http://localhost:7431")
            }
            status = response.status
            body = response.bodyAsText()
        }
        return CapturedResponse(status, body)
    }
}

private data class CapturedResponse(val status: HttpStatusCode, val body: String) {
    fun deletedField(): Int = Json.parseToJsonElement(body).jsonObject["deleted"]!!.jsonPrimitive.content.toInt()
}

/** Opens the store one time, so the schema exists, then closes it. */
private fun prepareStore(): String {
    val dataDir = testDataDir()
    SqliteDatabase.open(dataDir).close()
    return dataDir
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun insertApp(dataDir: String, name: String): Long {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement(
            "INSERT INTO app (name, database_name, collection_name, created_at) VALUES (?, ?, ?, ?)",
        ).use { insert ->
            insert.setString(1, name)
            insert.setString(2, "db")
            insert.setString(3, "octometer_events")
            insert.setLong(4, 1_700_000_000_000L)
            insert.executeUpdate()
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                result.next()
                return result.getLong(1)
            }
        }
    }
}

private fun insertEvent(dataDir: String, appId: Long, eventId: String, sessionId: String, userId: String?) {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement(
            "INSERT INTO event (app_id, event_id, ts, element, session_id, user_id) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.setLong(1, appId)
            insert.setString(2, eventId)
            insert.setLong(3, 1_700_000_000_000L)
            insert.setString(4, "checkout.save")
            insert.setString(5, sessionId)
            insert.setString(6, userId)
            insert.executeUpdate()
        }
    }
}

private fun countEvents(dataDir: String, appId: Long): Int {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM event WHERE app_id = ?").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                result.next()
                return result.getInt(1)
            }
        }
    }
}

private fun remainingUserIds(dataDir: String, appId: Long): List<String> {
    val url = "jdbc:sqlite:" + File(dataDir, "octometer.db").absolutePath
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement("SELECT user_id FROM event WHERE app_id = ? ORDER BY user_id").use { select ->
            select.setLong(1, appId)
            select.executeQuery().use { result ->
                val ids = mutableListOf<String>()
                while (result.next()) ids += result.getString(1)
                return ids
            }
        }
    }
}

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return -1
    outer@ for (start in 0..haystack.size - needle.size) {
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}
