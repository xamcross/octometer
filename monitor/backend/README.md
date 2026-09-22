# The monitor backend

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

## How to restore a backup

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
