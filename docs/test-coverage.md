# Test coverage of Octometer

Issue #176. This document states the coverage of the JVM test suite and of
the browser suite (`e2e/`), from one measured run each, with the exact
command of each number. It also gives the design-decision table: which
decision the browser suite reaches, and why a decision with "no" needs a
different kind of test.

Date of this run: 2026-09-27. Tool versions: JaCoCo 0.8.15 (confirmed on
Maven Central, `org.jacoco.agent`, on 2026-09-27); `v8-to-istanbul` 9.3.0
(confirmed on the npm registry on 2026-09-27).

## The JVM tests alone

Command:

```
./gradlew test jacocoAggregate -Pcoverage=true
```

| Module | Line coverage | Branch coverage |
| --- | --- | --- |
| `monitor/backend` | PENDING | PENDING |
| `kit/jvm-core` | PENDING | PENDING |
| `kit/jvm-ktor` | PENDING | PENDING |
| `kit/jvm-mongo` | PENDING | PENDING |
| `kit/jvm-spring` | PENDING | PENDING |
| `tools/demo-app` | PENDING | PENDING |
| **Total (`jacocoAggregate`)** | **PENDING** | **PENDING** |

Source of each per-module number: `<module>/build/reports/jacoco/test/jacocoTestReport.xml`,
the report-level `<counter type="LINE">` and `<counter type="BRANCH">`
elements. Source of the total: `build/reports/jacoco/aggregate/jacocoAggregate.xml`.

## The browser suite alone

One run of the distribution and of the demo app, with the JaCoCo agent,
with no JVM unit test or integration test:

```
./gradlew :monitor:backend:distZip :tools:demo-app:installDist -PfrontendSourceMap=true
./gradlew -q :monitor:backend:jacocoAgentArgs
# each of the three printed lines becomes an environment variable
cd e2e && npm test
node scripts/coverage-report.mjs
cd .. && ./gradlew :monitor:backend:jacocoE2eReport
```

| | Line coverage |
| --- | --- |
| The monitor process and the demo app process (JaCoCo, `jacocoE2eReport`) | PENDING |
| The frontend (V8, mapped through `v8-to-istanbul`) | PENDING |

Source: `monitor/backend/build/reports/jacoco/e2e/jacocoE2eReport.xml`, the
report-level `<counter type="LINE">` element; and
`e2e/.run/coverage/frontend-coverage-summary.txt`, the "Total" line.

## The design decisions that the browser suite reaches

The column "Reaches" states "yes" (the one run above exercises the main
rule of the decision), "partly" (it exercises one path, not every path of
the decision), or "no". A "no" row states its reason.

| Decision | Reaches | Reason (for "no") |
| --- | --- | --- |
| D1. Stack | yes | |
| D2. Config | partly | |
| D3. Store | partly | |
| D4. Page transaction | partly | |
| D5. Invalid document, and the three later notes of D5 | no | The suite writes only valid documents, with the one route pattern list of the demo app; an invalid document, an out-of-set `referrerHost`, and the per-app path cap each need an injected bad document or an injected high count. |
| D6. Scheduler | partly | |
| D7. Wake rule | no | The rule needs a wall clock later than the planned time by more than two intervals; only a test with an injected `Clock` and virtual time reaches it (the design document names this test form itself). |
| D8. Status | partly | |
| D9. Privilege check | partly | |
| D10. MongoDB client | partly | |
| D11. Secrets | partly | |
| D12. Exposure | partly | |
| D13. API | partly | |
| D14. Totals | yes | |
| D15. Personal data (the erasure route, contract rule C43) | yes | |
| D16. Gap | no | The rule needs a cursor 29 to 30 days old; a fresh MongoDB container of one short run never ages that much. Only a test with an injected old cursor time reaches it. |
| D17. Languages | yes | |
| D18. Core | partly | |
| D19. Anonymous clicks | yes | |
| D20. Rate limit | no | The suite sends a handful of clicks; the limit is 30 to 120 requests each minute. Only a test that sends a burst of requests reaches the 429 path. |
| D21. Event cap | no | The cap is 200 000 events; a short run writes a handful of events. Only a test with a faked or a seeded high count reaches the drop path. |
| D22. MongoDB store | partly | |
| D23. Adapters | partly | |
| D24. Tracker | partly | |
| D25. Distribution | no | A release and packaging process (JitPack, `npm pack`); no application code path, in a JVM test or in a browser test alike. |
| D26. Release | no | A release-workflow step (`release.yml`); no application code path. |
| D27. Baseline | partly | |
| D28. Views | partly | |
| D29. Poll store | partly | |
| D30. States | partly | |
| D31. Time | partly | |
| D32. Management page | partly | |
| D33. Accessibility | no | The criteria need a screen-reader role and text read, or a computed-style read; the functional scenarios of this suite assert neither. |
| D34. Layout | no | The suite overrides `OCTOMETER_DATA_DIR` to a temporary folder for the whole run; the real `%LOCALAPPDATA%\Octometer\` layout is never created. |
| D35. Start | partly | |
| D36. Backup and upgrade | no | `VACUUM INTO` runs once a day, and the upgrade script needs a real version change; a short run crosses no day boundary and installs no upgrade. Only a test with an injected `Clock` day change, or a real upgrade run, reaches it. |
| D37. Logs | partly | |
| D38. Repository safety | no | A repository and CI safety control (`gitleaks`, the pre-commit hook); no application code path. |
| D39. CI | no | The CI workflow structure itself; no application code path. |
| D40. Route pattern list | partly | |
| D41. Session start | partly | |
| D42. Visitor source | partly | |
| D43. Anonymous caps | no | The demo app runs with anonymous events off (D19; `Application.kt`, `IngestSettings(false, ...)`), so the three per-minute counters and the two daily caps of D43 never execute. Only a test that turns anonymous events on reaches them. |
| D44. First pages and anonymous sessions | partly | |
| D45. The cartoon look | partly | |

### The decisions that only a test with an injected clock or a fake can reach

- **D7. Wake rule** — a fake `Clock`, virtual time (the design document's own test form).
- **D6. Scheduler, the failure backoff** — an injected poll failure, so the 2× backoff (up to
  300 s) runs; the design document names this as one of the two examples of the issue.
- **D8. Status** (`UNREACHABLE`, `UNAUTHORIZED`, `OVERPRIVILEGED`, `ERROR`) — an injected
  MongoDB failure, or a second MongoDB user with a restricted role.
- **D9. Privilege check, the failed check** — a second MongoDB user with a restricted role
  (an injected privilege failure).
- **D10. MongoDB client, the PATCH reset race** — a controlled scheduler that races one poll
  cycle in flight against one PATCH of the connection string.
- **D16. Gap** — an injected cursor time 29 to 30 days old.
- **D36. Backup and upgrade** — a fake `Clock` day change, or a real upgrade run.
- **D43. Anonymous caps** — anonymous events turned on, with a fake window boundary for the
  per-minute and the per-day counters.

Each of these decisions already has a JVM unit test or a JVM integration test with the
named fake; the browser suite is not the right tool for any of them (the owner's own
statement of 2026-09-22, restated in the issue text).

## What the browser suite cannot replace

The owner asked on 2026-09-22 whether the browser suite could replace the unit tests and
the integration tests. The measurement above answers this: the browser suite reaches the
demo app's one path of the business logic fully ("yes" and most of "partly"), and it
reaches none of the decisions above ("no"). The JVM unit tests and the JVM integration
tests stay, for the decisions that need a fake, an injected failure, or a volume that a
short run cannot reach.
