# Changelog

All notable changes to this project sit in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
The version number follows the tag form `X.Y.Z`, with no prefix.

## Unreleased

### Contract

### Kit JVM

### Tracker

### Monitor

- Add the "Anonymous sessions" view, with a link from the user list and from the element view. (#115)

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
