# The run guide

This guide tells the owner how to install, run, and maintain the monitor. The
guide names no real app and no real organisation. A value inside angle
brackets is a placeholder; the owner fills it with a real value.

This guide covers five sections in full. Section 3 gives a placeholder for
the upgrade step; issue #56 gives the upgrade script and the full text of
that step.

## 1. Install and start

The monitor lives under one folder, `%LOCALAPPDATA%\Octometer\`. Keep this
folder outside OneDrive or a different synced folder. A synced copy of the
SQLite write-ahead log file is not safe.

The target layout of design decision D34 holds these folders and files:

- `app\<version>\`: the installed code of one version.
- `data\`: the SQLite store.
- `secrets\`: the connection string of each app.
- `logs\`: the rolling log files.
- `backups\`: the daily backup files.
- `octometer.conf`: the user config file.
- `octometer.cmd`: the launcher of the installed version.

The monitor creates `data\`, `secrets\`, `logs\`, and `backups\` at the first
start. The owner creates `app\<version>\` by hand today.

`octometer.cmd` and the scheduled task come with issue #54. Until #54 lands,
follow these steps.

1. Open the release page:
   `https://github.com/xamcross/octometer/releases`.
2. Download `octometer-monitor-<version>.zip` (the example version below is
   `0.2.0`, so the file is `octometer-monitor-0.2.0.zip`).
3. Extract the zip. It holds one folder, `backend-<version>`.
4. Create the folder `app\<version>\` under `%LOCALAPPDATA%\Octometer\`. Move
   each file of `backend-<version>` into it.
5. Open a console in `app\<version>\`. Run `bin\backend.bat`.

The monitor starts in the prod mode by default. Stop the monitor with
Ctrl+C. The scheduled task of design decision D35 (`scripts/install.ps1`)
is not on `main` today. That script and the task come with issue #54; this
guide names no command of that script until it lands.

Confirm the start with a health check:

```powershell
curl.exe http://127.0.0.1:7431/api/health
```

Open the UI at `http://127.0.0.1:7431/`.

## 2. The config

Design decision D2 sets the config keys and their order. The order of
precedence, high to low: the command argument, the environment variable, the
user file `%LOCALAPPDATA%\Octometer\octometer.conf`, the bundled file
`octometer-defaults.conf`.

| Key | Environment variable | Default |
| --- | --- | --- |
| `mode` | `OCTOMETER_MODE` | `prod` |
| `port` | `OCTOMETER_PORT` | `7431` |
| `dataDir` | `OCTOMETER_DATA_DIR` | `%LOCALAPPDATA%\Octometer\data` in prod, `build/dev-data` in dev |
| `settleLagSeconds` | `OCTOMETER_SETTLE_LAG_SECONDS` | `60` in prod, `2` in dev |
| `retentionDays` | `OCTOMETER_STORE_RETENTION_DAYS` | `395` |
| `backupDir` | `OCTOMETER_BACKUP_DIR` | the sibling folder `backups` of `dataDir` |
| `pollIntervalSeconds` | `OCTOMETER_POLL_INTERVAL_SECONDS` | `60` in prod, `5` in dev |
| `pathValueCap` | `OCTOMETER_PATH_VALUE_CAP` | `200` |

`monitor/backend/README.md`, section "Config", holds the same eight rows and
the meaning of each key. Read that section for the detail this guide does
not repeat.

Write the user file with one `octometer { }` block. Give each Windows path
with a forward slash:

```
octometer {
  mode = "prod"
  port = 7431
  dataDir = "D:/octometer-data"
}
```

An invalid value stops the start with exit code 2. The start log prints
each value and its source, never a secret.

## 3. Backup, restore, and upgrade

### Backup

The monitor writes one backup file each day, with `VACUUM INTO`, into
`backupDir`. The file name is `octometer-<yyyyMMdd>.db`. The job keeps the
newest 7 files. Before a migration, the monitor writes one extra file,
`pre-migrate-v<n>-<timestamp>.db`, and it keeps one such file for each
migration it ever applied.

A backup file holds no connection string. Each connection string sits in
`secrets\apps.json`, outside `dataDir` (design decision D11).

### Restore

Follow each step in order, on a clean machine or on the same machine.

