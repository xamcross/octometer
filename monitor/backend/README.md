# Monitor backend

The Ktor server of the monitor. See `docs/superpowers/specs/2026-09-21-octometer-design.md`
for the full design, and `contract/README.md` for the event log contract.

## The user erasure route (issue #61)

`DELETE /api/apps/{appId}/events?userId=<id>` deletes each event of one user id of one app in
the monitor store. It also deletes each event with `user_id IS NULL` of a session that holds
an event of that user (contract rule C43). A row of a second user in the same session stays.

This route erases the monitor copy only. Each app also keeps its own copy of the events in its
MongoDB store, through the kit (`EventLogStore.append`). Issue #35 gives the kit the same
erasure width (rule E1). Run the erasure of #35 in the app first. Else a later poll cycle of
the monitor can read the erased events again from the app store, and the monitor row comes
back.
