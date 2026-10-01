package octometer.monitor.accounts

import kotlinx.coroutines.runBlocking
import octometer.monitor.config.InvalidConfigException
import octometer.monitor.config.loadConfig
import octometer.monitor.store.SqliteDatabase
import kotlin.system.exitProcess

private sealed class InviteCommand {
    abstract val email: String

    data class Invite(override val email: String) : InviteCommand()
    data class Uninvite(override val email: String) : InviteCommand()
}

/** The launcher argument of `--invite <address>` or `--uninvite <address>` (D2, issue #301). */
private fun parseInviteCommand(args: Array<String>): InviteCommand? {
    val inviteIndex = args.indexOf("--invite")
    if (inviteIndex >= 0 && inviteIndex + 1 < args.size) {
        return InviteCommand.Invite(args[inviteIndex + 1])
    }
    val uninviteIndex = args.indexOf("--uninvite")
    if (uninviteIndex >= 0 && uninviteIndex + 1 < args.size) {
        return InviteCommand.Uninvite(args[uninviteIndex + 1])
    }
    return null
}

/**
 * Runs `--invite` or `--uninvite` when [args] carries one, and ends the
 * process; returns `false` with no other effect when neither argument
 * is present, so `main()` goes on to the normal server start.
 *
 * Brief review M7: this opens [SqliteDatabase] only. It never opens
 * `MonitorServices` (the secret sweep, the jobs, the poll scheduler),
 * and it never touches the secret store. It takes no instance lock:
 * the running service holds it, and SQLite WAL admits this one insert
 * or delete from a second, short-lived connection.
 */
fun runInviteCommandIfPresent(args: Array<String>): Boolean {
    val command = parseInviteCommand(args) ?: return false

    val resolved = try {
        loadConfig(args = args)
    } catch (invalidConfig: InvalidConfigException) {
        System.err.println("The config is invalid. ${invalidConfig.message}")
        exitProcess(2)
    }
    val email = normalizeEmail(command.email)
    if (email.isEmpty()) {
        System.err.println("Give an email address after --invite or --uninvite.")
        exitProcess(2)
    }

    val database = SqliteDatabase.open(resolved.config.dataDir, backupDir = resolved.config.backupDir)
    try {
        when (command) {
            is InviteCommand.Invite -> {
                runBlocking { database.write { writer -> insertInvite(writer, email, System.currentTimeMillis()) } }
                println("invited $email")
            }
            is InviteCommand.Uninvite -> {
                runBlocking { database.write { writer -> deleteInvite(writer, email) } }
                println("uninvited $email")
            }
        }
    } finally {
        database.close()
    }
    exitProcess(0)
}