1. Stop the monitor.
2. Move `data\octometer.db`, `data\octometer.db-wal`, and
   `data\octometer.db-shm` out of `data\`.
3. Copy the backup file `backups\octometer-<yyyyMMdd>.db` to
   `data\octometer.db`.
4. Start the monitor.

The start stops with one log line when a `-wal` file of the old database
still sits beside the restored file. Move each old `-wal` and `-shm` file
out of `data\` before the start; do not leave one behind.

After the start, read `GET /api/health`. Then compare the event count of
the level 1 view with the date of the restored backup file.

`secrets\apps.json` sits outside `dataDir`, so a restore does not touch it.
Each app id of the restored file keeps its connection string. The start
removes each secret whose app id the restored file does not hold. Register
each app that the restore drops, on `/manage`, with its connection string.
Confirm each connection string on `/manage` after a restore of an older
backup file.

### Upgrade

Issue #56 gives the upgrade script `scripts/upgrade.ps1 -Version X.Y.Z`
(design decision D36). The script is not on `main` today; this guide names
no step of that script until #56 lands.

Follow one rule today and after #56 lands: upgrade the monitor before an
app (design decision D26). Read the release notes of the new monitor
version first.

## 4. The secrets

The monitor stores each connection string in `secrets\apps.json`, outside
`dataDir`. The application applies no encryption to this file (design
decision D11).

Check that BitLocker protects the drive:

```powershell
manage-bde -status C:
```

### Rotate a reader password

Rotate the reader password after a lost laptop, after a suspected leak, and
each 12 months. Delete the user, then create it again, with the Atlas CLI
commands of section 3 of `docs/integration-ktor.md`.

A password never passes through an agent or a chat. The owner runs each
Atlas CLI command by hand.

## 5. The erasure

Design decision D15 sets the order of an erasure. Run the three steps in
order.

1. The app erases its own copy of the user's events.
2. Wait for one full poll cycle of the monitor.
3. Call the erasure route of the monitor.

A call to the monitor before step 2 finishes lets a poll cycle read the
erased events again.

### The erasure command

The user id must not sit in the URL of a command line; the shell history
keeps the command line. Give the id as a shell variable first.

```powershell
$id = "<the id>"
curl.exe -X DELETE -H "Origin: http://127.0.0.1:7431" "http://127.0.0.1:7431/api/apps/<appId>/events?userId=$id"
```

Keep the `Origin` header. The route answers 403 with no `Origin` header. A
`DELETE` with no body needs no `Content-Type` header.

PowerShell also saves the assignment line, `$id = "<the id>"`, in
`ConsoleHost_history.txt`. The shell variable alone does not hide the id.
Clear that history file after the command, or read the id from a file
instead of a literal string.

### The answer

The answer holds `deleted` (a row count) and `checkpointed`:

- `checkpointed: true`: the erased bytes left `octometer.db`.
- `checkpointed: false`: the rows are gone, but an old page can still hold
  the erased bytes. Call the route again with the same user id. Delete no
  `octometer.db*` file by hand.

The route also rewrites each event `path` that holds the erased id as a
whole segment, to `/other` (issue #205).

## 6. The risks

Open the monitor UI in a browser profile with no extension. A hostile
extension can read the page past the Content Security Policy of design
decision D12. The monitor binds to the loopback address only, so a
different machine cannot reach the UI.

### The sleep test

Run this manual test once, after each install.

1. Close the lid of the monitor machine.
2. Wait past one poll interval.
3. Open the lid.

Read the level 1 view. It shows the resume line of issue #29, or the gap
line of issue #62 when the machine stayed off for a long time.

### The residual risks

The owner accepts each risk below (design section 8):

- A forged click.
- A hostile browser extension on the monitor machine.
- A leaked reader password. M0 keeps no access history, so an Atlas alert
  is the only signal.
- A reader role with a write action the monitor cannot see, when the
  cluster reports no privilege list.
- A source that writes raw paths keeps a maximum of `pathValueCap` distinct
  values in the monitor, until the owner reads the WARN line (issue #205).
  An app with fewer distinct raw values than `pathValueCap` gives no WARN
  line, and the set never ages out on its own. A pattern-shaped raw path
  passes the shape check. The first `pathValueCap` raw values stay in
  `event.path` until the retention purge or an erasure of their user id.

<!--
Sources:
monitor/backend/README.md ("Config", "The daily backup", "How to restore a
  backup", "The log files", "A corrupt, a locked, or a full store", "The
  user erasure route")
docs/demo.md (section 4b, the distribution start)
docs/integration-ktor.md, section 3 (the database user and the password
  rules)
scripts/bootstrap.ps1
CHANGELOG.md (0.2.0 and 0.1.0 entries, issues #29, #38, #62)
monitor/backend/src/main/kotlin/octometer/monitor/config/ConfigLoader.kt
  (ENV_KEYS, about lines 14-23)
monitor/backend/src/main/resources/octometer-defaults.conf
monitor/backend/src/main/kotlin/octometer/monitor/backup/BackupPaths.kt
  (about lines 33-34)
monitor/backend/src/main/kotlin/octometer/monitor/security/BindAddress.kt
  and RequestGuard.kt (D12, the loopback bind and the Content Security
  Policy)
monitor/backend/build.gradle.kts (about lines 343-346: the zip file name
  octometer-monitor-<version>.zip and the folder name backend-<version>
  inside it)
.github/workflows/release.yml (about lines 145-161: the built zip path)
docs/superpowers/specs/2026-09-21-octometer-design.md, decisions D2, D3,
  D11, D15, D26, D34, D35, D36, D37, sections 4.4 and 8
Issue #64 comments of 2026-09-22 (the shell-variable form of the erasure
  command) and the brief correction round of 2026-09-27 (the ConfigLoader
  key count, the daily backup file name, the D34 layout today, the
  ConsoleHost_history.txt note, the checkpointed:false action).
The upgrade step of section 3 is a placeholder. Issue #56 gives
  `scripts/upgrade.ps1` and the full text of that step.
-->
