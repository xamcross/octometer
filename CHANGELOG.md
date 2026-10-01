# Changelog

All notable changes to this project sit in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
The version number follows the tag form `X.Y.Z`, with no prefix.

## Unreleased

### Site

- Scaffold the public site: the Vite build, the hero section of the mockup, the
  Playwright harness, the CI job `site`, and the host document. (#287)
- Deploy the site as a Cloudflare Worker with static assets, not as a Pages project;
  Pages is now the legacy path. (#287)
- Add the wave animation of the mark and the focus move into the waitlist email field
  on a click of "Join the waitlist"; enable the Worker preview URL. (#288)

### Monitor

- Scaffold the hosted service: the Docker image, `fly.toml`, the `/healthz` route, the D56
  fields of `GET /api/health`, the public bind and the temporary admin guard, and the CI
  deploy job. (#295)
- Add sign-in with a mail link: the accounts, the invite list, the session cookie, the
  Resend and the file mail senders, the account filter of every API route, and the
  launcher command `--invite`. Remove the temporary admin guard of #295. (#301)

### CI

- Give the secret scan job the `pull-requests: read` permission, so it passes on the
  private repository. (#308)
- Remove the Windows job and the weekly run, so the private repository stays inside the
  Free plan minutes. (#306)

### Documentation

- Record the hosted model (D50 to D58) in the design document, with the closure notes, the
  change notes, and the milestone I backlog. (#294)
- State that the repository keeps all rights: the `LICENSE` file, the `license` field of
  `kit/tracker/package.json`, and the licence block of each kit module POM. (#203)

## 0.3.0 - 2026-09-28

### Tests

- Measure the JVM line coverage and the browser-suite line coverage, with the JaCoCo plugin on the six JVM modules, the V8 coverage of the frontend, and `docs/test-coverage.md`. (#176)
- Add the Playwright end-to-end suite, against the built distribution and the demo app. (#175)

### Contract

- Add rule C44 for the config document of the app's route list, amend rules C42, C35, and C27, and widen the database role to the collection `octometer_config`. (#276)
- Amend rules C41 and C42 for version 1.3: the tracker sends the raw page path, and the kit alone matches it; an invalid path value gives `/other`, never a dropped field. (#278)

### Kit JVM

- Publish the derived route pattern list into `octometer_config`, at each start, on the app's own database user. (#276)
- Derive the route pattern list from the routes of the app, in the Ktor adapter and the Spring adapter; drop the typed list of the demo app. (#275)
- Add the design decisions D46 to D49 for the route list from the app. (#275)

### Tracker

- Name the tarball asset of the release `0.2.0` in the Ktor guide and the tracker README. (#229)
- Send the raw page path with no `routes` option of its own; the kit alone matches it. (#278)

### Monitor

- Read the app's own published route list at each poll cycle, cache it in the app row, and show it read-only in the manage view; drop the typed route pattern field of the add form and the edit form. (#277)
- Derive the database name from the path of the connection string, and remove the Database field from the add form. (#272)
- Show the app name in the breadcrumb as the link to its users list, and drop the "Users" item. (#270)
- Keep a margin of 16 px between the refresh line and the table panel of each data view. (#271)
- Mark the invalid field of the add form and the edit form with a dashed outline under forced colors. (#266)
- State the one-reviewer rule in the README, with the role match and the 2026-09-27 change date. (#268)
- Remove the button press motion under prefers-reduced-motion, and raise the invalid field ring to 4.5:1 on white. (#263, #264)
- Record the accessibility verification of the cartoon look, with the measured contrast table and the contrast script. (#251)
- Write the component design document of the monitor UI. (#260)
- Show the mascot with one sentence in each empty view, on the first load, and on the not-found page. (#250)
- Put each data table of the five views in a panel, with a status badge and tabular numbers. (#249)
- Restyle the buttons, the form fields, and the delete dialog of the manage view with the cartoon look. (#248)
- Restyle the header band, the nav pills, the pause button, the skip link, the banner, and the breadcrumb; add the mascot mark. (#247)
- Add the design tokens, the two fonts, and the base stylesheet of the cartoon look. (#246)
- Give the collection field of the add-app request the default `octometer_events`, and remove the field from the add form. (#244)
- Show a hint with an example under each field of the add form and the edit form. (#245)
- Add the per-app route pattern list, the path allow-list of the reader, with the API field, the manage form, and the guides. (#226)
- Lower the query time of the level 1 app totals and the anonymous sessions statement, at 1 million events. (#237)
- Write the run guide, five sections complete; the upgrade step waits for issue #56. (#64)
- Add the per-app cap on the distinct path values, with a shape check and an erasure rewrite. (#205)
- Add the "Anonymous sessions" view, with a link from a first-page row and from the anonymous row of the user list. (#115)
- Show the retention time above the app table of the level 1 view. (#66)
- Return `null` for a missing path in the first-pages and the sessions answers; the views show `(unknown)`. (#213)
- Add a rolling file log, next to the console log, with a 10 MB file limit, a 14-day
  history, and a 200 MB total cap. (#57)
- Record a gap after a long off-time, and show it in the level 1 view. (#62)
- Purge a gap row whose `to_ts` is older than the retention time. (#227)
- Add the perf test of the three levels, at 1 million and 10 million events. (#63)
- Refuse a corrupt database, and a second monitor instance, with a clear log line and exit code 2; map a locked or a full database to a fixed `last_error` text; add `databaseBytes` and `freeDiskBytes` to `GET /api/health`. (#58)

### Documentation

- Add the "Upgrade to kit 0.3.0" section to the Ktor guide and the Spring guide, with the role widen command, the dependency bump, and the route list steps. (#279)
- State the route list case of the monitor upgrade order, the cache sentence, and the read-only manage view in the run guide. (#279)
- State the raw-path sentence in the privacy notice, where it names the route list. (#279)

## 0.2.0 - 2026-09-27

### Contract

### Kit JVM

- Write the Ktor integration guide. (#40)
- Add the Atlas setup and a draft privacy notice to the Ktor guide. (#45)
- Add the two issue templates for an app repository to the Ktor guide. (#48)
- Move the ingest check order into one processor and add kit/jvm-spring. (#69)
- Write the Spring Boot integration guide. (#70)
- Add the route pattern list, the pilot settings, and the first-page privacy text. (#119)

### Tracker

### Monitor

- Detect a resume from sleep and wait 15 s before the next poll. (#29)
- Check the privileges of the database user before each read. (#30)
- Keep each connection string out of the log and add a plain request line. (#31)
- Mark the privacy notice text as approved and proof-check each fact. (#42)
- Attach the tracker package to each GitHub Release. (#44)
- Request the JitPack POM of octometer-kit-spring after a release. (#71)
- Add the first-pages route of level 3. (#112)
- Add the anonymous sessions route and the sessionId filter of level 3. (#113)
- Show the first pages of an app. (#114)
- Reset the poll state on a PATCH to a different connection string. (#187)
- Store a referrerHost outside the set as NULL and keep the row. (#196)

## 0.1.0 - 2026-09-26

### Contract

- Add the event log contract, version 1. (#8)
- Remove the cluster host name from the output of check-privileges.js. (#76)
- Correct the time relation of `_id` and `ts` in contract rule C2. (#77)
- Correct the text defects of contract/README.md. (#78)

### Kit JVM

- Add the ingest logic of kit/jvm-core. (#10)
- Add the MongoDB event store in kit/jvm-mongo. (#11)
- Add the Ktor ingest route in kit/jvm-ktor. (#12)
- Add the store interface and the in-memory store to kit/jvm-core. (#26)
- Add the anonymous per-minute limits and the proxy rule to the kit. (#116)
- Add the daily anonymous cap and the user-agent filter to the kit. (#117)

### Tracker

- Add the click tracker core. (#13)
- Add the demo app with synthetic clicks. (#14)
- Add the referrer source on the session start event. (#108)

### Monitor

- Confirm the read-only database user on a real Atlas M0 cluster. (#1)
- Create the git repository with the first commit. (#2)
- Add the Gradle build with the Ktor monitor server. (#3)
- Load the monitor config with a fixed precedence. (#4)
- Reject a request with a wrong Host or Origin header. (#5)
- Add the Angular frontend with the dev proxy. (#6)
- Add the CI workflow for each module. (#7)
- Add the SQLite store with versioned migrations. (#9)
- Add the app registry API. (#15)
- Read the new events of one app from MongoDB. (#16)
- Schedule the poll of each app on the mode interval. (#17)
- Serve the level 1 totals. (#18)
- Add the UI shell. (#19)
- Show the app table on the level 1 view. (#20)
- Add the end-to-end test from a click to the level 1 totals. (#21)
- Add the secret scan to the repository. (#22)
- Add the Dependabot config. (#23)
- Show the demo clicks on the level 1 view. (#24)
- Add the poll store of the frontend. (#25)
- Skip an invalid document and set the status of the poll cycle. (#27)
- Set the poll status and the backoff after a failure. (#28)
- Add the release workflow. (#39)
- Show the element table on the level 3 view. (#53)
- Add the two consumer smoke projects. (#68)
- Add a .gitattributes file for stable line ends. (#72)
- Upgrade the required Node version to 24.15.0 or newer. (#80)
- Show the refresh bar before each table. (#92)
- Fix the flaky test pause-refresh-integration.spec.ts. (#100)
- Copy the `path` and `referrerHost` fields and set the `kind` field in the reader. (#110)
- Give each Gradle test dependency a catalog entry for Dependabot. (#138)
- Remove a leftover apps-*.json.tmp file at the start of the monitor. (#141)
- Remove each temporary test folder of the monitor backend after a run. (#142)
- Answer 400, not 500, for a bad percent escape in a query string. (#148)
- Stop the TRACE log line of StatusPages that holds the query string. (#150)
- Show a later failed data poll in the shell banner. (#164)
- Remove each dead entry from the secret-scan allow-list. (#171)
- Run only the tests of the changed modules in CI and on the owner machine. (#177)
- Add minor_issues.md with the open MINOR findings of the reviews. (#186)
- Add the visit rule to the end-to-end test. (#188)
- Store the MINOR review rows in one file for each pull request. (#202)
