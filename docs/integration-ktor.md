# The Ktor integration guide

This guide connects a Ktor app to Octometer. A developer follows this guide alone, with
no read of the design document. The guide names no real app and no real organisation.
Where this guide says "your app", put the name of your own app.

**A note for an app behind a path-limited proxy.** Some apps sit behind a proxy. The
proxy forwards only a few path prefixes to the backend, for example an API prefix, an
OAuth prefix, and a login-callback prefix. The app also sets a Content Security Policy
with `connect-src 'self'`. Put the ingest path of step 5 below the proxied prefix. The
tracker of step 7 then posts to the same origin as the page. The proxy forwards the
request in that case.

## 1. The dependency

Add the JitPack repository. Limit it to the Octometer group with `exclusiveContent`, so
Gradle never asks JitPack for an artifact of a different group.

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven { url = uri("https://jitpack.io") }
        }
        filter {
            includeGroup("com.github.xamcross.octometer")
        }
    }
}

dependencies {
    implementation("com.github.xamcross.octometer:octometer-kit-ktor:0.1.0")
    implementation("com.github.xamcross.octometer:octometer-kit-mongo:0.1.0")
}
```

Add both coordinates. The Ktor route lives in `octometer-kit-ktor`. The MongoDB store,
`MongoEventLogStore`, lives in `octometer-kit-mongo`. The artifact `octometer-kit-core`
comes with each of them, as a transitive dependency.

**Upgrade the monitor before an app (design decision D26).** Read the release notes of
the monitor first. Upgrade the monitor. Upgrade the version of the kit in your app after
that.

## 2. The driver

Add `mongodb-driver-sync` at the same version as the MongoDB coroutine driver of your
app (design decision D22). The kit tests run against the driver version 5.0.1 and
against the newest 5.x driver at the time of the test.

Build one MongoDB client, with one connection pool of `maxPoolSize=5`. A small pool
protects the connection limit of a small MongoDB Atlas cluster, for example the 500
connections of an M0 cluster.

```kotlin
val settings = MongoClientSettings.builder()
    .applyConnectionString(ConnectionString(mongoUri))
    .applyToConnectionPoolSettings { it.maxSize(5) }
    .applyToSocketSettings { it.readTimeout(5, TimeUnit.SECONDS) }
    .writeConcern(WriteConcern.ACKNOWLEDGED.withWTimeout(5, TimeUnit.SECONDS))
    .build()
val mongoClient = MongoClients.create(settings)
val store = MongoEventLogStore(mongoClient.getDatabase(yourDatabaseName))
```

**Set a timeout.** The store sets no timeout of its own. Without a timeout, a blocked
primary node holds a thread of the ingest route. On driver 5.0, set a socket read
timeout. Also set a write concern `wtimeout`, as the example above shows. On driver 5.2
or newer, set `timeoutMS` instead.

**Set the database role.** The app database user needs a custom role on
`octometer_events` with four actions: `insert`, `createIndex`, `collMod`, and `find`.
The built-in role `readWrite` has no `collMod`. Without `find`, the event cap of design
decision D21 never stops the ingest, and the store writes one warning each hour.

## 3. The database user

Create the read-only role and the user for the monitor. This user is not the app
database user of section 2. The owner runs each command below on the Atlas CLI. A
password must never pass through an agent.

```
atlas customDbRoles create octometerEventReader --privilege FIND@<database>.octometer_events --projectId <id>
atlas dbusers create --username octometer-reader --role octometerEventReader --projectId <id>
atlas dbusers describe octometer-reader --projectId <id> -o json
```

The CLI prompts for the password. This keeps the password out of the shell history.
Make the password with `openssl rand -hex 24`. Use one password for each Atlas project.

Rotate the password after a lost laptop, after a suspected leak, and each 12 months.
Delete the user, then create it again.

**Check the role.** Run `contract/fixtures/check-privileges.js` against the new user
with `mongosh`, as `contract/fixtures/README.md` states. The script proves that the
user holds only the `find` action on `octometer_events`. Design decision D9 gives the
status `OVERPRIVILEGED` for a wider role, once issue #30 reaches `main`.

**The connection string for the monitor.** Build the string in this exact form:

```
mongodb+srv://octometer-reader:<password>@<cluster host>/<database>
```

Use the SRV host. The form above holds no option. Design decision D11 also allows a
`tls=true` option or a `ssl=true` option in a connection string, but this form adds
none.

Keep `<password>` and `<cluster host>` inside angle brackets in each copy of this
string. Never put a real value in their place. A real host next to a real password
triggers a secret alert.

The monitor stores this string in its own registry, outside its data folder (design
decision D11). Paste the string into the manage form of the monitor. Section 5 of
`docs/demo.md` shows the form fields.

## 4. The alerts

M0 keeps no access history. An alert is the only signal of a leaked password (design
section 8). Each alert below needs a notification target, or the alert reaches nobody.

**1. Connections.**

```
atlas alerts settings create \
  --event OUTSIDE_METRIC_THRESHOLD \
  --metricName CONNECTIONS \
  --metricOperator GREATER_THAN \
  --metricThreshold <n> \
  --metricUnits RAW \
  --notificationType GROUP \
  --notificationEmailEnabled \
  --notificationIntervalMin 5 \
  --projectId <id>
