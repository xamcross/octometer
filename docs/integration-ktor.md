# The Ktor integration guide

This guide connects a Ktor app to Octometer. A developer follows this guide alone, with
no read of the design document. The guide names no real app and no real organisation.
Where this guide says "your app", put the name of your own app.

**A note for an app behind a path-limited proxy.** Some apps sit behind a proxy. The
proxy forwards only a few path prefixes to the backend, for example an API prefix, an
OAuth prefix, and a login-callback prefix. The app also sets a Content Security Policy
with `connect-src 'self'`. Put the ingest path of step 3 below the proxied prefix. The
tracker of step 5 then posts to the same origin as the page. The proxy forwards the
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

## 3. The route

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

This guide leaves out the route pattern list and the warning of rule R7. Issue #119
adds that section to this same file.

## 4. The account deletion

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

## 5. The tracker

Install the tracker from the tarball of a GitHub release. The release `0.1.0` holds no
tarball. A later tag brings the first one (issue #44).

```
npm install https://github.com/xamcross/octometer/releases/download/<version>/octometer-tracker-<version>.tgz
```

Replace `<version>` with the tag of that later release.

Add the tracker to your page script, for example `main.ts`. Call `start()` only after
the consent signal of your app.

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

## 6. The check

Sign in to your app in a browser first. Copy the value of your session cookie from the
browser developer tools.

Send this request through the public origin of your app, the same origin the tracker
uses. Send your session cookie with the request. `resolveUserId` then returns your user
id, not `null`.

```
curl -i -X POST "https://your-app.example/api/octometer/v1/clicks" \
  -H "Content-Type: application/json" \
  -H "Cookie: <your session cookie name>=<your session cookie value>" \
  --data-binary '{"sessionId":"3fa85f64-5717-4562-b3fc-2c963f66afa6","clicks":[{"element":"checkout.save","ageMs":1200}]}'
```

The answer has status `204`, with an empty body (contract rule C19). A request with no
session cookie also gives status `204`, but it stores nothing (design decision D19).

Read the event back through the monitor. Read the app row of the monitor API, or open
the level 1 view of the monitor UI. The new click raises the click count of your app
within the poll interval of the monitor mode.

<!--
Sources:
kit/jvm-ktor/README.md
kit/jvm-core/README.md
kit/jvm-mongo/README.md
kit/tracker/README.md
tools/demo-app/README.md
docs/demo.md
contract/README.md
.github/workflows/release.yml
tools/consumer-smoke/spring41/build.gradle.kts
tools/consumer-smoke/spring34-maven/pom.xml
CHANGELOG.md
kit/jvm-ktor/src/main/kotlin/octometer/kit/ktor/IngestRoute.kt
kit/jvm-core/src/main/java/octometer/kit/core/store/EventLogStore.java
kit/jvm-mongo/src/main/java/octometer/kit/mongo/store/MongoEventLogStore.java
tools/demo-app/src/main/kotlin/octometer/demo/Application.kt
tools/demo-app/src/main/kotlin/octometer/demo/DemoUserIdResolver.kt
The exclusiveContent block of section 1 is new text of this guide. Design decision D25
states the rule. No file on main holds this exact block.
-->
