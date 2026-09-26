# Octometer design

Status: version 3, after seven design reviews and four backlog reviews. Date: 2026-09-21.

Source: `App Description.md` in the root of the repository.

## 1. Purpose

Octometer is a monitor for the click metrics of the owner's web apps. The owner runs it on a
local Windows 11 machine. It reads the events of each app, stores them, and shows three table
levels. Version 1.1 adds the two views of decision D44.

| Level | One row is | Columns |
|---|---|---|
| 1 | An app | Total clicks, unique users, unique sessions |
| 2 | A user of the app | Clicks, sessions, unique elements |
| 3 | An element that a visitor touched | Clicks, sessions, last interaction timestamp |

A click on a level 1 row opens level 2. A click on a level 2 row opens level 3.

## 2. Inputs

### 2.1 Requirements from the app description

- R1. The web app is locally deployed.
- R2. The monitor polls each 5 seconds in dev mode and each 1 minute in prod mode.
- R3, R4, R5. A metrics endpoint in each app, a key check, and a key that the monitor
  generates. **Decision O4 replaces these three requirements.**
- R6. Each app stores the metrics in its own database as a flat event log. The log has no
  groups and no references.
- R7. One event is one click or tap. It holds a timestamp, an element name (id), a session id,
  a username, and an IP address. **Decision O5 changes the last two fields. Decision D41 adds
  one event that is not a click.**
- R8, R9, R10. The three table levels of section 1.

### 2.2 Owner decisions on 2026-09-21

- O1. The monitor stack is Ktor plus Angular.
- O2. The repository holds the monitor, the contract, and a reference kit for the apps. The
  integration of each app is an issue in the repository of that app.
- O3. The monitor copies the new events into a local embedded database and calculates the
  totals there.
- O4. The monitor reads the event collection of each app directly from MongoDB. Its database
  user is read-only and limited to that one collection. No metrics endpoint and no API key
  exist. Reason: an HTTP poll each minute keeps a Fly machine awake and costs money.
- O5. An event holds the internal user id of the app in place of a username. It holds no IP
  address.
- O6. The three levels are separate views with a breadcrumb (drill-down), not nested tables.
- O7. The owner registers an app on a management page in the UI.
- O8. A GitHub issue is succinct and has a maximum of 6 implementation steps.

### 2.3 Evidence from the owner's public app repositories, observed on 2026-09-21

- `traficio`: Ktor 3.2.0, Kotlin 2.2.0, `mongodb-driver-kotlin-coroutine` 5.5.0, JDK 21,
  Angular 20.
- `tuliplot`: Spring Boot 4.1, Spring Data MongoDB, Spring Security, JDK 25, Angular 22.
- `cadence`: Java, Spring Boot 3.3.5 with `io.spring.dependency-management` 1.1.6, JDK 21,
  Angular 17. The BOM pins `kotlin-stdlib` 1.9.25 and `mongodb-driver-sync` 5.0.1.
- `cadence` serves Angular from Cloudflare Pages. A Pages Function sends only `/api`,
  `/oauth2`, and `/login/oauth2/code` to the backend (`frontend/src/_redirects:9-13`). The CSP
  is `connect-src 'self'` (`frontend/src/_headers:5`). Spring Security enforces a cookie CSRF
  token.
- Each app uses a MongoDB Atlas M0 cluster: 512 MB, 500 connections, 100 operations each
  second, 10 GB out in 7 days, no audit, no access history. The access list is `0.0.0.0/0`.
- The owner machine has JDK 17, 21, and 23 in `~/.jdks`. `java` on the PATH is JDK 24.
  Docker Desktop, Node, `gh`, and `flyctl` are installed. `gitleaks` is absent.

### 2.4 Verified facts on 2026-09-21

- The Atlas CLI role format is `roleName[@dbName[.collection]]`. A custom role takes a
  privilege such as `FIND@<database>.<collection>`.
