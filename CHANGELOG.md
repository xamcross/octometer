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
- Add the "How it works" section with the three steps and the scroll-reveal flow
  illustration, and the "Three levels in your dashboard" section with real screenshots
  of the demo monitor; add the screenshot script `site/scripts/screenshots.mjs`. (#289)
- Add the "What we keep" section and the footer notice of the waitlist, with a link
  from the section into the notice. (#290)
- Add the waitlist form: the Worker handler of `POST /api/waitlist`, `GET`/`POST
  /confirm`, and `GET`/`POST /remove`, the D1 migration, and the confirmation mail
  through Resend. (#291)
- Re-capture the three dashboard screenshots of the landing page from the current
  build, and crop each one to its content, with 24 px below the table. (#343)
- Raise the flow figure's sub-label size from 11.5 px to 13 px, so each one sits at or
  above the 12 px floor. (#344)
- Raise the token `--text-s` from 12.8 px to 14 px, so the hero note, the flow caption,
  and both form hints read larger. (#357)
- Pack the users card and the elements card at the top of their grid row, so the
  stretch rule no longer opens a gap above a shorter card's figcaption. (#382)
- Bound the steps column of the "How it works" grid at 36rem. Put the flow diagram
  beside the step text, with a gap of about 50 px at 1280 px. (#355)
- Add a dark color scheme: a dark value for each surface and ink token under
  `prefers-color-scheme: dark`, and `color-scheme: light dark`. (#366)
- Add a top navigation bar with the mark, the wordmark, and a "Sign in" link to
  `app.0ct0meter.com/signin`, above the hero. (#367)
- Give the waitlist section heading and submit button their own labels: "Get an
  invite" and "Send me the link". (#358)
- Name the domain "0ct0meter.com" next to the contact line in the footer, and mark
  the zeros once. (#365)
- Widen the waitlist panel to fill its row at 48rem and above, with the email
  field and the button on one row. (#381)
- Add a product-shot preview to the right side of the hero at 56rem and above, built
  as an HTML table in the visual family of `.frame`, so no empty strip sits past the
  copy. (#372)
- Add a button on each "Three levels" card that opens one dashboard capture at full
  size, with a close control and the Escape key. (#359)
- Put each "Three levels" card on its own row at 1280 px, so the row text of each
  capture reads at 12.8 px or more, with no zoom. (#359, the correction round of pull
  request #424)
- Let the full-size dialog hold the capture at its own natural width, with a pan, so
  the row text reads at 12.8 px at 390 px too. (#359, the correction round of pull
  request #424)
- Rewrite the hero note, the waitlist lead, and the footer labels in plain language.
  Each fact stays the same. (#356)
- Fold the footer notice into a collapsed "Waitlist privacy notice", with a link
  beside the waitlist form. Add one footer row: the mark, the copyright line, the
  contact address, and "Sign in". (#388)
- Run the waitlist end-to-end scenario with reduced motion, so the page scroll makes
  no click of the scenario fail at random. (#426)
- Move the hero mark into the top bar under 48rem, beside the wordmark, so it does
  not sit alone above the heading on a phone. (#370)
- Raise the link underline and the focus halo to a 3:1 contrast against the page in
  the light scheme, with the new tokens `--link-underline` and `--focus-halo`; the
  focus outline keeps the brand sunny colour in both schemes. (#409)
- Set the small text token of the design mock to 14 px, the same size as the site
  token. (#425)
- Add a dark-scheme row for each light pair of the contrast script, so a dark-scheme
  regression now shows in the script output. (#429)
- Shrink the side padding of the hero preview table under 48rem, so the table fits
  the preview window at 390 px with no clip. (#431)
- Wait until 56rem to split the waitlist row into two columns, so the page shows no
  sideways scroll at 768 px, at 800 px, or at 870 px. (#442)

### Contract

- State the hosted collector's header `X-Octometer-Key` in section 2, beside the ingest
  request it shares the body form and the answer codes with. (#297)
- Amend rule C16 and rule C32: the hosted collector takes the body's `userId` field when
  the request carries a valid app key; the kit's own ingest route keeps the rule. (#300)

### Kit JVM

- Add the default route pattern rule (a whole number, a UUID, or a hex string of 8
  characters or more becomes `:id`), and a fallback parameter of `IngestPipeline.process`
  that applies it after the typed route pattern list. (#297)
- Add the `IngestSettings` option `acceptBodyUserId`, and `IngestPipeline.processBatch`,
  so the collector route alone can take a user id from the ingest body. (#300)

### Tracker

- Add the option `appKey`, for the header `X-Octometer-Key` of the hosted collector; send
  each request with `mode: 'cors'`. (#297)
- Add the IIFE bundle `dist/tracker.iife.js`, under 6 KB gzipped, built with `esbuild`; it
  reads `data-app-key` and `data-endpoint` from its own script tag and starts the tracker.
  Move the npm build into its own Gradle module, `kit/tracker`. (#298)
- Add the option `userId` and the data attribute `data-user-id`: a pseudonymous id of the
  signed-in user, dropped with one warning for an email address or a space. (#300)

### Monitor

- Scaffold the hosted service: the Docker image, `fly.toml`, the `/healthz` route, the D56
  fields of `GET /api/health`, the public bind and the temporary admin guard, and the CI
  deploy job. (#295)
- Add sign-in with a mail link: the accounts, the invite list, the session cookie, the
  Resend and the file mail senders, the account filter of every API route, and the
  launcher command `--invite`. Remove the temporary admin guard of #295. (#301)
- Register an app with a name and an origin list; it gets an app key and a secret key,
  shown one time, and the poll loop reads the hosted cluster URI when no secret is
  stored, with one shared MongoDB client for every such app. (#296)
- Add the collector route `POST /collect`: the app key and Origin checks, the body limit,
  the rate limit and the daily caps, the cluster-wide write stop, the CORS preflight of
  `/collect` and `/tracker.js`, and the `writeStopped` field of `GET /api/health`. (#297)
- Add the route `GET /tracker.js`: the served IIFE bundle, the `ETag`, and the cache rules. (#298)
- Make the sign-in sentinel test of `ConfigLoaderTest` wait for the mail file: a bound
  poll replaces the one immediate read of the mail folder. (#328)
- Add `PUT /api/apps/{id}/routes`: the typed route pattern list of an app, in the column
  `route_patterns`; the manage view edits it, with a "Patterns" column on the app list, and
  the collector's app key cache clears on each save. Remove the closed, published-list read
  of `octometer_config`. (#299)
- Give the bare `GET /tracker.js` a `304 Not Modified` answer for a matching `If-None-Match`
  header; the keys panel shows the bare script tag, with no `?v=`. (#331)
- Add the secret-key path of the erasure route: a server of the app calls it with no
  session, erases the cluster copy first, then the cache, and stores the body's own user
  id on the collector path. (#300)
- Spare an identified request from the D43 request count: a new pre-lookup gate takes
  the per-address check before the app lookup, and the D43 count now runs after the
  parse, for an anonymous batch only. (#335)
- Send a signed-in visitor of the plain `/signin` route to `/apps`, after one
  `GET /api/me` check; the confirm page keeps its own behaviour. (#347)
- Hide the breadcrumb on a top-level page: the apps page, the manage page, and the
  not-found page no longer show a one-item trail that repeats the heading. (#346)
- Put the sign-in form into the cartoon card, with the sentence "We send you a mail
  with a link. The link works for 15 minutes." under the title. (#349)
- Give each table caption 12 px of top padding, clear of the panel border. (#376)
- Remove the bottom margin of `.table-scroll` on the manage view, so no empty strip
  sits between the table and the panel border. (#377)
- Show the not-found card in the users, the first-pages, the elements, and the sessions
  view of an app id that is not registered, in place of the loading sentence; the poll
  stops, and the breadcrumb drops the app crumb. (#348)
- Remove the blank reserved band under the header: the banner slot keeps no reserved
  height. A banner that shows sits in the page flow. It pushes the breadcrumb down by
  its own height, so a user can always reach the breadcrumb. (#375)
- Even the padding of the add-app panel: a zero top margin on the heading and a
  24 px padding on each side, so the gap above the heading matches the gap below
  the submit button. (#389)
- Keep the three Manage actions in one row at each width from 390 px up; the table
  scrolls inside `.table-scroll` instead of wrapping the row. (#362)
- Hide the pager of the users view, the first-pages view, and the anonymous-sessions
  view for a one-page result, remove the misused `aria-current` from the page-label
  span, and give the page-count announcement the same rule. (#387)
- Give a disabled button a shallows fill, ink-soft text, and a dashed border, in place
  of the opacity rule; the text now measures 7.83:1 on its fill. (#384)
- Keep the "First pages" header and link text on one line in the apps table, at a
  narrow width too. (#394)
- Change the Users filter form to a grid: the filter input and the Filter button stay
  on one row from 360 px wide up, and the input keeps its 44 px minimum height. (#374)
- Raise the token `--text-s` from 12.8 px to 14 px, so the table caption, the status
  badge, the gap line, the "Data from" line, the account email, the refresh-bar text,
  `.btn-small`, and each form hint read larger. (#380)
- Align the Manage table with the shared `.panel` style: the shared caption and cell
  padding, a `.badge` Status cell, and a right-aligned Patterns column. (#379)
- Bind the Elements table caption to the selected view: it names "this session", "an
  anonymous visit", or "this user". (#392)
- Show a "Deleting…" label with `aria-busy="true"` on the delete-confirm button while
  the delete request is in flight; the label returns on an error. (#393)
- Move "Sign out" into its own account menu, clear of the row of nav pills, so the
  button look and the pill look never mix in one row. (#360)
- Give the Filter button and each pager button the 44 px minimum height of a
  button, on the users, the sessions, and the first-pages view. (#368)
- Add a dark color scheme: a `prefers-color-scheme: dark` block of dark tokens, and the
  band's ink and paper as token values, not hex literals. (#361)
- Rename the "Data time" column of the apps table to "Last updated". The cell now
  shows a relative time, with the full time in its title; the status cell drops the
  "Data from" line. The cell reads its own 30 s clock, so the relative text stays
  fresh with no new poll answer. The Sessions "Start time" column and the Elements
  "Last interaction" column keep their seconds. (#390)
- Open the Edit app form in a dialog, like the delete dialog and the new-secret-key
  dialog; the table row keeps its own height. (#364)
- Add a hover state to the `.btn` class, the nav pill, the Pause refresh pill, and
  the global link style. (#369)
- Hoist the `.field`, `.form-hint`, and base `dialog` rules of the manage forms and the
  sign-in form into one shared stylesheet; each form and dialog renders the same.
  (#385)
- Darken the `.form-error` left border from `--coral` to `--coral-deep`, and add a
  leading "⚠" glyph to each error message. (#383)
- Correct the invalid-field ring pair of `docs/design/contrast.mjs` to the real token
  value, `--coral-deep`; the pair now reads 4.5:1, at or above the 3:1 floor. (#438)
- Show an error state with a "Try again" button on the apps view, the users view, the
  sessions view, and the first-pages view, when the first list request fails; the
  perpetual loading sentence no longer shows. (#373)
- Render the raw error text of a failed app's Status cell at 14px monospace, with a
  2-line clamp; the full text stays in the element's `title` attribute. (#395)

### CI

- Add the workflow `mirror-images.yml`, a manual mirror of the MongoDB image into the
  GitHub Container Registry; give the `e2e` job a registry login. (#456)
- Upload `e2e/.run/*.log` as the artifact `e2e-run-logs` on a failure of the `e2e`
  job, with a retention of 3 days. (#418)
- Pull the demo app's MongoDB image and the monitor's base image from the Amazon
  ECR Public mirror, not from Docker Hub, so the anonymous pull limit never fails
  the `e2e` job. (#418)
- Give the secret scan job the `pull-requests: read` permission, so it passes on the
  private repository. (#308)
- Remove the Windows job and the weekly run, so the private repository stays inside the
  Free plan minutes. (#306)
- Give the deploy job the e2e job's own built distribution, as a one-day artifact, in
  place of a second Gradle build; add `jitpack.yml` to the e2e path filter. (#297)

### Tests

- Give each clone its own Docker Compose project in the end-to-end harness, derived
  from the clone's path, with `OCTOMETER_E2E_PROJECT` as the override. (#406)
- Pull each Testcontainers MongoDB image from the Amazon ECR Public mirror, not from
  Docker Hub, in `monitor/backend`, `kit/jvm-mongo`, and `tools/demo-app`. (#447)
- Scroll each clicked control into view with `behavior: 'instant'` before the click, so
  no smooth scroll moves the hero and how-it-works scenarios. (#448)

### Documentation

- Record the hosted model (D50 to D58) in the design document, with the closure notes, the
  change notes, and the milestone I backlog. (#294)
- State that the repository keeps all rights: the `LICENSE` file, the `license` field of
  `kit/tracker/package.json`, and the licence block of each kit module POM. (#203)
- Add the collector paragraph to `docs/privacy.md`, and the collector section to
  `docs/run-guide.md`: the config keys, the write stop, and the daily cap. (#297)
- Add `docs/add-octometer.md`: the served script tag, the npm option, the origins rule,
  the app key and the secret key, and the Content-Security-Policy paragraph. Add the
  served-tracker section to `docs/run-guide.md`. (#298)
- Add the "Your users" section to `docs/add-octometer.md`, and the pseudonymous id and
  erasure paragraph to `docs/privacy.md`. (#300)
- Pull the local-service MongoDB image from the Amazon ECR Public mirror, not from
  Docker Hub, so a manual start never hits the anonymous pull limit. (#430)
- Make `tools/minor_issues_table.py` honor the `\|` escape inside a cell, and fail with
  an exit code when a row misses a column. (#449)

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
