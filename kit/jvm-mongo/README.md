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
quarter. Dependabot never raises it, because the `ignore` rule of
`.github/dependabot.yml` covers each version of
`org.mongodb:mongodb-driver-sync`, and Dependabot cannot separate the two
catalog entries by name alone. The compile version 5.0.1 stays fixed
each time.

## The user erasure (issue #35)

`MongoEventLogStore.deleteByUserId` deletes each event of one user id,
and each anonymous event (`userId: null`) of a session of that user
(contract rule C43). An event of a second user id in the same session
stays.

The call runs three MongoDB commands: it reads the distinct session ids
of the user, then it runs two `deleteMany` calls. These three commands
are not one transaction. A write for this user id, between the first
command and the last one, can leave an event behind; the Javadoc of
`EventLogStore.deleteByUserId` states the exact risk. Call the method
again to catch that case.

Contract rule C8 gives the collection only two indexes: one on `_id`,
and the TTL index on `ts`. This module adds no new index for the
erasure, so each of the three commands is a full collection scan. The
event cap of design decision D21 (200 000 documents) bounds this cost,
because the erasure is a rare, owner-triggered action.

### The erasure order for an app team (design decision D15)

Run the three steps in this order:

1. Call `deleteByUserId` in the app, through this store.
2. Wait for one full poll cycle of the monitor (the refresh time of its
   mode).
3. Call the erasure route of the monitor: `DELETE
   /api/apps/{appId}/events?userId=<id>` (issue #61,
   `monitor/backend/README.md`).

A call to the monitor route before step 2 finishes lets a poll cycle
read the erased events again from this store.
