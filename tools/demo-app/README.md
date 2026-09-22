# tools/demo-app

The demo app of Octometer (issue #14). It writes tracker clicks and
synthetic clicks into a local MongoDB. It uses the ingest route of
`kit/jvm-ktor` and the MongoDB store of `kit/jvm-mongo`, through a
project dependency of this build.

## Before you build

`./gradlew build` needs Node 24 and npm on the PATH for the demo app
(Ktor review MAJOR 3). The file `kit/tracker/.nvmrc` names the exact
Node version. Install Node 24. Add npm to the PATH. The task
`npmCiTracker` checks the PATH, and it fails with a clear message when
npm is absent.

## Start the database

```
docker compose -f tools/demo-app/docker-compose.yml up -d
```

This command starts `mongo:8.0` on the loopback address `127.0.0.1`,
port 27017. The repository holds no real connection string. The demo
app reads the URI from the environment variable
`OCTOMETER_DEMO_MONGO_URI`. The default is
`mongodb://127.0.0.1:27017`, the loopback address of this compose file.
The database name is `exampledb` by default; set
`OCTOMETER_DEMO_DATABASE` for a different name.

Set `OCTOMETER_DEMO_MONGO_PORT` before the compose command when port
27017 already holds a different MongoDB server on this machine. Then
give the same port in `OCTOMETER_DEMO_MONGO_URI` of the app.

## Start the app

```
./gradlew :tools:demo-app:run
```

The app binds to `127.0.0.1`, port 8098 by default. Set
`OCTOMETER_DEMO_PORT` for a different port. Open
`http://127.0.0.1:8098/` in a browser.

The page holds a select box with three demo user ids. Select one first.
The select box sets the cookie `demo_user`. **The demo `UserIdResolver`
reads this cookie. A request with no cookie gives no user id, and the
ingest route then drops the click (design decision D19).** Click a
button only after you select a user, or the click leaves no document.

The demo app trusts a client cookie for the user id. A real app must
not do this. A real app takes the user id from its own session or from
its authentication context (contract rule C16). A client sets a cookie
itself, thus a client can claim any user id.

The page loads the built tracker of `kit/tracker` from `/tracker/`. The
build task `copyTrackerDist` copies the built files there; see
`build.gradle.kts` for the three build steps (`npmCiTracker`,
`buildTracker`, `copyTrackerDist`). The tracker option `routes` holds
the one path of this page, `/`, so each click carries a `path` field.
The tracker option `flushIntervalMs` is `1000`.

This page sends no `Content-Security-Policy` header. The page serves a
loopback address only, and it holds one inline module script. A real
app must send a CSP; design decision D12 states the policy of the
monitor.

## Write synthetic clicks

```
./gradlew :tools:demo-app:run --args="--generate"
```

This command writes synthetic clicks through the store directly, not
through HTTP. It writes a minimum of 100 events for 5 demo user ids
(`amy`, `ben`, `cleo`, `dax`, `eve`). Each session holds one
`octo:session-start` event and 5 click events, each with a `path`. Each
session start time sits inside the last 24 hours.

The generator passes no HTTP route, thus it passes no rate limit of
design decision D20 and no 16 KB body limit of contract rule C18. Both
guards belong to the route.

## The worst dev delay

The monitor of the full Octometer stack polls each app in dev mode. The
worst delay from one click to a number on the level 1 view is the sum
of four parts:

| Part | Delay |
| --- | --- |
| The tracker flush interval of this demo app | 1 s |
| The reader settle lag in dev mode (design decision D2, C22) | 2 s |
| The poll interval in dev mode (design decision D6) | 5 s |
| The UI refresh interval of the frontend (design decision D29) | 5 s |
| **Total** | **13 s** |

## Tests

```
./gradlew :tools:demo-app:test
```

A container test needs Docker. The annotation is
`@Testcontainers(disabledWithoutDocker = true)`. The test skips with no
failure on a developer machine with no Docker. A test of the resolver
and of the static page needs no Docker.

## Stop the database

```
docker compose -f tools/demo-app/docker-compose.yml down -v
```

This command also removes the data volume, so the next start begins
with an empty database.
