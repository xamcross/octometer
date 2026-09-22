# kit/jvm-mongo

The MongoDB event store of the kit (design decision D22). The MongoDB
driver is a `compileOnly` dependency: the app gives the `MongoDatabase`,
and the kit never creates a client.

## The two driver versions

`gradle/libs.versions.toml` holds two versions of
`org.mongodb:mongodb-driver-sync`:

- `mongodb-driver-sync` (5.0.1). The compile version of this module. It
  is the version of the Spring Boot 3.3 BOM (design decision D22), and
  it stays at 5.0.1.
- `mongodb-sync-newest` (5.12.0 on 2026-09-21). A second test task runs
  the test suite again against this version.

Raise `mongodb-sync-newest` in `gradle/libs.versions.toml` by hand each
quarter. The `[libraries]` table holds one entry for
`org.mongodb:mongodb-driver-sync`, and that entry carries the compile
version. Dependabot reads that entry, and the `ignore` rule of
`.github/dependabot.yml` stops its update. Dependabot reads no
dependency for `mongodb-sync-newest`, because the `[libraries]` table
holds no reference to that version. The `ignore` rule covers each
version of `org.mongodb:mongodb-driver-sync`, so it also guards a later
`[libraries]` entry. The compile version 5.0.1 stays fixed each time.

## The user erasure (issue #35)

`MongoEventLogStore.deleteByUserId` deletes each event of one user id,
and each anonymous event (`userId: null`) of a session of that user
(contract rule C43). An event of a second user id in the same session
stays.

The call reads the session ids of the user, then it deletes the
anonymous events of those sessions, then it deletes the events of the
user in those sessions. It reads the session ids again. It repeats the
delete pair while a new session id appears, up to 3 passes. A pass
never deletes a user event before it deletes the anonymous events of
the same session. A failure of the second delete of a pass leaves the
anonymous events reachable; a caller runs the method again to erase
them.

Contract rule C8 gives the collection only two indexes: one on `_id`,
and the TTL index on `ts`. This module adds no new index for the
erasure, so each command is a full collection scan. The event cap of
design decision D21 (200 000 documents) bounds this cost, because the
erasure is a rare, owner-triggered action. Issue #34 adds this cap to
the store. The kit does not enforce it yet.

### The erasure order for an app team (design decision D15)

Run the three steps in this order:

1. Call `deleteByUserId` in the app, through this store.
2. Wait for one full poll cycle of the monitor (the refresh time of the
   mode).
3. Call the erasure route of the monitor: `DELETE
   /api/apps/{appId}/events?userId=<id>` (issue #61,
   `monitor/backend/README.md`).

A call to the monitor route before step 2 finishes lets a poll cycle
read the erased events again from this store.

### What this call does not erase

This call erases the collection `octometer_events` only. Two copies
stay outside it:

- The oplog of the replica set. The insert entry of each event holds
  the whole document, with the user id and the session id. The delete
  entry holds the `_id` only. An entry leaves the oplog when the oplog
  window passes.
- A backup snapshot, and a point-in-time restore, of Atlas. A restore
  brings the erased events back. Call this method again after a
  restore.

The kit reaches neither one. The owner of the app and the owner of the
Atlas cluster control them.