```

Set `<n>` below the connection limit of the M0 cluster. Section 2 states this limit
as 500.

**2. Network.**

```
atlas alerts settings create \
  --event OUTSIDE_METRIC_THRESHOLD \
  --metricName NETWORK_BYTES_IN \
  --metricOperator GREATER_THAN \
  --metricThreshold <n> \
  --metricUnits MEGABYTES \
  --notificationType GROUP \
  --notificationEmailEnabled \
  --notificationIntervalMin 5 \
  --projectId <id>
```

Set `<n>` for the normal traffic of your app.

**3. Storage, at 80 percent of the M0 limit.**

M0 has a storage limit of 512 MiB, that is 536870912 bytes. Eighty percent of that
value is 429496730 bytes. A free cluster has no disk-partition metric, thus this
alert checks the total document data size metric instead.

```
atlas alerts settings create \
  --event OUTSIDE_METRIC_THRESHOLD \
  --metricName DB_DATA_SIZE_TOTAL \
  --metricOperator GREATER_THAN \
  --metricThreshold 429496730 \
  --metricUnits BYTES \
  --notificationType GROUP \
  --notificationEmailEnabled \
  --notificationIntervalMin 5 \
  --projectId <id>
```

Use `--notificationType EMAIL --notificationEmailAddress <address>` in place of
`--notificationType GROUP --notificationEmailEnabled` above, when the project has no
group to notify. Keep `--notificationIntervalMin`.

## 5. The route

Mount the ingest route inside the routing block that already has the session or the
authentication plugin of your app. The route needs the session (design decision D23).

```kotlin
routing {
    octometerIngestRoute(
        store = store,
        ingestPath = "/api/octometer/v1/clicks",
    ) { call ->
        call.sessions.get<UserSession>()?.userId
    }
}
```

The parameter `ingestPath` sets the path of the route. The default is
`/api/octometer/v1/clicks` (contract rule C12). Put this path below the proxied prefix
of the note near the top of this guide.

**Set an engine timeout.** The route sets no read timeout of its own for a slow body.
Set `requestReadTimeoutSeconds` on the Netty engine of your app. The demo app sets 10
seconds.

The last argument is `resolveUserId`, a function `(ApplicationCall) -> String?`. Write
your own function. It reads the user id from your own session, never from a cookie that
a client can set on its own. `kit/jvm-core` has a separate interface, `UserIdResolver`.
This route does not take that interface as an argument.

The returned value must follow contract rule C6. It has 1 to 254 characters, never an
email address, a username, or an IP address. A bad value gives status 500, not 400. A
`null` value marks a request with no signed-in user (design decision D19).

The kit reads each setting below from an environment variable.

| Variable | Default | Meaning |
| --- | --- | --- |
| `OCTOMETER_RECORD_ANONYMOUS` | `false` | Turns on the recording of a click with no user id (design decision D19). |
| `OCTOMETER_CLIENT_IP_HEADER` | none | Names the header that holds the client address, behind a proxy that appends it. |
| `OCTOMETER_TRUSTED_PROXY_COUNT` | `1` | Counts the trusted proxy element from the right of that header. |
| `OCTOMETER_ANON_REQ_PER_MIN` | `300` | Limits the anonymous request count of one minute, for one client address. |
| `OCTOMETER_ANON_EVENTS_PER_MIN` | `900` | Limits the anonymous click count of one minute, for one client address. |
| `OCTOMETER_ANON_SESSIONS_PER_MIN` | `120` | Limits the anonymous session-start count of one minute, for one client address. |
| `OCTOMETER_MAX_ANON_EVENTS_PER_DAY` | `20000` | Limits the anonymous event count of one day, for the whole app. |
| `OCTOMETER_ANON_EVENTS_PER_KEY_PER_DAY` | `2000` | Limits the anonymous event count of one day, for one client address. |
| `OCTOMETER_MAX_EVENTS` | `200000` | Caps the stored event count of the app (design decision D21). Above the cap, the store drops each new batch. |
| `OCTOMETER_RETENTION_DAYS` | `30` | Sets the TTL retention of a stored event, in days. |
| `OCTOMETER_PATH_PATTERNS` | none | Names the route pattern list of the app. Section 6 states its form. |

Section 6 states the route pattern list, the tracker option `routes`, and the
warning for an app team.

## 6. The route pattern list

The setting `OCTOMETER_PATH_PATTERNS` gives the kit an ordered list of route
patterns (contract rule C42). A run of whitespace separates each pattern: a
space, a tab, or a line break. Write one pattern for each line, or write the
patterns with a space between them. Never use a comma as the separator. A
comma is a valid character of a pattern under contract rule C39.

The tracker option `routes` (`kit/tracker/README.md`) takes the same list, in
the same order. Give the kit and the tracker the same list. A different list
gives a different stored path for the same click.

> A path can hold an identifier, a token, or a search term. Mark each such
> segment with `:name` in the route list. Never use `*` for a segment that
> holds a token, an email address, or a user id.

**A path outside the list.** The kit stores the literal `/other` for a path
that matches no pattern. It also stores `/other` for a path with an empty
segment, a `.` segment, a `..` segment, or a bad `*` segment (contract rule
C42). The kit never drops the `path` field for that case.

**A bad `routes` value in the tracker.** One bad entry stops the whole list.
A dropped entry would move a later pattern into its place, and that would
change the match order. The tracker then sends no `path` field for the whole
list. It writes one console warning, and the warning names the index of each
bad entry. A `routes` value that is not an array gives the same result: no
`path` field, with one console warning. An empty list also gives that same
result.

Without `OCTOMETER_PATH_PATTERNS`, the kit stores no `path` field, and it
writes one warning at the start.

## 7. The account deletion

Call `deleteByUserId(userId)` on your store to erase one user (contract rule C43). Never
pass a `null` value or an empty text as `userId`.

The contract of the call:

- It deletes each event of the user id.
- It also deletes each anonymous event (`userId: null`) of a session of that user.
- It runs a maximum of 3 passes, so a session that starts during the call still loses
  its anonymous events.
- Read `DeletionResult.complete()` on the return value. A value of `false` means: call
  `deleteByUserId` again.
- The call never puts a user id or a session id into a log line or an exception message.

**The order for an app team (design decision D15).** Run these three steps in order:

1. Call `deleteByUserId` in your app.
2. Wait for one full poll cycle of the monitor.
3. Call the erasure route of the monitor: `DELETE /api/apps/{appId}/events?userId=<id>`.

A call to the monitor route before step 2 finishes lets a poll cycle read the erased
events again.

## 8. The tracker

Install the tracker from the tarball of a GitHub release. The release `0.1.0` holds no
tarball. A later tag brings the first one (issue #44).

```
npm install https://github.com/xamcross/octometer/releases/download/<version>/octometer-tracker-<version>.tgz
```

Replace `<version>` with the tag of that later release.

Add the tracker to your page script, for example `main.ts`. Call `start()` only after
the consent signal of your app. `docs/privacy.md` holds the approved privacy notice
text. The legal basis of its section 6 waits for issue #41.

```ts
import { createTracker } from 'octometer-tracker';

