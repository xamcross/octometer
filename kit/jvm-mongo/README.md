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

## The event cap (issue #34, design decision D21)

`MongoEventLogStore` drops a batch above `OCTOMETER_MAX_EVENTS`
(default 200000). The store reads `estimatedDocumentCount()` a maximum
of one time each 60 seconds. It drops each batch that the cache finds
at or above the cap. A dropped batch throws no exception. The ingest
route still answers 204 (contract rule C19). This protects an Atlas M0
cluster: above the cap, the store stops the ingest. The app then never
loses a write of its own to a full cluster.

**The database user needs the `find` action.** The minimum role of
the app database user is a custom role on `octometer_events` with the
actions `insert`, `createIndex`, `collMod`, and `find`. The action
`find` lets the store run `estimatedDocumentCount()` for the event
cap. Without `find` the cap never stops the ingest. The store then
fails closed after three count errors in a row. It drops each batch
until a count succeeds. It writes one warning each hour, with the
MongoDB error code. The built-in role `readWrite` holds `find` and
`insert`. It holds no `collMod`.

A value of `OCTOMETER_MAX_EVENTS` of zero, a negative value, or a
value with a character other than an ASCII digit, stops the app start
with a clear error. A value above 1000000 is clamped at start, with a
warning.

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
the same session, so no anonymous event ever loses its link to the
user. A failed user delete of a pass leaves the user events of that
pass in place; a caller runs the method again, and the retry finds
their session ids and finishes the erasure.

A session id list of more than 1 000 entries goes to one command in
batches of 1 000, so one command never nears the 16 MB command limit
of the server.

Contract rule C8 gives the collection only two indexes: one on `_id`,
and the TTL index on `ts`. This module adds no new index for the
erasure, so each command is a full collection scan. The event cap of
design decision D21 (200 000 documents, see above) bounds this cost,
because the erasure is a rare, owner-triggered action.

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