- Observed on 2026-09-21 on a real Atlas M0 cluster (issue #1): `connectionStatus` with
  `showPrivileges: true` returns the full privilege list. The user had the custom role of
  section 4.4, and the list held one resource, the one collection, and the one action `find`.
  A statement of 2022 (Compass PR 2959) said that M0 returns no privileges; it is out of date.
  `listCollections` with `authorizedCollections: true, nameOnly: true` returns an empty list
  when the collection does not exist. An authorization failure on Atlas has the code 8000
  (`AtlasError`) with the text "user is not allowed to do action [...]".
- An ObjectId is not monotonic across processes. The time part has a resolution of 1 second,
  and the clock of the client sets it.
- Measured on `mongo:8.0`: one event costs 185 bytes with its two indexes. 200 000 events use
  about 37 MB. The reviewer estimates that the field `path` of version 1.1 adds a maximum of
  about 160 bytes to each click event. No measurement of this addition exists yet. The
  estimate gives about 70 MB for 200 000 events. The cap of D21 stays inside the 512 MB of an
  M0 cluster.
- Measured on SQLite 3.50 with the indexes of section 6: each level needs less than 0.4 s with
  1 million events, and 2 to 9 s with 10 million events.

## 3. Components and repository layout

```
contract/            Event log contract v1.2: README.md, examples/*.json, fixtures/
monitor/backend/     Ktor server: registry, MongoDB reader, SQLite store, totals API
monitor/frontend/    Angular app: five table views, the app management page
kit/jvm-core/        Java 21, no run-time dependency: the ingest logic
kit/jvm-mongo/       Java 21: the MongoDB event store
kit/jvm-ktor/        Kotlin: the ingest route for a Ktor 3 app
kit/jvm-spring/      Java 21: the ingest controller for a Spring MVC app
kit/tracker/         TypeScript click tracker, no dependency
tools/demo-app/      Ktor demo app: kit + tracker + synthetic clicks
tools/consumer-smoke/ Minimal Spring Boot apps that use the kit
scripts/             install.ps1, upgrade.ps1, bootstrap script
docs/                Design, run guide, integration guides
```

The demo app has no login. A select box on its page sets the cookie `demo_user`, and the demo
`UserIdResolver` reads it.

One Gradle build is at the root, with one toolchain (JDK 21), the foojay toolchain resolver,
and the version catalog `gradle/libs.versions.toml`. Each npm project has its own
`package.json`.

## 4. Event log contract v1.2

### 4.1 The event document

Collection `octometer_events` in the database of the app.

| Field | BSON type | Rule |
|---|---|---|
| `_id` | ObjectId | The kit creates it inside the insert call. |
| `ts` | Date | The click time. The app server sets it. |
| `element` | String | 1 to 100 characters, pattern `[A-Za-z0-9_.:-]+`. |
| `sessionId` | String | A UUID. One session is the life of one browser tab. |
| `userId` | String or null | 1 to 254 characters. `null` means "not signed in". |
| `path` | String | Optional. The form and the limits are in contract rule C39. The stored value is the result of the route-pattern match, or the literal `/other` (contract rule C42; design decision D40). |
| `referrerHost` | String | Optional, only on `octo:session-start`. The value set is in contract rule C40 (design decision D42). |

- The app only inserts. It never updates an event.
- Indexes: `_id`, plus a TTL index on `ts` (default 30 days, `OCTOMETER_RETENTION_DAYS`).
- A reader ignores an unknown field. A new field is a minor change.
- A major change uses a new collection name: `octometer_events_v2`.
- An app that writes its own store must not add a `_class` field (Spring Data adds it).
- The `element` value `octo:session-start` marks the first event of a session. It is not a
  click (contract rule C38; design decision D41).

### 4.2 The ingest request (tracker to app backend)

`POST <ingestPath>`, default `/api/octometer/v1/clicks`, `Content-Type: application/json`.

```json
{"sessionId": "0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",
 "clicks": [{"element": "checkout.save", "ageMs": 1200}]}
```

A session start is its own request, with one entry only (design decision D41):

```json
{"sessionId": "0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11",
 "clicks": [{"element": "octo:session-start", "ageMs": 0, "path": "/", "referrerHost": "google.com"}]}
```

- The server calculates `ts = receivedAt - ageMs`. It clamps an `ageMs` above 600 000. A
  negative `ageMs` is invalid.
- The server takes `userId` from the authentication context. The client never sends it.
- The server ignores an unknown field in the body, a `userId` field included, and an unknown
  field inside an entry of `clicks` (contract rule C32).
- The server checks `element` and `sessionId` with the rules of 4.1. It drops an entry whose
  `element` starts with the reserved prefix `octo:`, except the exact text
  `octo:session-start` (contract rule C38; design decision D41).
- Limits: 50 clicks for each batch, a body of 16 KB.
- Responses: 204 for success and for a batch that the event cap drops, 400 for an invalid
  body (a broken limit and a broken field rule included), 415 for a content type other than
  JSON, 429 for the rate limit.
- A duplicate key makes the body invalid. `ageMs` is a JSON integer.

### 4.3 The reader rule (monitor)

```
bound  = ObjectId.getSmallestWithDate(serverTime - lag)
filter = { _id: { $gt: cursor, $lt: bound } }     sort { _id: 1 }   limit 1000   batchSize 1000
```

- Without a cursor, the reader starts at the oldest event.
- `serverTime` is `localTime` from the `hello` command of the primary.
- `lag` is 60 seconds in prod mode and 2 seconds in dev mode (config value).
- The read preference is `primary`, set in code. The monitor rejects `readPreference` in a URI.
- The delivery is "at least once". The primary key `(app_id, event_id)` drops a duplicate.
- Accepted loss: an insert that commits more than `lag` seconds after the creation of its
  `_id`. The contract README states it.
- A `$gt` on a deleted `_id` is a plain index seek. A TTL delete does not break the cursor.

### 4.4 The database user

```
atlas customDbRoles create octometerEventReader --privilege FIND@<database>.octometer_events --projectId <id>
atlas dbusers create --username octometer-reader --role octometerEventReader --projectId <id>
atlas dbusers describe octometer-reader --projectId <id> -o json
```

- The CLI prompts for the password, thus the password stays out of the shell history.
- Create the password with `openssl rand -hex 24`. Use one password for each Atlas project.
- The owner runs these commands. A password must not pass through an agent.
- Rotate after a laptop loss, after a suspected leak, and each 12 months: delete the user,
  then create it again.

## 5. Decisions

### Monitor backend

- **D1. Stack.** Ktor 3, Kotlin, JDK 21, Netty, kotlinx.serialization, the MongoDB Kotlin
  coroutine driver, coroutines.
- **D2. Config.** HOCON. Precedence, high to low: the argument `-P:octometer.<key>=`, the
  environment variable, `%LOCALAPPDATA%\Octometer\octometer.conf`, the bundled
  `application.conf`. Environment variables: `OCTOMETER_MODE`, `OCTOMETER_PORT`,
  `OCTOMETER_DATA_DIR`, `OCTOMETER_SETTLE_LAG_SECONDS`, `OCTOMETER_STORE_RETENTION_DAYS`. Keys:
  `mode` (default `prod`; the Gradle `run` task sets `dev`), `port` (default 7431),
  `dataDir` (`%LOCALAPPDATA%\Octometer\data` in prod, `build/dev-data` in dev),
  `settleLagSeconds`, `retentionDays`. An invalid value gives exit code 2. The start log
  prints each value and its source, never a secret. The user file uses the same
  `octometer { }` block as the bundled file, and a Windows path in it needs forward slashes
  or double backslashes. Without `LOCALAPPDATA`, `dataDir` defaults to
  `<user home>/.octometer/data` in prod mode.
- **D3. Store.** One SQLite file `octometer.db` in `dataDir`, driver `sqlite-jdbc`, plain JDBC.
  Migrations: numbered files `db/001_init.sql` plus `PRAGMA user_version`. No Flyway.
  Pragmas for each connection: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`,
  `foreign_keys=ON`, `secure_delete=ON`, `temp_store=MEMORY`, `cache_size=-65536`. One writer connection on a
  single-thread dispatcher. One separate connection for the reads. A lock file
  `octometer.lock` in `dataDir` with `FileChannel.tryLock()` makes a second monitor instance
  refuse to start. The folders `secrets`, `logs`, and `backups` are siblings of `dataDir`.
  `PRAGMA quick_check` runs at the start; a failed check gives exit code 2 and leaves the file
  as it is. `SQLITE_FULL` rolls back the page transaction, thus the cursor stays.
- **D4. Page transaction.** `BEGIN IMMEDIATE`, the insert of one page with
  `ON CONFLICT(app_id, event_id) DO NOTHING`, the cursor update, `COMMIT`. The poller requests
  the next page only after the commit. The poller updates only its own columns of `app`.
- **D5. Invalid document.** The reader skips it, writes `skipped_event(app_id, event_id,
  reason)`, and moves the cursor. Invalid means: an absent field, a wrong BSON type, or an
  empty `userId`. At the end of a good cycle the reader sets `last_poll_at`,
  `last_success_at`, and the status `OK`, or `INVALID_DATA` after a skip. The reader copies a
  `path` value as it is. A wrong BSON type is an invalid document under this decision.
- **D5, the reader rule for a value outside the contract (2026-09-26, issue #196, the
  owner's choice of option (c)).** A `referrerHost` value outside the closed set of contract
  rule C40 (`google.com`, `bing.com`, `other`) is not an invalid document. It lands as `NULL`
  in `referrer_host`, and the row stays: the event itself is valid, and a `skipped_event` row
  would drop the whole session from the counts of issues #112 and #113. The three set values
  land as they are. The check is case-sensitive, the same as contract rule C40. This is the
  form of contract rule C41: the reader drops the field, it keeps the row, and one WARN line
  of the cycle counts the dropped fields, a count only, with no fixed reason text and no
  `skipped_event` row. A `path` value stays a raw copy for the reasons that D5 already gives:
  a pattern form of contract rule C42 cannot be told apart from a raw path at the reader. This
  risk (RISK 1 of the security review of pull request #193) stays open by the owner's choice;
  issue #205 tracks it, outside this decision.
- **D6. Scheduler.** One loop with a tick of 1 second. It starts a poll for each app with
  `next_poll_at <= now` or `next_poll_at IS NULL`, and no active poll. One cycle reads a maximum of 10 pages inside
  `withTimeout(45 s)`. Success sets `next_poll_at = now + interval` (5 s dev, 60 s prod).
  Failure sets `now + min(interval * 2^failures, 300 s)` with a jitter of 10 percent. The
  loop reads the app list at each tick, thus a new app needs no restart.
- **D7. Wake rule.** When the wall clock is later than the planned time by more than two
  intervals, the loop writes "resume detected" and waits 15 seconds. The tests inject a
  `Clock` and use virtual time. No test uses a real `delay`.
- **D8. Status.** Stored: `OK`, `UNREACHABLE`, `UNAUTHORIZED`, `OVERPRIVILEGED`,
  `INVALID_DATA`, `ERROR`. Derived: `NEVER_POLLED` (`last_poll_at IS NULL`). Map: `MongoSecurityException`, command
  error code 13, or code 8000 with the text "not allowed to do action" gives `UNAUTHORIZED`.
  A timeout, a socket error, or a DNS error gives `UNREACHABLE`, shown after 2 failed cycles
  in sequence; the first failed cycle keeps the old status. Each other error gives `ERROR`.
- **D9. Privilege check.** At the first poll and each 24 hours. Check 1: `listCollections`
  with `authorizedCollections: true, nameOnly: true`. An empty list passes, because the
  collection does not exist before the first event of an app. A list that holds a name other
  than the configured collection fails. Check 2, the main check: `connectionStatus` with
  `showPrivileges: true`, compared with an allow-list (resource = the one collection, action =
  `find`). Section 2.4 shows that M0 returns this list. A failed check gives `OVERPRIVILEGED`
  and stops the poll of that app. When the privilege list is absent or empty, check 1 alone
  decides; a write action on the one collection is then not visible, and section 8 lists this
  risk. When the privilege list is absent or empty and check 1 returns an empty list, the
  monitor has no evidence, thus it gives `OVERPRIVILEGED`. While the status is
  `OVERPRIVILEGED`, each cycle runs the checks again before a read, thus a corrected role ends
  the stop.
- **D10. MongoDB client.** One client for each app, created at the first poll cycle, not at
  the start of the monitor. The monitor keeps it for the next cycles and closes it after a
  PATCH of the connection string or a delete of the app. `maxPoolSize=2`, `serverSelectionTimeoutMS=10000`, `maxIdleTimeMS=120000`,
  `appName=octometer`. The monitor checks the scheme with a string test before the driver
  parses the URI. A PATCH of the connection string to a different string resets `cursor`,
  `last_poll_at`, `last_success_at`, `next_poll_at`, `status`, and `last_error` to `NULL`, and
  `consecutive_failures` to 0 (maintainer decision, 2026-09-26): the new source then reads
  each event from the oldest one. A PATCH with the same string, or of the name alone, keeps
  every column. The reset costs time only, not a wrong number: the reader stores each event
  one time, by its `_id` (issue #187). A cycle in flight can read the old cursor before this
  reset and write it back after; each cursor write of a cycle carries a guard, the cursor
  value that the cycle last saw, so a write after a reset changes 0 rows (maintainer decision,
  2026-09-26, MAJOR 1 of the correction round of pull request #208).
- **D11. Secrets.** The registry stores each connection string in
  `%LOCALAPPDATA%\Octometer\secrets\apps.json`, outside `dataDir`, thus a database backup
  holds no credential. No encryption in the application. The run guide names BitLocker
  (`manage-bde -status C:`). The API never returns a connection string. The code parses the
  URI one time, keeps `MongoClientSettings`, and never logs a `ConnectionString` or a
  `Config` object. Accepted: `mongodb+srv://`, and `mongodb://` only for a loopback host.
  Corrected in the review of pull request #125: the query check is an allow-list of option
  names, not a deny-list. The query splits on `&` and on `;`, because the driver accepts
  both. Each option name is decoded and lower-cased before the compare. The allowed names
  are `retryWrites`, `retryReads`, `w`, `appName`, `authSource`, and `replicaSet`, with any
  value, plus `tls` and `ssl` with the value `true`, in any letter case. Each other name, a repeated name,
  and a name with no value are rejected. No allowed name can turn off TLS, change the read
  preference, or send a credential to a different host (source: the MongoDB manual,
  "Connection String Options", read on 2026-09-21; see the pull request text for the exact
  finding of each option).
- **D12. Exposure.** The server binds to `127.0.0.1` and refuses another address. No login.
  Each request passes a Host check (`localhost:<port>`, `127.0.0.1:<port>`, plus the
  `ng serve` port in dev mode). GET and HEAD are the safe methods; each other method needs an
  allowed `Origin` header, also `OPTIONS`, `TRACE`, and a custom method. Each request that
  holds a body needs `Content-Type: application/json` (else 415); a request without a body
  needs no `Content-Type` header. The server never installs the CORS plugin. It
  sends `Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline';
  object-src 'none'; base-uri 'self'; frame-ancestors 'none'` (Angular adds its component
  styles as inline `<style>` elements), `X-Content-Type-Options: nosniff`, and
  `Cache-Control: no-store` on the API.
- **D13. API.**
  - `GET /api/health`: `version`, `schemaVersion`, `mode`, `refreshSeconds`, the database
    size, the free disk space.
  - `GET /api/apps`: level 1 totals plus `status`, `lastSuccessAt`, `lastError`, `nextPollAt`.
  - `GET /api/apps/{appId}/users?page=&q=`: level 2, pages of 50, the filter `q` on the user
    id, the fixed order `clicks DESC, user_id ASC`, a page clamp with `page` and `pageCount`.
    A row holds `userId: string | null`.
  - `GET /api/apps/{appId}/elements?userId=<id>`, `?anonymous=true`, or `?sessionId=<uuid>`:
    level 3, no pages, the fixed order `clicks DESC, element ASC`, exactly one of the three
    parameters, else 400 (design decision D44).
  - `GET /api/apps/{appId}/first-pages?page=`: the first-page table, pages of 50, the fixed
    order `sessions DESC, path ASC` (design decision D44).
  - `GET /api/apps/{appId}/sessions?anonymous=true&firstPath=<path>&page=`: the anonymous
    session table, pages of 50, the fixed order `startTime DESC, sessionId ASC`. The parameter
    `anonymous=true` is mandatory, and it selects each session that holds a minimum of one
    event with `user_id IS NULL`. The parameter `firstPath` is optional, and it keeps the
    sessions of one first path. Each other parameter set gives 400 (design decision D44).
  - Registry: `POST /api/apps` (JSON fields `name`, `connectionString`, `database`,
    `collection`),
    `PATCH /api/apps/{id}` (name, connection string), `DELETE /api/apps/{id}` (deletes the
    events in chunks of 10 000).
  - `DELETE /api/apps/{appId}/events?userId=<id>`: the erasure of one user. Contract rule C43
    sets its width: it also removes the anonymous events of each session of that user id.
  - JSON field names. Health: `version`, `schemaVersion`, `mode`, `refreshSeconds`,
    `retentionDays`, `databaseBytes`, `freeDiskBytes`. Level 1 row: `appId`, `name`,
    `clicks`, `uniqueUsers`, `uniqueSessions`, `status`, `lastSuccessAt`, `lastError`,
    `nextPollAt`, `gaps`. Level 2: `page`, `pageCount`, `rows` with `userId`, `clicks`,
    `sessions`, `uniqueElements`. Level 3: `rows` with `element`, `clicks`, `sessions`,
    `lastInteractionAt`. First pages: `page`, `pageCount`, `rows` with `path`, `sessions`.
    Anonymous sessions: `page`, `pageCount`, `rows` with `sessionId`, `firstPath`, `source`,
    `startTime`, `clicks`, `userId`. A gap
    holds `from` and `to`. The erasure returns `deleted` and `checkpointed`. Each time field is UTC ISO 8601
    with milliseconds.
  - Each route of D44 keeps the Host check of D12, sends `Cache-Control: no-store`, and adds
    no CORS header.
- **D14. Totals.** SQL at query time, with the indexes and statements of section 6. No rollup
  table and no result cache in version 1.
- **D15. Personal data.** A log line never holds a user id or a connection string. A sentinel
  test searches the captured log. `PRAGMA secure_delete=ON`. After an erasure and after a
  purge: `PRAGMA wal_checkpoint(TRUNCATE)`. The purge runs at the start and each 24 hours, in
  chunks of 10 000 rows. The erasure order is: first the app, then one poll cycle, then the
  monitor. Contract rule C43 sets the width of an erasure: the erasure of one user id also
  removes the anonymous events of each session of that user id. An event of a second user id
  in the same session stays. The kit (#35) and the monitor (#61) both apply the rule. Reason:
  after a sign-in in the same tab, the earlier anonymous events of that session describe an
  identified person.
- **D16. Gap.** At the cycle start, a cursor time older than the server time minus 29 days
  writes a gap row: `from_ts` is the time of the cursor `_id`, and `to_ts` is the server time
  minus 30 days. The UI shows it.

### App side (the kit)

- **D17. Languages.** `kit/jvm-core`, `kit/jvm-mongo`, and `kit/jvm-spring` use Java 21 with
  no Kotlin library, because the Spring Boot 3.3 BOM forces `kotlin-stdlib` 1.9.25. Only
  `kit/jvm-ktor` uses Kotlin. Each framework dependency of an adapter is `compileOnly`.
- **D18. Core.** It takes the raw body as a `String` and parses it with its own strict
  parser for the one shape of section 4.2. The parser accepts `path` and `referrerHost`. The
  raw client `path`, after the shape check of rule C39, stays in the parsed click only. No
  store gets the raw client value. The event record and the call `append` carry
  `referrerHost` and one component `path`. The component `path` holds only the match result
  of the server (rule C42). It is empty when the kit has no route pattern list. The decision
  holds the validation, the `ts` rule, a `UserIdResolver` that the app gives, the
  `EventLogStore` interface (`append`, `deleteByUserId`), and an in-memory store.
  `deleteByUserId` also removes the anonymous events of each session of that user id
  (contract rule C43).
- **D19. Anonymous clicks.** Off by default. `OCTOMETER_RECORD_ANONYMOUS=true` turns them on.
  The pilot app `investguideua` turns it on. Design decision D43 states its caps.
- **D20. Rate limit.** In memory, a fixed window of 60 seconds, two maps with an LRU
  eviction: 5 000 user keys and 20 000 anonymous keys. The key is the user id, with 30
  requests each minute. Without a user id the key is the client address, with 120 requests
  each minute. With anonymous events on (D19), decision D43 replaces that limit of 120
  requests, and it sets the form of the anonymous key. The kit reads the IP address from the
  header that `OCTOMETER_CLIENT_IP_HEADER` names, at the position that
  `OCTOMETER_TRUSTED_PROXY_COUNT` sets (default 1, counted from the right of the header
  list), or from the remote address without that header. The IP address stays in memory
  only. Each map is empty at the start of each window. An LRU eviction removes the oldest
  key when a map is full.
- **D21. Event cap.** `OCTOMETER_MAX_EVENTS`, default 200 000. The store reads
  `estimatedDocumentCount()` a maximum of one time each 60 seconds. Above the cap it drops the
  batch, returns 204, and writes one warning. Reason: a full M0 cluster refuses each write of
  the app. The pilot app `investguideua` keeps this default. It has fewer than 500 visits
  each day (owner decision, 2026-09-21).
- **D22. MongoDB store.** The constructor takes a `com.mongodb.client.MongoDatabase` from the
  app. The kit never creates a client. The driver is `compileOnly`
  `org.mongodb:mongodb-driver-sync:5.0.1`. The tests run against 5.0.1 and against the newest
  5.x. At start the store creates the TTL index. On error code 85 it runs `collMod`. An index
  error gives a warning, never a failed app start. The store writes a field only when the
  field is present. It adds no key with a null value. An app with the coroutine driver adds
  `mongodb-driver-sync` at the same version and one sync client with `maxPoolSize=5`.
- **D23. Adapters.** Each one mounts only the ingest route, with an `ingestPath` option. The
  route stays in the security chain of the app, because it needs the session. It answers 415
  to a content type other than JSON. It writes no CORS header. The Ktor adapter calls the
  store inside `withContext(Dispatchers.IO)`. The default is a view of `Dispatchers.IO` that
  `limitedParallelism(8)` gives, so the route never fills the whole shared pool.
- **D24. Tracker.** Plain TypeScript, ESM, zero dependencies, `"sideEffects": false`, no code
  at import time, no action in SSR. Options: `endpoint` (mandatory), `credentials` (default
  `same-origin`), `headers: () => Record<string,string>`, `flushIntervalMs` (default 5000),
  `routes` (the ordered route pattern list of D40), and `ignoreWebdriver` (default false,
  D41). It is off until the app calls `start()` after the consent signal. For a new session
  id, `start()` sends the entry `octo:session-start` at once, in its own request (design
  decision D41). `stop()` empties the queue and removes the `sessionStorage` key. One
  capture-phase `click` listener searches `event.composedPath()` for the first element with
  `data-octo`. It skips a disabled element.
  The queue holds a maximum of 200 entries and drops the oldest entry when it is full. A
  keepalive body stays below 64 KB. One `setTimeout` starts when a click enters an
  empty queue (no interval). On `pagehide` and on `visibilitychange` to hidden it sends with
  `fetch` and `keepalive: true`. It retries a batch one time after a network error, a 5xx, or
  a 429, after a wait. The wait is 500 ms plus a random value below 500 ms for a network
  error or a 5xx. The wait is 2000 ms plus a random value below 2000 ms for a 429, because
  the rate limit uses a fixed 60-second window (D20, D43). Only the timer flush retries. The
  `pagehide` flush and the `visibilitychange` flush send a batch one time only. A browser can
  freeze the page after the hidden state and drop the connection. The UUID falls back to
  `crypto.getRandomValues`. Each `sessionStorage` access has a try/catch.
- **D25. Distribution.** JVM: JitPack with a `jitpack.yml` (`jdk: openjdk21`, an `install`
  command that publishes only the kit modules, `-x test`). Artifact IDs `octometer-kit-core`,
  `octometer-kit-mongo`, `octometer-kit-ktor`, `octometer-kit-spring`. An app depends only on
  a release tag, and it limits the JitPack repository with `exclusiveContent`. Tracker: `npm pack`, the `.tgz` file on the GitHub Release. No npm account.
- **D26. Release.** One SemVer tag `X.Y.Z` without a prefix. `release.yml` checks the top
  entry of `CHANGELOG.md` and the tracker version, builds `octometer-monitor-X.Y.Z.zip`
  (`distZip` with the Angular build), attaches the zip and the tracker `.tgz`, and requests
  the JitPack POM files. Rule: upgrade the monitor before an app.

### Frontend

- **D27. Baseline.** The current stable Angular (confirm with `npm view @angular/core
  version`), standalone components, zoneless change detection, signals, Vitest, the Router
  with `withComponentInputBinding()`. No NgRx, no SSR, no component library. The dev proxy
  target is `http://127.0.0.1:7431` with `changeOrigin: false`.
- **D28. Views.** Routes `/manage`, `/apps`, `/apps/:appId/users`, `/apps/:appId/elements`
  (with the query parameter `userId=<id>`, `anonymous=true`, or `sessionId=<uuid>`, the same
  as the API), `/apps/:appId/first-pages`, and `/apps/:appId/sessions` (with the mandatory
  query parameter `anonymous=true` and the optional parameter `firstPath=<path>`, the same as
  the API; design decision D44). Each view is one flat `<table>` with a `<caption>` and
  `<th scope>`. The name cell is
  `<th scope="row"><a routerLink>`, and a click on a row cell opens that link. The breadcrumb is `<nav aria-label="Breadcrumb"><ol>` with
  `aria-current="page"`. The first load keeps the focus. After each later navigation that
  changes the path or a path parameter, the focus moves to `<h1 tabindex="-1">`. A change of
  only a query parameter keeps the focus.
- **D29. Poll store.** `timer(0, ms)` plus `exhaustMap`, with `catchError` inside. Signals
  `data`, `lastSuccessAt`, `error`. No `httpResource`. `@for` tracks the stable key. A refresh
  keeps the old rows until the new rows arrive. A button "Pause refresh" with `aria-pressed`
  (WCAG 2.2.2). The refresh stops while the focus is in `<tbody>` and while
  `document.hidden` is true.
- **D30. States.** First load, no app, `NEVER_POLLED` (show "–", not 0), zero events, a failed
  app poll (the numbers stay, plus "Data from HH:mm:ss" and the visible `lastError`), a failed
  monitor API (a banner with a reserved height), a 404 on an open view, a long user id
  (`overflow-wrap: anywhere`, no ellipsis). Numbers use `Intl.NumberFormat`, right alignment,
  and `tabular-nums`.
- **D31. Time.** `<time datetime="...Z">yyyy-MM-dd HH:mm:ss</time>` in local time. The column
  header shows the zone name. No relative time.
- **D32. Management page.** A list with the status of each app. A form to add an app. A
  control to replace the connection string. A delete with a native `<dialog>`, the typed app
  name, and the event count. The connection string field is write-only.
- **D33. Accessibility.** Each UI issue holds its criteria: no CSS `display` on table
  elements, targets of 24 by 24 CSS px, status as an icon plus text, one permanent
  `role="status"` region for a user action and for one transition of the monitor connection,
  `aria-disabled` on pager buttons.

### Operation

- **D34. Layout.** `%LOCALAPPDATA%\Octometer\` holds `app\<version>\`, `data\`, `secrets\`,
  `logs\`, `backups\`, `octometer.conf`, and `octometer.cmd`. The folder is outside OneDrive,
  because SQLite WAL is not safe in a synced folder.
- **D35. Start.** `installDist` and `distZip`, with the wildcard class path `lib\*` in the
  Windows script. `scripts/install.ps1` registers the scheduled task `Octometer` at logon,
  with no time limit, battery start, and 3 restarts.
- **D36. Backup and upgrade.** `VACUUM INTO` one time each day, 7 files kept, plus one backup
  `pre-migrate-v<n>-<timestamp>.db` before a migration, where `<n>` is the schema version of
  the file before the migration. `scripts/upgrade.ps1 -Version X.Y.Z` downloads the zip with
  `gh release download X.Y.Z --repo xamcross/octometer --pattern "octometer-monitor-*.zip"`,
  switches the launcher, reads `/api/health` for a maximum of 60 seconds, and restores on a
  failure.
- **D37. Logs.** Logback rolling files: 10 MB, 14 days, 200 MB total. A log line for a status
  change and for a failure, not for each good poll.
- **D38. Repository safety.** `.gitignore` (`data/`, `*.db*`, `secrets/`, `*.log`,
  `octometer.conf`, `build/`, `node_modules/`, `dist/`), `.gitleaks.toml` with a rule for a MongoDB URI with a
  password, `.githooks/pre-commit` with `gitleaks protect --staged`, a CI job with
  `gitleaks detect`, secret scanning and push protection in the repository settings,
  Dependabot.
- **D39. CI.** One workflow for a push to `main` and for a pull request, each job with `timeout-minutes: 15`, no `paths` filter,
  `concurrency` with `cancel-in-progress`. Jobs: `jvm` (ubuntu, Testcontainers),
  `monitor-windows` (tests without Docker, `installDist`, start the script, read
  `/api/health`), `frontend`, `tracker`, `consumer-smoke`, `secrets`.
  `@Testcontainers(disabledWithoutDocker = true)` locally.

### Version 1.1 (the first page and the anonymous visitor)

Source: the design brief of issue #101, and `contract/README.md` version 1.1 (rules C38 to
C43). Where the two differ, `contract/README.md` on `main` has priority.

- **D40. Route pattern list.** The app gives one ordered list of route patterns to the
  tracker (option `routes`) and to the kit (`OCTOMETER_PATH_PATTERNS`). Contract rule C42
  states the pattern match and the `/other` fallback. The tracker repeats the same match
  before it sends a click. The kit repeats it again on the server, because a client rule is
  not a control. The monitor shows the stored path as plain text only, with no
  `[innerHTML]`, no `[href]`, no `[attr.*]`, and no `DomSanitizer.bypass*` call, because a
  kept `*` segment can hold an escaped markup text. Without the list, the tracker sends no
  `path`, and the kit stores none.
- **D41. Session start.** `start()` creates the session id and, for a new id, sends one entry
  `octo:session-start` at once, in its own request, with `ageMs: 0` (contract rule C38). While
  `document.prerendering` is true, `start()` waits for the event `prerenderingchange`. While
  `document.visibilityState` is `hidden`, it waits for the event `visibilitychange`. It then
  runs one time only. A new id enters `sessionStorage` only at the moment the session start
  request goes out, after this wait (maintainer decision, 2026-09-22). A document that ends before
  that moment stores no id, and the next document of the same tab starts a fresh session. It
  sends nothing when `navigator.webdriver` is true, except when the option `ignoreWebdriver` turns
  this check off for an end-to-end test. A `true` value of `navigator.webdriver` blocks the whole
  tracker, the click listener included, not only the session start. Without `sessionStorage`, the
  tracker keeps the id in a module variable, thus it sends one session start for each
  document. The pilot app `investguideua` calls `start()` in the browser only, for example
  from `afterNextRender`, because it uses server-side rendering.
- **D42. Visitor source.** The `octo:session-start` entry can hold `referrerHost`: the host
  of `document.referrer` in lower case, matched against the closed set of contract rule C40
  (`google.com`, `bing.com`, or `other`). The tracker sends no `referrerHost` field for one of
  these cases:

  - an empty referrer;
  - a referrer with a scheme other than `http` or `https`;
  - the origin of the app;
  - an IP literal;
  - a host without a dot.

  This is a rule of the tracker. Contract rule C40 states the same five cases. An absent
  field marks a direct visit: a typed address, a bookmark, or a source that sends no
  referrer. The monitor shows the text `(direct)` for it. The column `referrer_host` holds
  only `NULL`, `google.com`, `bing.com`, or `other`, thus it needs no purge: decision D5
  (2026-09-26, issue #196) has the reader drop each other value, for a document that reaches
  MongoDB by a route outside the kit too.
- **D43. Anonymous caps.** With anonymous events on (D19), the kit applies three counters to
  one key in a window of 60 seconds. The counters are 300 requests, 900 click entries, and
  120 entries `octo:session-start` (`OCTOMETER_ANON_REQ_PER_MIN`,
  `OCTOMETER_ANON_EVENTS_PER_MIN`, `OCTOMETER_ANON_SESSIONS_PER_MIN`). A key is one IPv4
  address, or the first 64 bits of an IPv6 address. These three counters replace the limit of
  120 requests each minute of D20 for a request without a user id. Above a per-minute counter
  the kit answers 429, as decision D20 and contract rule C19 state. A global cap
  `OCTOMETER_MAX_ANON_EVENTS_PER_DAY` (default 20 000) and a cap for each key
  `OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY` (default 2 000) limit one day. Above a daily cap,
  the kit drops the batch, returns 204, and writes one warning each hour. The ingest
  route also drops a batch when the `User-Agent` header matches
  `bot|crawl|spider|slurp|headless|preview|monitor|Go-http-client|python-requests|curl`. The
  match ignores the letter case. The route returns 204, it writes one debug line, and it
  never stores the user agent. Each daily counter resets each 24 hours. The daily key map
  holds 20 000 keys with an LRU eviction. This daily map is a third map. It is separate from
  the two windowed maps of D20. The pilot app `investguideua` has fewer than 500
  visits each day, thus the default caps fit (owner decision, 2026-09-21).
- **D44. First pages and anonymous sessions.** Two views join the monitor: "First pages"
  (`path`, `sessions`) and "Anonymous sessions". Each row of "Anonymous sessions" holds the
  session id, the first path, and the source. It holds the start time and the click count. It
  holds the user id of a later sign-in in the same session: the `user_id` of the event with the
  smallest `ts` among the events of that session with `user_id IS NOT NULL` (one session can
  hold two users; the earliest sign-in wins, never the last). The first-pages statement holds no
  `COALESCE`. The API writes `(unknown)` for a `NULL` path. A session without a start row
  shows the path `(unknown)` and the time of its first click. A row of "First pages" opens
  "Anonymous sessions" with `firstPath`. The row `(anonymous)` of level 2 opens "Anonymous
  sessions" too. A session row opens the element view with `sessionId`. Each level 1 app row
  gets a link cell "First pages". "First pages" counts `COUNT(DISTINCT session_id)`, and
  "Anonymous sessions" takes one row for each session. A second `octo:session-start` row of one
  session, from a retry that still reached the store, thus changes no count of either view.

## 6. Monitor data model (SQLite)

```sql
CREATE TABLE app (
  id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, database_name TEXT NOT NULL,
  collection_name TEXT NOT NULL, created_at INTEGER NOT NULL,
  cursor TEXT, next_poll_at INTEGER, last_poll_at INTEGER, last_success_at INTEGER,
  status TEXT, last_error TEXT, consecutive_failures INTEGER NOT NULL DEFAULT 0,
  privileges_checked_at INTEGER
) STRICT;

CREATE TABLE event (
  app_id INTEGER NOT NULL REFERENCES app(id), event_id TEXT NOT NULL,
  ts INTEGER NOT NULL,                       -- epoch milliseconds, UTC
  element TEXT NOT NULL, session_id TEXT NOT NULL,
  user_id TEXT CHECK (user_id IS NULL OR user_id <> ''),
  path TEXT, referrer_host TEXT, kind INTEGER NOT NULL DEFAULT 0, -- 0 = click, 1 = session start
  PRIMARY KEY (app_id, event_id)
) STRICT;

CREATE INDEX event_agg     ON event(app_id, user_id, element, session_id, ts) WHERE kind = 0;
CREATE INDEX event_session ON event(app_id, session_id, element, ts, user_id, kind);
CREATE INDEX event_first_page ON event(app_id, path, ts, session_id, user_id) WHERE kind = 1;
CREATE INDEX event_start   ON event(app_id, user_id, ts, session_id) WHERE kind = 1;

CREATE TABLE skipped_event (app_id INTEGER NOT NULL, event_id TEXT NOT NULL, reason TEXT NOT NULL,
  PRIMARY KEY (app_id, event_id)) STRICT;
CREATE TABLE gap (app_id INTEGER NOT NULL, from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL) STRICT;
```

Migration `002` adds the columns `path`, `referrer_host`, and `kind`, then replaces
`event_agg` and `event_session`, and adds `event_first_page` and `event_start` (design
decisions D40, D42, D44). The reader sets `kind = 1` for the element `octo:session-start`. It
copies `path` from each event, and `referrer_host` from a session start.

Level 1 uses three statements, each on one covering index: `COUNT(*)` and
`COUNT(DISTINCT user_id)` with the filter `kind = 0`, and `COUNT(DISTINCT session_id)` with
no `kind` filter, because a first visitor who clicks nothing is a session too. Each statement
groups by `app_id`. Level 2 and level 3 keep the filter `kind = 0`, else SQLite ignores the
index `event_agg`. Level 2 groups by `user_id`. Level 3 filters with `user_id IS ?` and
groups by `element`. The first-pages query filters with `kind = 1`, and it uses the index
`event_first_page`. The anonymous-sessions query reads the start row of each session with
`kind = 1` on the index `event_start`. It counts the clicks of that session with `kind = 0` on
the index `event_session`. A session without a start row holds no row with `kind = 1`, thus
the query finds that session on `event_session` too. Each `ORDER BY` ends
with a unique column. `COUNT(DISTINCT user_id)` ignores `NULL`, thus level 2 can show one row
more than the level 1 user count. The sums across the levels do not agree, because one
session can belong to two users.

## 7. Out of scope for version 1

A time-range filter, a chart, an alert, an export, a login, a Docker image, a rollup table, a
result cache, an event type other than a click and the session start of D41, a record of each
page view, a native mobile app, a sort that the user selects, a disable operation for an app,
the encryption of a connection string, an OpenAPI file, the npm registry, a Playwright test, a
new session id after 30 idle minutes, an hourly repair read, X.509 and AWS IAM authentication.

## 8. Owner work

- Create the database role and the user of each app (section 4.4).
- Decide the legal basis and the consent method for click tracking. The tracker sends the
  clicks of an identified user. The reviewer cites EDPB Guidelines 2/2023 and ICO guidance:
  the analytics exemptions exclude data at user level.
- Approve the privacy notice text in `docs/privacy.md`.
- Create three Atlas alerts for each app: connections, network, and storage at 80 percent. M0
  records no access history, thus an alert is the only signal of a leaked password.
- Decide the retention time of the monitor store. The default is 13 months.
- Done: the owner selected the pilot app `investguideua` on 2026-09-21 (the owner comment on
  issue #43). It uses Spring Boot 3.4.1 with Maven, and Angular 17.3.
- Verify the start after a reboot, and do the screen reader pass.
- Accept the residual risks: a forged click, a hostile browser extension on the monitor
  machine, a leaked reader password (M0 records no access history), and a reader role with a
  write action that the monitor cannot see when the cluster reports no privilege list.

## 9. Backlog

Five milestones, named after the result. Labels follow the skill `managing-github-issues`.
Filed on 2026-09-21 in `xamcross/octometer` as the issues #1 to #71. Version 1.1 adds the
issues #101 to #119, and issue #111 is closed, with its rule kept in issues #18, #50, and #51.

| Key, issue | Title |
|---|---|
| **A** | **Demo numbers on level 1** |
| A01, #1 | Prove the read-only database user on a real Atlas M0 cluster |
| A02, #2 | Create the git repository with the first commit |
| A03, #3 | Create the Gradle build with the Ktor monitor server |
| A04, #4 | Load the monitor config with a fixed precedence |
| A05, #5 | Reject a request with a wrong Host or Origin header |
| A06, #6 | Create the Angular frontend with the dev proxy |
| A07, #7 | Add the CI workflow for each module |
| A08, #8 | Write the event log contract v1 |
| A09, #9 | Add the SQLite store with versioned migrations |
| A10, #10 | Build the ingest logic of kit/jvm-core |
| A11, #11 | Build the MongoDB event store in kit/jvm-mongo |
| A12, #12 | Build the Ktor ingest route in kit/jvm-ktor |
| A13, #13 | Build the click tracker core |
| A14, #14 | Build the demo app with synthetic clicks |
| A15, #15 | Add the app registry API |
| A16, #16 | Read the new events of one app from MongoDB |
| A17, #17 | Schedule the poll of each app on the mode interval |
| A18, #18 | Serve the level 1 totals |
| A19, #19 | Build the UI shell |
| A20, #20 | Show the app table (level 1) |
| A21, #21 | Add the end-to-end test from a click to the level 1 totals |
| A22, #22 | Add the secret scan to the repository |
| A23, #23 | Add the Dependabot config |
| A24, #24 | Show the demo clicks on the level 1 view |
| A25, #25 | Build the poll store of the frontend |
| A26, #26 | Add the store interface and the in-memory store to kit/jvm-core |
| A27, #27 | Skip an invalid document and set the status of the poll cycle |
| **B** | **Pilot numbers** |
| B01, #28 | Set the poll status and the backoff after a failure |
| B02, #29 | Delay the first poll after a wake from sleep |
| B03, #30 | Check the privileges of the database user |
| B04, #31 | Keep each connection string out of the log |
| B05, #32 | Add the app management page |
| B06, #33 | Limit the ingest rate in kit/jvm-core |
| B07, #34 | Stop the ingest above the event cap |
| B08, #35 | Delete the events of one user id in the kit stores |
| B09, #36 | Add the page lifecycle flush and the retry rule to the tracker |
| B10, #37 | Publish the JVM kit through JitPack |
| B11, #38 | Bundle the frontend into the monitor distribution |
| B12, #39 | Add the release workflow |
| B13, #40 | Write the integration guide for a Ktor app |
| B14, #41 | Decide the legal basis and the consent method for click tracking |
| B15, #42 | Approve the privacy notice text |
| B16, #43 | Select the pilot app |
| B17, #44 | Attach the tracker package to each GitHub Release |
| B18, #45 | Add the Atlas setup and the privacy text to the Ktor integration guide |
| B19, #46 | Release version 0.1.0 for the pilot |
| B20, #47 | Accept the residual risks of version 1 |
| B21, #48 | Write the two issue templates for an app repository |
| B22, #49 | Record the first numbers of the pilot app |
| **C** | **Three levels** |
| C01, #50 | Serve the level 2 totals with pages and a filter |
| C02, #51 | Serve the level 3 totals |
| C03, #52 | Show the user table (level 2) |
| C04, #53 | Show the element table (level 3) |
| **D** | **Operate** |
| D01, #54 | Install the monitor as a scheduled task on Windows |
| D02, #55 | Back up the database each day and before a migration |
| D03, #56 | Add the upgrade script |
| D04, #57 | Write the log to files with rotation |
| D05, #58 | Test the store against a corrupt, a locked, and a full database |
| D06, #59 | Purge the events above the retention limit |
| D07, #60 | Decide the retention time of the monitor store |
| D08, #61 | Delete the events of one user id in the monitor |
| D09, #62 | Show a gap after a long off-time of the monitor |
| D10, #63 | Measure the three levels with 1 million and 10 million events |
| D11, #64 | Write the run guide |
| D12, #65 | Verify the UI with the keyboard, the zoom, and a narrow width |
| D13, #66 | Show the retention time on the level 1 view |
| D14, #67 | Do the NVDA pass of the UI |
| **E** | **Second stack** |
| E01, #68 | Add the consumer smoke projects for Spring Boot 3.3.5 and 4.1 |
| E02, #69 | Build the Spring MVC ingest controller in kit/jvm-spring |
| E03, #70 | Write the integration guide for a Spring Boot app |
| E04, #71 | Publish kit/jvm-spring through JitPack |

## 10. Review record

Seven reviewers challenged the draft on 2026-09-21: a software architect, a security and GDPR
engineer, a data engineer, a frontend and accessibility engineer, a DevOps and test engineer,
a product owner, and a security engineer for the direct read (O4).

Main results: vertical slices in place of component layers; Java for the kit core; the ingest
path behind the `/api` proxy; the event cap; the consent gate; the data location outside the
repository; the settle lag on `_id` with the server clock; the privilege check with two
methods; covering indexes (8.4 s to 0.33 s for level 1 at 1 million events); `PRAGMA
user_version` in place of Flyway; the removal of the OpenAPI file, the contract test tool, the
npm registry, the Playwright test, and the key encryption. The security reviewer proposes a
monitor retention of 90 days for identified data; the default stays at 395 days until the
owner decides.

Four reviewers then read the 61 issue drafts. Main results: no issue wrote the status `OK`,
thus level 1 showed no number; one invalid document stopped an app; the CSP blocked the
Angular styles; a new app with `next_poll_at = NULL` was never polled; about 20 blockers were
absent; the pilot did not wait for the production blockers.