const tracker = createTracker({
  endpoint: '/api/octometer/v1/clicks',
});

// After the consent signal:
tracker.start();
```

Mark each clickable element with `data-octo="<name>"`. The name has 1 to 100 characters,
with the pattern `[A-Za-z0-9_.:-]+` (contract rule C4). The prefix `octo:` stays
reserved for the contract. Give each `data-octo` value a name outside that prefix
(contract rule C38).

## 9. The check

Sign in to your app in a browser first. Copy the value of your session cookie from the
browser developer tools.

Send this request through the public origin of your app, the same origin the tracker
uses. Send your session cookie with the request. `resolveUserId` then returns your user
id, not `null`.

```
curl -i -X POST "https://your-app.example/api/octometer/v1/clicks" \
  -A "Mozilla/5.0 (integration check)" \
  -H "Content-Type: application/json" \
  -H "Cookie: <your session cookie name>=<your session cookie value>" \
  --data-binary '{"sessionId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","clicks":[{"element":"checkout.save","ageMs":1200}]}'
```

The kit drops a batch of a known robot user agent with status 204 (design decision
D43). The default user agent of `curl` is such a value, thus the command above sets
its own.

The answer has status `204`, with an empty body (contract rule C19). A request with no
session cookie also gives status `204`, but it stores nothing (design decision D19).

Read the event back through the monitor. Read the app row of the monitor API, or open
the level 1 view of the monitor UI. The new click raises the click count of your app
within the poll interval of the monitor mode.

## 10. The integration issues of an app

This section holds two issue templates. Each filed issue belongs to the app
repository, not to this repository. Copy the fenced block of one template into a
file. Run `gh label list --repo <your app repository>` first. Create a missing label
with `gh label create <name> --description "<the meaning>" --color <hex>`.

**The agent template.** The title has the imperative form "Integrate Octometer into
`<app>`". Add the label `enhancement`. File the issue with this command:

```
gh issue create --repo <your app repository> --title "Integrate Octometer into <app>" --label enhancement --body-file <file>
```

```markdown
**Goal.** `<app>` sends its clicks to Octometer, and it shows the privacy text.

