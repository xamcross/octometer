# Octometer

Octometer is a monitoring tool. Each app sends click events to a monitor
server. The monitor stores the events and shows the totals in a browser
view.

Read `App Description.md` for the product description. Read
`docs/superpowers/specs/2026-09-21-octometer-design.md` for the design
decisions and the contract rules.

Read `minor_issues.md` for each open MINOR finding of a review. The owner
decided on 2026-09-22 that a row stays in that file until a later fix.

`./gradlew build` needs Node 24 and npm on the PATH for the demo app
(Ktor review MAJOR 3, pull request #168). Read
`tools/demo-app/README.md` for the setup steps.

## The build cache and the changed-only test runs

The root `gradle.properties` turns on the Gradle build cache. A `test`
task of an unchanged module reuses its cached result, also across a new
clone (the cache lives in `%USERPROFILE%\.gradle\caches\build-cache-1`).

CI runs only the jobs of a changed path set (`.github/workflows/ci.yml`,
the `changes` job). The job `secrets` always runs. Each doubtful path
sits in the `jvm` filter, on purpose: a run too many costs less than a
missed test.

For a local run of one module before a push, use these commands. They
run one module or one changed file, not the whole suite.

- A JVM module: `./gradlew :monitor:backend:test` or `./gradlew
  :kit:jvm-core:test`.
- The frontend has no changed-only script. Run `npm test` inside
  `monitor/frontend`. It always runs the whole suite.
- The tracker, only the specs that a changed file touches: `npm run
  test:changed` inside `kit/tracker`. It compares the working tree
  against `origin/main` by default.

CI still runs the whole suite of each changed module.

The local cache folder has no integrity control. Every process of the
owner account can write it. Run the last check before a push with
`./gradlew build --no-build-cache`. This command ignores a crafted or
a stale cache entry.
