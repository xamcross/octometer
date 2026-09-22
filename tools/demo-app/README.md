# tools/demo-app

The demo app of Octometer (issue #14). It writes tracker clicks and
synthetic clicks into a local MongoDB. It uses the ingest route of
`kit/jvm-ktor` and the MongoDB store of `kit/jvm-mongo`, through a
project dependency of this build.

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
27017 already holds a different MongoDB server on this machine, then
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

The page loads the built tracker of `kit/tracker` from `/tracker/`. The
build task `copyTrackerDist` copies the built files there; see
`build.gradle.kts` for the three build steps (`npmCiTracker`,
`buildTracker`, `copyTrackerDist`). The tracker option `routes` holds
the one path of this page, `/`, so each click carries a `path` field.
The tracker option `flushIntervalMs` is `1000`.

## Write synthetic clicks

```
./gradlew :tools:demo-app:run --args="--generate"
```

This command writes synthetic clicks through the store directly, not
through HTTP. It writes a minimum of 100 events for 5 demo user ids
(`amy`, `ben`, `cleo`, `dax`, `eve`). Each session holds one
`octo:session-start` event and 5 click events, each with a `path`. Each
session start time sits inside the last 24 hours.

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

A container test needs Docker (`@Testcontainers(disabledWithoutDocker =
true)`); it skips with no failure on a machine with no Docker, for
example the Windows CI job of this repository. A test of the resolver
and of the static page needs no Docker.

## Stop the database

```
docker compose -f tools/demo-app/docker-compose.yml down -v
```

This command also removes the data volume, so the next start begins
with an empty database.
