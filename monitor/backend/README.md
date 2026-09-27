# Monitor backend

The Ktor server of the monitor. See `docs/superpowers/specs/2026-09-21-octometer-design.md`
for the full design, and `contract/README.md` for the event log contract.

## Config (design decision D2)

Each key has an order of precedence: the argument `-P:octometer.<key>=`, the environment
variable, the user file, the bundled default. See `octometer.monitor.config.loadConfig`.

| Key | Environment variable | Bundled default |
| --- | --- | --- |
| `mode` | `OCTOMETER_MODE` | `prod` |
| `port` | `OCTOMETER_PORT` | `7431` |
| `dataDir` | `OCTOMETER_DATA_DIR` | `%LOCALAPPDATA%\Octometer\data` in prod, `build/dev-data` in dev |
| `settleLagSeconds` | `OCTOMETER_SETTLE_LAG_SECONDS` | `60` in prod, `2` in dev |
| `retentionDays` | `OCTOMETER_STORE_RETENTION_DAYS` | `395` |
| `backupDir` | `OCTOMETER_BACKUP_DIR` | the sibling folder `backups` of `dataDir` |
| `pollIntervalSeconds` | `OCTOMETER_POLL_INTERVAL_SECONDS` | `60` in prod, `5` in dev |
| `pathValueCap` | `OCTOMETER_PATH_VALUE_CAP` | `200` |

`pollIntervalSeconds` sets the gap between two poll cycles of one app (issue #17, design
decision D6). The poll scheduler adds this gap to the clock time after each cycle, on a
success and on a failure alike.

`pollIntervalSeconds` takes a whole number of 1 or more. A tick of the loop is 1 second
(the floor), so a value of 1 polls the app at every tick.

## The poll cycle status (design decision D8)

Each app row holds one status after a poll cycle:

| Status | Meaning |
| --- | --- |
| `OK` | The cycle read every document, and each one was valid. |
| `INVALID_DATA` | The cycle skipped one document or more; see `skipped_event`. |
| `ERROR` | The cycle failed with an error that no other status here names. |
| `UNAUTHORIZED` | The cycle failed with a wrong user name or a wrong password. |
| `UNREACHABLE` | Two failed cycles in a row could not reach the MongoDB server. |

### The reader rule for a value outside the contract (issue #196, 2026-09-26)

A `referrerHost` value outside the closed set of contract rule C40 (`google.com`, `bing.com`,
`other`) lands as `NULL` in `referrer_host`. The row stays: the reader writes no
`skipped_event` row for it, because the event itself is valid. One WARN line of the cycle
counts the dropped fields, a count only, never a value. The three set values land as they
are, and the check is case-sensitive.

### The per-app cap on the distinct path values (issue #205, 2026-09-27)

The reader keeps the set of distinct `path` values of each app, in the table `app_path`, with
a cap (`pathValueCap`, default 200). A `path` value of a start or a click event is accepted as
it is when it passes the shape check of contract rule C39, and it is in the set, or the set
holds fewer than `pathValueCap` values (the value then joins the set). Each other value lands
as the literal `/other` (the C42 fallback). The row stays. One WARN line of the cycle counts
the replaced values, a count only, never a value. A `NULL` path stays `NULL`.

A PATCH to a different connection string clears the set of the app. The retention purge
removes each value with no matching event row of its own app any more. The erasure route also
rewrites each event `path`, and deletes each `app_path` value, that holds the erased user id
as a whole segment.

A source that writes raw client paths keeps a maximum of `pathValueCap` distinct values until
the owner reads the WARN line. An app with fewer distinct raw values than `pathValueCap`
produces no WARN line, and the set never ages out on its own. The allow-list of a per-app
pattern list is the stronger control; issue #226 holds it.

## The release zip (issue #38)

Plain `assemble` and `build` skip the distribution zip, so the JVM job of CI stays free of
Node. Run `./gradlew :monitor:backend:distZip` to build the zip, with the Angular app of
`monitor/frontend` inside it.

## The daily backup (issue #55)

The monitor writes one backup file each day, and one backup file before a
migration. `MonitorServices` starts the daily job at the start, and stops
it at the stop.

- The daily file: `<backupDir>/octometer-<yyyyMMdd>.db`. The job keeps
  the newest 7 files, and it never writes over an existing file; a
  second run of the same day skips the write, with one log line.
- The pre-migration file: `<backupDir>/pre-migrate-v<n>-<timestamp>.db`.
  The runner writes this file before it applies a migration, and it
  stops the start when the write fails. The prune of the daily files
  never removes a pre-migration file; each install keeps one file for
  each migration that it ever applied, a small and bounded count.
- `backupDir` defaults to the folder `backups`, a sibling of `dataDir`.
  A config file, an argument, or the environment variable
  `OCTOMETER_BACKUP_DIR` can set a different folder.

### How to restore a backup

A backup file is a valid SQLite database on its own. Follow each step in
order.

1. Stop the monitor. End the scheduled task `Octometer`, and wait for
   the process to end.