**The guide.** https://github.com/xamcross/octometer/blob/main/docs/integration-ktor.md

**Implementation steps.**
1. Add the dependency of section 1: the JitPack repository with `exclusiveContent`,
   plus the coordinates `octometer-kit-ktor` and `octometer-kit-mongo`.
2. Add the driver client of section 2: `mongodb-driver-sync`, a connection pool of
   `maxPoolSize=5`, a socket read timeout, against the database `<database>`.
3. Mount the route of section 5, with a `resolveUserId` lambda. The lambda reads the
   user id from your own session.
4. Add the account deletion call of section 7: `deleteByUserId(userId)`.
5. Install the tracker of section 8, and call `start()`. The `.tgz` asset needs a
   release tag after `0.1.0`. The moment of the `start()` call follows the decision
   of `xamcross/octometer#41` (open).
6. Show the text of `xamcross/octometer#42`
   (https://github.com/xamcross/octometer/blob/main/docs/privacy.md) where `<app>`
   shows its notices. The legal-basis section stays a placeholder until
   `xamcross/octometer#41` closes.

**Acceptance criteria.**
- [ ] The dependency of `<app>` uses `octometer-kit-ktor` and `octometer-kit-mongo`
  (section 1).
- [ ] The MongoDB client sets a socket read timeout and the write concern `wtimeout`
  (section 2).
- [ ] The Netty engine of `<app>` sets `requestReadTimeoutSeconds` (section 5).
- [ ] Each environment variable of section 5 has its own value, or it keeps the
  default value.
- [ ] Each clickable element of `<app>` holds a `data-octo` name of contract rule C4.
- [ ] The check of section 9 gives the status `204` for the POST with your session
  cookie.
- [ ] The test command of `<app>` passes.
- [ ] A comment on this issue records the response status and the environment
  variable values.

**Related.**
- `xamcross/octometer#42` holds the owner's approval of the privacy text.
- `xamcross/octometer#41` holds the open decision on the legal basis and the consent
  method.

**Source.** `xamcross/octometer`; sections 1, 2, 5, 7, 8, and 9 of
https://github.com/xamcross/octometer/blob/main/docs/integration-ktor.md;
https://github.com/xamcross/octometer/blob/main/docs/privacy.md.
```

**The mixed template.** The title has the imperative form "Put `<app>` on the
Octometer monitor (no cost, about 60 minutes)". Add the labels `enhancement` and
`owner-only`. File the issue with this command:

```
gh issue create --repo <your app repository> --title "Put <app> on the Octometer monitor (no cost, about 60 minutes)" --label enhancement --label owner-only --body-file <file>
```

```markdown
**Goal.** The owner adds `<app>` to the Octometer monitor, and an agent confirms the
first numbers.

**The guide.** https://github.com/xamcross/octometer/blob/main/docs/integration-ktor.md

**Owner steps.**
1. Create the database role and the read-only user of section 3, with the Atlas CLI
   commands (`<project id>`, `<database>`).
2. Extract `octometer-monitor-<monitor version>.zip` of the GitHub Release. Run
   `bin\backend.bat` from the extracted folder. The distribution starts in the prod
   mode by default.
3. Register `<app>` on `/manage`, with the connection string form of section 3:
   `mongodb+srv://octometer-reader:<password>@<cluster host>/<database>`.
4. Create the three Atlas alerts of section 4: connections, network, and storage.
5. Deploy `<app>`.

**Implementation steps.**
1. After the deployment of owner step 5, run the check of section 9. Send the POST
   request with your session cookie and the user agent line.
2. Confirm the response status is `204`.
3. Read the click count of `<app>` in the level 1 view of the monitor.
4. Confirm the click count rises within the poll interval of the monitor.
5. Write a comment on this issue with the response status and the click count.

**Acceptance criteria.**
- [ ] The database role of section 3 holds only the `find` action on
  `octometer_events`.
- [ ] `<app>` is registered on `/manage`, with its connection string.
- [ ] Each of the three Atlas alerts of section 4 exists for `<app>`.
- [ ] The check of section 9 gives the status `204`.
- [ ] The click count of `<app>` rises in the monitor within the poll interval.
- [ ] A comment on this issue records the response status and the click count.

**Related.**
- Release `<monitor version>` of xamcross/octometer is out; D26: upgrade the monitor
  before an app.
- `xamcross/octometer#205`: Decide the reader rule for a raw path value from a
  hand-written document.
- `xamcross/octometer#42`: Approve the privacy notice text.
- `xamcross/octometer#41`: Decide the legal basis and the consent method for click
  tracking.
- `xamcross/octometer#30`: Check the privileges of the database user.
- Close each one before the production start.

**Source.** `xamcross/octometer`; sections 3, 4, and 9 of
https://github.com/xamcross/octometer/blob/main/docs/integration-ktor.md;
https://github.com/xamcross/octometer/blob/main/docs/demo.md, lines 85-87; design
decision D26; the GitHub Release page of `xamcross/octometer`.
```

<!--
Sources:
kit/jvm-ktor/README.md
kit/jvm-core/README.md
kit/jvm-mongo/README.md
kit/tracker/README.md
tools/demo-app/README.md
docs/demo.md
contract/README.md
contract/fixtures/README.md
.github/workflows/release.yml
tools/consumer-smoke/spring41/build.gradle.kts
tools/consumer-smoke/spring34-maven/pom.xml
CHANGELOG.md
kit/jvm-ktor/src/main/kotlin/octometer/kit/ktor/IngestRoute.kt
kit/jvm-core/src/main/java/octometer/kit/core/store/EventLogStore.java
kit/jvm-mongo/src/main/java/octometer/kit/mongo/store/MongoEventLogStore.java
tools/demo-app/src/main/kotlin/octometer/demo/Application.kt
tools/demo-app/src/main/kotlin/octometer/demo/DemoUserIdResolver.kt
docs/superpowers/specs/2026-09-21-octometer-design.md, sections 4.4 and 8, decisions D9,
  D11, D21
The exclusiveContent block of section 1 is new text of this guide. Design decision D25
states the rule. No file on main holds this exact block.
Section 3 (the database user) and section 4 (the alerts) are new text of this guide, for
  issue #45.
Section 3 cites contract/fixtures/README.md ("check-privileges.js") and issue #30 (open,
  "Check the privileges of the database user").
Section 4, connections and network alert flags:
  https://www.mongodb.com/docs/atlas/cli/current/command/atlas-alerts-settings-create/,
  read 2026-09-26.
Section 4, the metric names and the free-cluster metric list:
  https://www.mongodb.com/docs/atlas/reference/alert-conditions/, read 2026-09-26. The
  page states: "Free clusters and Flex clusters only trigger alerts related to the
  metrics supported by those clusters."
Section 4, the storage alert metric name and its unit:
  https://www.mongodb.com/docs/atlas/reference/alert-host-metrics/, read 2026-09-26.
  The page holds `DB_DATA_SIZE_TOTAL`, not `DB_DATA_SIZE`, and states that this metric
  counts the document data of each database in bytes.
Section 6 is new text of this guide, for issue #119. It cites contract rules C39 and
  C42, kit/jvm-ktor/README.md ("OCTOMETER_PATH_PATTERNS"), kit/tracker/README.md (the
  option `routes`, and the bad-value rule near line 229), and
  kit/jvm-core/src/main/java/octometer/kit/core/ingest/IngestSettings.java (the
  whitespace separator, near line 189). The quoted warning is the text of issue #119's
  acceptance criteria.
Section 8, the link sentence to `docs/privacy.md`: updated for issue #42 (the owner
  approval of 2026-09-27) to name the approved text and the open legal basis of
  issue #41.
Section 10 is new text of this guide, for issue #48. It cites the skill
  `managing-github-issues` (title form, body order, label rules, `gh issue create`
  form) and docs/superpowers/specs/2026-09-21-octometer-design.md, sections 4.4 and 8,
  decisions D25 and D26.
Section 10, the agent template: docs/privacy.md and the owner's approval comment on
  xamcross/octometer#42 (2026-09-27); the parked consent decision on
  xamcross/octometer#41 (the owner's comment on issue #45, 2026-09-21).
Section 10, the mixed template: docs/demo.md, lines 85-87 (the prod mode default of the
  distribution form of issue #38); the release artifact name and the D26 rule of
  .github/workflows/release.yml. The open `production-blocker` issue list
  (xamcross/octometer#205, #42, #41, #30) comes from `gh issue list --repo
  xamcross/octometer --label production-blocker --state open`, read 2026-09-27.
-->
