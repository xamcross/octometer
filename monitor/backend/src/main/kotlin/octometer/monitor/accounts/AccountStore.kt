package octometer.monitor.accounts

import java.sql.Connection

/** The maximum length of an email address (step 2 of issue #301). */
internal const val EMAIL_MAX_LENGTH = 254

/** Lowers and trims an email address, the one normal form of the store (issue #301, D52). */
fun normalizeEmail(email: String): String = email.trim().lowercase()

/**
 * The form check of `POST /api/signin` (issue #301, D52): one `@`
 * character, no space, at most [EMAIL_MAX_LENGTH] characters. This is a
 * shape check only; it never confirms that the address exists.
 */
fun isWellFormedEmail(email: String): Boolean {
    if (email.isEmpty() || email.length > EMAIL_MAX_LENGTH) return false
    if (email.any { it.isWhitespace() }) return false
    return email.count { it == '@' } == 1 && !email.startsWith("@") && !email.endsWith("@")
}

internal fun findAccountIdByEmail(reader: Connection, email: String): Long? =
    reader.prepareStatement("SELECT id FROM account WHERE email = ?").use { select ->
        select.setString(1, email)
        select.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
    }

internal fun insertAccount(writer: Connection, email: String, now: Long): Long {
    writer.prepareStatement("INSERT INTO account (email, created_at) VALUES (?, ?)").use { insert ->
        insert.setString(1, email)
        insert.setLong(2, now)
        insert.executeUpdate()
    }
    return writer.createStatement().use { statement ->
        statement.executeQuery("SELECT last_insert_rowid()").use { result ->
            check(result.next()) { "No id after the account insert." }
            result.getLong(1)
        }
    }
}

/** Finds the account of [email], or creates it when it is absent (`POST /api/signin/confirm`). */
internal fun findOrCreateAccountId(writer: Connection, email: String, now: Long): Long =
    findAccountIdByEmail(writer, email) ?: insertAccount(writer, email, now)

internal fun isInvited(reader: Connection, email: String): Boolean =
    reader.prepareStatement("SELECT 1 FROM invite WHERE email = ?").use { select ->
        select.setString(1, email)
        select.executeQuery().use { it.next() }
    }

internal fun insertInvite(writer: Connection, email: String, now: Long) {
    writer.prepareStatement("INSERT OR IGNORE INTO invite (email, created_at) VALUES (?, ?)").use { insert ->
        insert.setString(1, email)
        insert.setLong(2, now)
        insert.executeUpdate()
    }
}

internal fun deleteInvite(writer: Connection, email: String): Boolean =
    writer.prepareStatement("DELETE FROM invite WHERE email = ?").use { delete ->
        delete.setString(1, email)
        delete.executeUpdate() > 0
    }

internal fun insertSignInToken(writer: Connection, tokenHash: String, email: String, expiresAt: Long, now: Long) {
    writer.prepareStatement(
        "INSERT INTO signin_token (token_hash, email, expires_at, used_at, created_at) VALUES (?, ?, ?, NULL, ?)",
    ).use { insert ->
        insert.setString(1, tokenHash)
        insert.setString(2, email)
        insert.setLong(3, expiresAt)
        insert.setLong(4, now)
        insert.executeUpdate()
    }
}

/** One row of `signin_token`, read by its hash. */
internal data class SignInTokenRow(val email: String, val expiresAt: Long, val usedAt: Long?)

internal fun findSignInToken(reader: Connection, tokenHash: String): SignInTokenRow? =
    reader.prepareStatement("SELECT email, expires_at, used_at FROM signin_token WHERE token_hash = ?").use { select ->
        select.setString(1, tokenHash)
        select.executeQuery().use { result ->
            if (!result.next()) return@use null
            SignInTokenRow(
                email = result.getString("email"),
                expiresAt = result.getLong("expires_at"),
                usedAt = result.getLong("used_at").takeUnless { result.wasNull() },
            )
        }
    }

internal fun markSignInTokenUsed(writer: Connection, tokenHash: String, now: Long) {
    writer.prepareStatement("UPDATE signin_token SET used_at = ? WHERE token_hash = ?").use { update ->
        update.setLong(1, now)
        update.setString(2, tokenHash)
        update.executeUpdate()
    }
}

internal fun insertSession(writer: Connection, sessionHash: String, accountId: Long, expiresAt: Long, now: Long) {
    writer.prepareStatement(
        "INSERT INTO session (session_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
    ).use { insert ->
        insert.setString(1, sessionHash)
        insert.setLong(2, accountId)
        insert.setLong(3, expiresAt)
        insert.setLong(4, now)
        insert.executeUpdate()
    }
}

/** Finds the account id of a session hash, only when it is not expired. */
internal fun findValidSessionAccountId(reader: Connection, sessionHash: String, now: Long): Long? =
    reader.prepareStatement("SELECT account_id, expires_at FROM session WHERE session_hash = ?").use { select ->
        select.setString(1, sessionHash)
        select.executeQuery().use { result ->
            if (!result.next()) return@use null
            val expiresAt = result.getLong("expires_at")
            if (expiresAt < now) null else result.getLong("account_id")
        }
    }

internal fun deleteSession(writer: Connection, sessionHash: String): Boolean =
    writer.prepareStatement("DELETE FROM session WHERE session_hash = ?").use { delete ->
        delete.setString(1, sessionHash)
        delete.executeUpdate() > 0
    }

internal fun findAccountEmail(reader: Connection, accountId: Long): String? =
    reader.prepareStatement("SELECT email FROM account WHERE id = ?").use { select ->
        select.setLong(1, accountId)
        select.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
    }
