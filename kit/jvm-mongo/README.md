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
