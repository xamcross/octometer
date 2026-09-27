# Octometer

Octometer is a monitoring tool. Each app sends click events to a monitor
server. The monitor stores the events and shows the totals in a browser
view.

Read `App Description.md` for the product description. Read
`docs/superpowers/specs/2026-09-21-octometer-design.md` for the design
decisions and the contract rules.

Read `minor_issues.md` for the rule. Read `minor_issues/<PR>.md` for each
open MINOR finding of pull request `<PR>`. The owner decided on 2026-09-22
that each row stays in the store, also after the production release. A
later fix sets the status of the row to `fixed in #N`. No pull request
fixes a MINOR finding of its own review.

`./gradlew build` needs Node 24 and npm on the PATH for the demo app
(Ktor review MAJOR 3, pull request #168). Read
`tools/demo-app/README.md` for the setup steps.

Read `docs/demo.md` for the full demo. It gives the start commands and
one real run, from a click on the demo page to a number on level 1.

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
- The end-to-end suite has no changed-only script. Run `cd e2e && npm
  test`. It always runs the whole suite.

CI still runs the whole suite of each changed module.

## End-to-end tests

The folder `e2e/` holds one Playwright suite. It drives a real Chromium
browser against the built distribution of the monitor and the demo app,
from a click on the demo page to a number on the level 1 view.

Build the two Gradle outputs first, from the repository root:

```
./gradlew.bat :monitor:backend:distZip :tools:demo-app:installDist
```

Run the suite, from `e2e/`:

```
cd e2e
npm ci
npx playwright install chromium
npm test
```

The suite needs Docker and Node 24 (`e2e/.nvmrc` names the exact
version). `npm test` starts one MongoDB container, one demo app process,
and one monitor process, each on a free port; it stops each one at the
end, also after a failed run. The measured run time of the suite, on the
owner machine, is about 33 seconds; the CI job `e2e` keeps its own
report as an artifact and has a 15-minute bound. Read the Playwright
report with `npm run report` inside `e2e/`.

The local cache folder has no integrity control. Every process of the
owner account can write it. Run the last check before a push with
`./gradlew build --no-build-cache`. This command ignores a crafted or
a stale cache entry.
