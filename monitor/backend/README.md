# Monitor backend

The Ktor server of the monitor. See `docs/superpowers/specs/2026-09-21-octometer-design.md`
for the full design, and `contract/README.md` for the event log contract.

## The user erasure route (issue #61)

`DELETE /api/apps/{appId}/events?userId=<id>` deletes each event of one user id of one app in
the monitor store. It also deletes each event with `user_id IS NULL` of a session that holds
an event of that user (contract rule C43). A row of a second user in the same session stays.
The answer holds `deleted` (the row count) and `checkpointed` (see below).

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