2. Copy `backups\octometer-<yyyyMMdd>.db` over `data\octometer.db`.
3. Delete `data\octometer.db-wal` and `data\octometer.db-shm`. **This
   step is not optional.** A file left over from the old database
   replays its own stale writes over the restored file, and the owner
   gets the old data back with no warning.
4. Start the monitor.
5. Read `GET /api/health`, then compare the event count with the count
   of the backup file.

`SqliteDatabase.open` refuses the start when it finds `octometer.db` in
rollback-journal mode (the state of a backup file) together with a
`octometer.db-wal` file that still holds content. That state means a
person skipped step 3. The start stops with one log line, and the code
never deletes the two side files by itself; only a person, following the
steps above, does that.

## The log files (issue #57)

The monitor writes each log line to the console, and to a rolling file. Each file appender
uses one text pattern, with no query string, no header, and no connection string.

- The file: `<logs>/octometer.log`. The folder `logs` is a sibling of `dataDir` (design
  decisions D3 and D34). `configureLogDir` in `Application.kt` sets the folder after the
  config load. It then loads `logback-file.xml`, a second Logback file that adds the
  rolling file appender. `logback.xml` itself never loads a file appender.
- Each file rolls over at 10 MB, or at midnight, whichever comes first.
- The monitor keeps 14 days of files, up to 200 MB in total. A file beyond either bound is
  gone.
- The dev mode (`dataDir = build/dev-data`) writes to `build/logs`.

## A corrupt, a locked, or a full store (issue #58, design decisions D3, D4, D8)

A corrupt `octometer.db` fails `PRAGMA quick_check` at the start (or gives `SQLITE_NOTADB` or
`SQLITE_CORRUPT` while the store opens). The start writes one ERROR line, the file stays as it
is, and the process exits with the code 2. A second monitor instance, on the same data folder,
fails the file lock `octometer.lock` and exits with the same code. A write that waits longer
than `busy_timeout` (`SQLITE_BUSY`), and a full disk (`SQLITE_FULL`), each give the app status
`ERROR` with a fixed `last_error` text, and the store keeps the row of the poll cycle that came
before the failed one.

## The user erasure route (issue #61)

`DELETE /api/apps/{appId}/events?userId=<id>` deletes each event of one user id of one app in
the monitor store. It also deletes each event with `user_id IS NULL` of a session that holds
an event of that user (contract rule C43). A row of a second user in the same session stays.
The answer holds `deleted` (the row count) and `checkpointed` (see below).

The route also rewrites each event `path` of the app that holds the erased id as a whole
segment to `/other`, and it deletes each `app_path` value with that same shape (issue #205,
design decision D5). Neither count joins `deleted`: the rewritten row keeps its own tie to its
own session, with no other tie to the erased user.

This route erases the monitor copy only. Each app also keeps its own copy of the events in its
MongoDB store, through the kit (`EventLogStore.append`). Issue #35 gives the kit the same
erasure width (rule E1).

### The erasure order (design decision D15)

Run the three steps in this order:

1. Run the erasure of #35 in the app.
2. Wait for one full poll cycle of the monitor (the refresh time of the mode).
3. Call this route: `DELETE /api/apps/{appId}/events?userId=<id>`.

A call before step 2 finishes lets a poll cycle read the erased events again from the app
store. The monitor row of this user then comes back, with no error from this route.

### The WAL checkpoint (BLOCKER 1 and MAJOR A, privacy and SQL review)

The delete writes new, zeroed pages into the WAL file. The erased bytes stay in the main file,
`octometer.db`, until a checkpoint copies the new pages over the old ones. The route runs
`PRAGMA wal_checkpoint(PASSIVE)` after the delete, with `busy_timeout=0` on the connection, so
the try never waits. `PASSIVE` copies each frame that no reader still needs, and it retries up
to five times, 100 ms apart. The route then runs one `TRUNCATE` checkpoint to reset the file
length; that result does not change the answer. The answer field `checkpointed` states the
`PASSIVE` result.

- `checkpointed: true`: the checkpoint moved every frame into the main file. The erased bytes
  left `octometer.db`.
- `checkpointed: false`: the checkpoint did not complete after five tries. The rows are still
  gone, but an old page with the erased bytes can stay in the store files until a later call
  completes the checkpoint. Never delete a file `octometer.db*` by hand. Call the route again
  with the same user id. A second call deletes 0 rows and retries the checkpoint alone.

### The user id in the URL (MAJOR 2, privacy review)

The user id travels in the query string of the URL. Each of these places can then hold it:

- The shell history of the terminal that sends the request.
- The log of a proxy that sits in front of the monitor.
- A future request log of the monitor itself.

The monitor writes no access log and no request log today (see #31 and #57). Do not add a log
that records a query string. Clear the shell history after a manual call of this route, and
never put this route behind a proxy that logs a request line.

### A migration that adds a user id to a new table

Only the table `event` holds a `user_id` column today. A migration that adds a user id to
another table must also extend `octometer.monitor.erasure.UserErasureService`, and add a test.
