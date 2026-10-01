# Octometer

Octometer is a monitoring tool. Each app sends click events to a monitor
server. The monitor stores the events and shows the totals in a browser
view.

All rights reserved: the repository grants no licence; see `LICENSE`.

Read `App Description.md` for the product description. Read
`docs/superpowers/specs/2026-09-21-octometer-design.md` for the design
decisions and the contract rules.

Read `minor_issues.md` for the rule. Read `minor_issues/<PR>.md` for each
open MINOR finding of pull request `<PR>`. The owner decided on 2026-09-22
that each row stays in the store, also after the production release. A
later fix sets the status of the row to `fixed in #N`. No pull request
fixes a MINOR finding of its own review.

Each pull request gets one review by one reviewer. The reviewer has the
role or the roles that the change needs, for example an Angular and
accessibility engineer, or a Kotlin and SQLite engineer. The owner
decided this on 2026-09-27; before that date a code change had two
reviewers. A brief review before the implementer starts and a
confirmation of a fix by the same reviewer stay.

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

The repository is private since 2026-09-28. The Free plan gives 2 000
Actions minutes each month, so the Windows job and the weekly run left
the workflow (#306).

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

## Coverage

Each of the six JVM modules (`monitor/backend`, `kit/jvm-core`,
`kit/jvm-ktor`, `kit/jvm-mongo`, `kit/jvm-spring`, `tools/demo-app`) applies
the `jacoco` plugin always. The agent stays off by default, so a plain
`./gradlew build` keeps its present speed and its present cache key. The
report task `jacocoTestReport` gives the jacoco plugin no dependency on
`test` (the Gradle manual, "The JaCoCo Plugin"), so the real command is:

```
./gradlew test jacocoTestReport -Pcoverage=true
```

`./gradlew test jacocoAggregate -Pcoverage=true` merges the six module
reports into one XML report and one HTML report, under
`build/reports/jacoco/aggregate/`.

The task `:monitor:backend:jacocoAgentArgs` prints the JaCoCo agent line
of a run of the distribution and of the demo app, plus the JaCoCo CLI jar
path, for the end-to-end suite (`e2e/`). Read `docs/test-coverage.md` for
the measured numbers, the run commands, and the table of the design
decisions that the browser suite reaches.

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

A run with the JaCoCo agent exports two environment variables first
(`E2E_JAVA_OPTS_MONITOR`, `E2E_JAVA_OPTS_DEMO`, from
`:monitor:backend:jacocoAgentArgs`) and builds the distribution with a
source map for the frontend coverage report:

```
./gradlew :monitor:backend:distZip :tools:demo-app:installDist -PfrontendSourceMap=true
./gradlew -q :monitor:backend:jacocoAgentArgs
# export each of the three printed lines, then:
cd e2e && npm test
node scripts/coverage-report.mjs
cd .. && ./gradlew :monitor:backend:jacocoE2eReport
```

The JVM coverage of the run sits under
`monitor/backend/build/reports/jacoco/e2e/`; the frontend coverage sits
under `e2e/.run/coverage/frontend-lcov.info` and
`e2e/.run/coverage/frontend-coverage-summary.txt`. Read
`docs/test-coverage.md` for the measured numbers.
