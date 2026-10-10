# The run guide

This guide tells the owner how to install, run, and maintain the monitor. The
guide names no real app and no real organisation. A value inside angle
brackets is a placeholder; the owner fills it with a real value.

This guide covers five sections in full. Section 3 gives a placeholder for
the upgrade step; issue #56 gives the upgrade script and the full text of
that step.

## 1. Install and start

The monitor lives under one folder, `%LOCALAPPDATA%\Octometer\`. Keep this
folder outside OneDrive or a different synced folder. A synced copy of the
SQLite write-ahead log file is not safe.

The target layout of design decision D34 holds these folders and files:

- `app\<version>\`: the installed code of one version.
- `data\`: the SQLite store.
- `secrets\`: the connection string of each app.
- `logs\`: the rolling log files.
- `backups\`: the daily backup files.
- `octometer.conf`: the user config file.
- `octometer.cmd`: the launcher of the installed version.

The monitor creates `data\`, `logs\`, and `backups\` at the first start. The
monitor creates `secrets\` at the first registration of an app. The owner
creates `app\<version>\` by hand today.

`octometer.cmd` and the scheduled task come with issue #54. Until #54 lands,
follow these steps.

1. Open the release page:
   `https://github.com/xamcross/octometer/releases`.
2. Download `octometer-monitor-<version>.zip` (the example version below is
   `0.2.0`, so the file is `octometer-monitor-0.2.0.zip`).
3. Extract the zip. It holds one folder, `octometer-monitor-<version>`.
4. Create the folder `app\<version>\` under `%LOCALAPPDATA%\Octometer\`. Move
   each file of `octometer-monitor-<version>` into it.
5. Open a console in `app\<version>\`. Run `bin\backend.bat`.

The monitor starts in the prod mode by default. Stop the monitor with
Ctrl+C. The scheduled task of design decision D35 (`scripts/install.ps1`)
is not on `main` today. That script and the task come with issue #54; this
guide names no command of that script until it lands.

Confirm the start with a health check:

```powershell
curl.exe http://127.0.0.1:7431/healthz
```

Open the UI at `http://127.0.0.1:7431/`, and sign in (issue #301). A
local run keeps `mailSender=file` by default (a blank `publicUrl`), so
no address needs an invite yet: run

```powershell
bin\backend.bat --invite <your address>
```

then request a link on the sign-in page. Read the newest file of the
mail folder (`%LOCALAPPDATA%\Octometer\..\mail` in prod mode, the
sibling folder of `dataDir`), and open the link it holds.

## 2. The config

Design decision D2 sets the config keys and their order. The order of
precedence, high to low: the command argument, the environment variable, the
user file `%LOCALAPPDATA%\Octometer\octometer.conf`, the bundled file
`octometer-defaults.conf`.

| Key | Environment variable | Default |
| --- | --- | --- |
| `mode` | `OCTOMETER_MODE` | `prod` |
| `port` | `OCTOMETER_PORT` | `7431` |
| `dataDir` | `OCTOMETER_DATA_DIR` | `%LOCALAPPDATA%\Octometer\data` in prod, `build/dev-data` in dev |
| `settleLagSeconds` | `OCTOMETER_SETTLE_LAG_SECONDS` | `60` in prod, `2` in dev |
| `retentionDays` | `OCTOMETER_STORE_RETENTION_DAYS` | `395` |
| `backupDir` | `OCTOMETER_BACKUP_DIR` | the sibling folder `backups` of `dataDir` |
| `pollIntervalSeconds` | `OCTOMETER_POLL_INTERVAL_SECONDS` | `60` in prod, `5` in dev |
| `pathValueCap` | `OCTOMETER_PATH_VALUE_CAP` | `200` |

`monitor/backend/README.md`, section "Config", holds the same eight rows and
the meaning of each key. Read that section for the detail this guide does
not repeat.

Write the user file with one `octometer { }` block. Give each Windows path
with a forward slash:

```
octometer {
  mode = "prod"
  port = 7431
  dataDir = "D:/octometer-data"
}
```

An invalid value stops the start with exit code 2. The start log prints
each value and its source, never a secret.

## 3. Backup, restore, and upgrade

### Backup

The monitor writes one backup file each day, with `VACUUM INTO`, into
`backupDir`. The file name is `octometer-<yyyyMMdd>.db`. The job keeps the
newest 7 files. Before a migration, the monitor writes one extra file,
`pre-migrate-v<n>-<timestamp>.db`, and it keeps one such file for each
migration it ever applied.

A backup file holds no connection string. Each connection string sits in
`secrets\apps.json`, outside `dataDir` (design decision D11).

### Restore

Follow each step in order, on a clean machine or on the same machine.

1. Stop the monitor.
2. Move `data\octometer.db`, `data\octometer.db-wal`, and
   `data\octometer.db-shm` out of `data\`.
3. Create the folder `data\` when it does not exist.
4. Copy the backup file `backups\octometer-<yyyyMMdd>.db` to
   `data\octometer.db`.
5. Start the monitor.

The start stops with one log line when a `-wal` file of the old database
still sits beside the restored file. Move each old `-wal` and `-shm` file
out of `data\` before the start; do not leave one behind.

After the start, read `GET /api/health`. Then compare the event count of
the level 1 view with the event count of the backup file.

`secrets\apps.json` sits outside `dataDir`, so a restore does not touch it.
Each app id of the restored file keeps its connection string. The start
removes each secret whose app id the restored file does not hold. Register
each app that the restore drops, on `/manage`, with its connection string.
Confirm each connection string on `/manage` after a restore of an older
backup file.

### Upgrade

Issue #56 gives the upgrade script `scripts/upgrade.ps1 -Version X.Y.Z`
(design decision D36). The script is not on `main` today; this guide names
no step of that script until #56 lands.

Follow one rule today and after #56 lands: upgrade the monitor before an
app (design decision D26). Read the release notes of the new monitor
version first.

**The route list case.** Issue #299 (D5, D40) brings the typed route
pattern list back to the manage view, in the dashboard's own `app`
table (the column `route_patterns`). The owner types one pattern per
line in "Route patterns (one per line)" on the edit form; the collector
applies the list at write time, and the default rule groups each path
outside the list (a number, a UUID, or a hex segment of 8 characters or
more becomes `:id`). The former kit-publish story (design decisions D46
to D48, issue #277) is closed; section 6 states the full detail.

## 4. Register an app

Sign in, then open `/manage`. Fill the name, and fill one origin per
line in "Origins (one per line)". An origin has the form
`https://host`, with no path (issue #296, design decisions D51, D52).

After the save, a panel shows the app key and the secret key, each
with a copy button. Copy the secret key now: it shows one time, and
the monitor never shows it again. The app key is public (it names the
app to a visitor's browser); keep the secret key private.

The app list shows the app key in its short form, the origin list, and
the count of the typed route pattern list. "Edit app" changes the name,
the origin list, and the route pattern list. "New secret key" asks for
a confirmation, then shows a new secret key one time; the old secret
key stops working at once.

The present path with a connection string stays for a local MongoDB
database of your own: fill `connectionString` through the API
directly (`POST /api/apps`) in place of the manage form, which sends
an origin list only.

### The secrets file of the connection-string path

The monitor stores each connection string in `secrets\apps.json`, outside
`dataDir`. The application applies no encryption to this file (design
decision D11). An app with an origin list keeps no entry here: the
service builds its MongoDB URI from `octometer.clusterUri` and the
stored database name.

Check that BitLocker protects the drive:

```powershell
manage-bde -status C:
```

### Rotate a reader password

Rotate the reader password after a lost laptop, after a suspected leak, and
each 12 months. Delete the user, then create it again, with the Atlas CLI
commands of section 3 of `docs/integration-ktor.md`.

A password never passes through an agent or a chat. The owner runs each
Atlas CLI command by hand.

## 5. The erasure

Design decision D15 sets the order of an erasure. Run the three steps in
order.

1. The app erases its own copy of the user's events.
2. Wait for one full poll cycle of the monitor.
3. Call the erasure route of the monitor.

A call to the monitor before step 2 finishes lets a poll cycle read the
erased events again.

### The erasure command

The user id must not sit in the URL of a command line; the shell history
keeps the command line. Give the id as a shell variable first.

```powershell
$id = "<the id>"
curl.exe -X DELETE -H "Origin: http://127.0.0.1:7431" "http://127.0.0.1:7431/api/apps/<appId>/events?userId=$id"
```

Keep the `Origin` header. The route answers 403 with no `Origin` header. A
`DELETE` with no body needs no `Content-Type` header.

PowerShell also saves the assignment line, `$id = "<the id>"`, in
`ConsoleHost_history.txt`. The shell variable alone does not hide the id.
Clear that history file after the command, or read the id from a file
instead of a literal string.

### The answer

The answer holds `deleted` (a row count) and `checkpointed`:

- `checkpointed: true`: the erased bytes left `octometer.db`.
- `checkpointed: false`: the rows are gone, but an old page can still hold
  the erased bytes. Call the route again with the same user id. Delete no
  `octometer.db*` file by hand.

The route also rewrites each event `path` that holds the erased id as a
whole segment, to `/other` (issue #205).

## 6. The risks

Open the monitor UI in a browser profile with no extension. A hostile
extension can read the page past the Content Security Policy of design
decision D12. The monitor binds to the loopback address only, so a
different machine cannot reach the UI.

### The sleep test

Run this manual test once, after each install.

1. Close the lid of the monitor machine.
2. Wait past one poll interval.
3. Open the lid.

Read the level 1 view. It shows the resume line of issue #29, or the gap
line of issue #62 when the machine stayed off for a long time.

### The residual risks

The owner accepts each risk below (design section 8):

- A forged click.
- A hostile browser extension on the monitor machine.
- A leaked reader password. M0 keeps no access history, so an Atlas alert
  is the only signal.
- A reader role with a write action the monitor cannot see, when the
  cluster reports no privilege list.
- A source that writes raw paths keeps a maximum of `pathValueCap` distinct
  values in the monitor, until the owner reads the WARN line (issue #205).
  An app with fewer distinct raw values than `pathValueCap` gives no WARN
  line, and the set never ages out on its own. A pattern-shaped raw path
  passes the shape check. The first `pathValueCap` raw values stay in
  `event.path` until the retention purge or an erasure of their user id.
- The owner types the route pattern list on `/manage` (issue #299, D5,
  D40, the column `route_patterns`); the collector applies it at write
  time, with the default rule for a path outside the list. The former
  kit-publish story of design decision D48 (issue #277) is closed.

## 7. The hosted service

Issue #295 scaffolds a hosted copy of the monitor on Fly.io, beside the
desktop app. This section covers the hosted service only.

### The three secrets

The service needs three secrets. Nobody types a secret value into an
agent, a pull request, or a comment.

| Secret | Fly secret name | Set with |
| --- | --- | --- |
| The Atlas cluster connection string | `OCTOMETER_CLUSTER_URI` | `flyctl secrets set` |
| The Resend API key | `OCTOMETER_MAIL_API_KEY` | `flyctl secrets set` |

Issue #301 removes the admin guard token of issue #295. The session
rule of the sign-in feature (below) protects the hosted service now.

`FLY_API_TOKEN` is a fourth secret, a GitHub Actions secret, not a Fly
secret. It lets the deploy job of `.github/workflows/ci.yml` run
`flyctl deploy`.

`FLY_APP_NAME` is a GitHub Actions repository **variable**, not a
secret (the app name is not sensitive). The deploy job passes it to
`flyctl deploy --app`, because `fly.toml` holds no `app` line
(correction round of pull request #307, MAJOR 2).

### The image environment

`monitor/backend/Dockerfile` sets these values inside the image:
`OCTOMETER_PORT=8080`, `OCTOMETER_BIND=0.0.0.0`,
`OCTOMETER_DATA_DIR=/data/db`, `OCTOMETER_CLIENT_IP_HEADER=Fly-Client-IP`,
`OCTOMETER_MODE=prod`. `fly.toml` adds `OCTOMETER_PUBLIC_URL`. The
secrets folder, the log folder, and the backup folder land under
`/data` too, so each one survives a deploy (design decision D56).

The base image comes from the Amazon ECR Public mirror
(`public.ecr.aws/docker/library/eclipse-temurin`), not from Docker
Hub, for the same reason as the demo app's MongoDB image: Docker Hub
refuses an anonymous pull past its daily limit.

### The collector

`POST /collect` (design decision D50, issue #297) takes a click of an
app that registered with an origin list. The tracker's `appKey` option
sets the header `X-Octometer-Key`. The route needs no session.

The route answers 204 for a success, an unknown app key, and an
unlisted origin. It answers 400 for an invalid body or a body over 16
KB, 415 for a wrong content type, and 429 for the rate limit.

Two abuse controls protect the one shared cluster:

- A write stop at 450 MB of `app_*` storage (`storageSize` plus
  `indexSize` of each app's own database, summed once a minute). Above
  the bound, the collector drops each write; `GET /api/health` reports
  `writeStopped: true`.
- A daily cap of 20 000 events for one app. Above the cap, the
  collector drops each write for the rest of that UTC day; `GET
  /api/apps/{id}` reports the drop count in `droppedToday`.

The event cap of design decision D21 is 50 000 events for one app, the
hosted model's own default (design decision D57); this cap never
resets.

### The served tracker

`GET /tracker.js` (design decision D53, issue #298) serves the IIFE
bundle of `kit/tracker`. Read `docs/add-octometer.md` for the script
tag that an app owner adds to a page.

The Gradle module `kit/tracker` builds the bundle with its own task
`npmBuild`. `./gradlew :monitor:backend:installDist` runs that task
first, then copies the built file into the distribution's `static`
folder, as `tracker.js`. This step needs Node 24 and npm, the same
rule as the Angular build.

A developer run with no `installDist` holds no built bundle. The route
then answers 404, and the start log holds the line "The tracker bundle
is absent." The manage view's keys panel shows "The script is not
built on this instance." in that case.

The manage view's keys panel shows the bare tag, with no `?v=`
(design decision D53, the addendum of 2026-10-02, issue #331). The
bare URL gets a one-hour cache header and an `ETag`. A request whose
`If-None-Match` header matches the current `ETag` gets a `304 Not
Modified` answer, with the same `ETag` and `Cache-Control` headers
and no body, so a revalidation costs no transfer. The bare tag
revalidates within one hour, so a bug fix of the tracker reaches
each page without a change of its tag.

The hashed form `?v=<hash>` still answers with a one-year, immutable
cache header, when the query `v` equals the content hash of the
current bundle; each other value of `v` gets the one-hour header. The
hash is the `ETag` value of the bare answer. Use the hashed form only
when a page wants the long cache on purpose.

A page that already cached a hashed URL keeps the old bundle until
that cache entry expires, or until the page's own tag changes. An old
hashed tag needs a change of the tag; `docs/add-octometer.md` tells
the app owner to replace it with the bare tag.

### The order for the owner

1. Create the Fly app and the volume first (`flyctl apps create`, then
   `flyctl volumes create octometer_data --region <region> --size 1`).
2. Set the GitHub Actions repository variable `FLY_APP_NAME` to the
   Fly app name.
3. Record the region in a comment on issue #295. The maintainer then
   writes `primary_region` into `fly.toml`, in a one-line pull
   request.
4. Set the GitHub Actions secret `FLY_API_TOKEN` last, or the first
   deploy job runs with no app, no region, and no volume to deploy to.

### The deploy job

`.github/workflows/ci.yml` runs the job `deploy` on a push to `main`
only, after the jobs `jvm`, `frontend`, and `e2e` pass or skip, and
only on a change that its path filter covers. It checks three things:
the presence of `FLY_API_TOKEN` (never its value), the presence of the
variable `FLY_APP_NAME`, and a real region in `fly.toml` (a grep for
the placeholder `primary_region = "<` finds nothing). When one item is
missing, the job prints one notice that names it, for example "The
deploy is skipped: no FLY_APP_NAME.", and passes, so `main` stays
green until the owner sets each item. The deploy command is
`flyctl deploy --remote-only --ha=false --app "${{ vars.FLY_APP_NAME }}"`:
`fly.toml` names one volume and one machine, so a second, parallel
machine of a normal Fly deploy would compete for that one volume.

### The deploy window

Each deploy stops the old machine, then starts the new one. A click
that reaches the service in that window is lost; the browser reader
sees a failed request. Measured by the maintainer after the first
deploy.

### The memory measurement

Read `flyctl machine status` after one poll cycle. When the value
exceeds 450 MB, propose the 1 GB machine in a comment on the issue.
Measured by the maintainer after the first deploy.

### The rollback

List each past release, then deploy the image of a working one:

```
flyctl releases
flyctl deploy --image <image ref of a past release>
```

### The volume snapshot

Fly takes one snapshot of the volume `octometer_data` each day, and
keeps 5 days of snapshots. Restore from a snapshot through the Fly
dashboard or `flyctl volumes` when the running volume is lost.

### The sign-in feature (issue #301)

Every route under `/api/` needs a session now, except `/api/signin`,
`/api/signin/confirm`, and `/healthz`. A visitor signs in with a mail
link; an account sees the apps of that account only.

The invite list controls who can sign in. Add an address with the
launcher: locally, `bin\backend.bat --invite <address>` (Linux:
`bin/backend --invite <address>`). On the Fly machine:

```
flyctl ssh console
setpriv --reuid octometer --regid octometer --clear-groups /app/bin/backend --invite <address>
```

The ssh session starts as root; the service itself runs as the user
`octometer` (`monitor/backend/docker-entrypoint.sh`), so the invite
command drops to that user the same way.

`--invite <address>` and `--uninvite <address>` open the store only.
Neither command starts a server, and each one takes no instance lock,
so it runs beside the live service. Give the lower-case, trimmed form
of the address; the command prints `invited <address>` or `uninvited
<address>` and exits.

The mail sender follows `OCTOMETER_MAIL_SENDER`: `resend` sends a real
mail through the Resend API key; `file` writes one file per mail under
`OCTOMETER_MAIL_DIR`, for a local run or the end-to-end suite. The
service refuses to start with `mailSender=file` on a public host. The
hosted service, with a public `OCTOMETER_PUBLIC_URL`, keeps the default
`resend`; a local run, with no `publicUrl` or a loopback one, keeps the
default `file`.

An app row with a `NULL` account id (an app that existed before this
issue) is visible to no account, and it polls no more. It waits, inert,
until a later story attaches it to an account.

Read the health of a deployed app with `/healthz`, which needs no
session:

```
curl https://app.0ct0meter.com/healthz
```

A signed-in visitor of `/signin` (no `token` query parameter) lands on
`/apps`: the page calls `GET /api/me` once, and a `200` navigates away
(issue #347). The confirm page (`/signin?token=...`) keeps its own
behaviour: it stays open to a mail scanner, with no session check
(D52).

### The Atlas access list

A Fly machine has no fixed address. Choose one:

- Keep the IP access list entry `0.0.0.0/0` (the owner's credentials
  protect the cluster).
- Allocate a static egress address instead: `flyctl machines
  egress-ip allocate`, then add that one address to the Atlas access
  list.

Give the service database user a password of 32 characters. Rotate it
the same way as the reader password of section 4: delete the user,
then create it again, and set the new connection string as the Fly
secret `OCTOMETER_CLUSTER_URI`.

### The local run

`tools/local-service/README.md` runs the same image on your own
machine, with a local MongoDB container in place of Atlas.

### The end-to-end suite

Run the suite with `cd e2e && npm test`. Each clone gets its own
Docker Compose project. `e2e/setup/constants.ts` builds the name from
the clone's absolute path: `octometer-e2e-<8 hex characters>`. Two
clones on one machine run the suite at the same time; each clone stops
only its own containers.

Set `OCTOMETER_E2E_PROJECT` to pick the project name yourself. The CI
job `e2e` sets it to the fixed name `octometer-e2e`, so its own output
stays stable between runs. The suite never touches the owner's own
containers: the demo app container of a manual run, `demo-app-mongo-1`
on port 27117, belongs to a different project.

`tools/demo-app/docker-compose.yml` pulls the MongoDB image from the
Amazon ECR Public mirror (`public.ecr.aws/docker/library/mongo`), not
from Docker Hub. Docker Hub refuses an anonymous pull past its daily
limit. This is the image that each e2e run and each manual demo run
pulls today. Part B of issue #456 points this file at the GitHub
Container Registry copy of the section below.

### The two image sources

Issue #456 moves the CI pull of the MongoDB image off the Amazon ECR
Public mirror. The move has two parts. Part A adds the workflow
`mirror-images.yml` and a login step of the `e2e` job. Part A pulls no
image from the GitHub Container Registry yet.

A manual run of the workflow (`workflow_dispatch`) pulls the tag from
the Amazon ECR Public mirror. It pushes the tag into the GitHub
Container Registry, as `ghcr.io/xamcross/octometer/mongo:8.0`. The job
logs in first, so the push and a later pull work on a private package
too. Part B points `tools/demo-app/docker-compose.yml` at this GHCR
copy, so the `e2e` job no longer reads the Amazon ECR Public mirror.
The Amazon ECR Public mirror stays the source that the workflow copies
from. The Amazon ECR Public mirror also stays the source for a
developer who pulls the image by hand.

<!--
Sources:
monitor/backend/README.md ("Config", "The daily backup", "How to restore a
  backup", "The log files", "A corrupt, a locked, or a full store", "The
  user erasure route")
docs/demo.md (section 4b, the distribution start)
docs/integration-ktor.md, section 3 (the database user and the password
  rules)
scripts/bootstrap.ps1
CHANGELOG.md (0.2.0 and 0.1.0 entries, issues #29, #38, #62)
monitor/backend/src/main/kotlin/octometer/monitor/config/ConfigLoader.kt
  (ENV_KEYS, about lines 14-23)
monitor/backend/src/main/resources/octometer-defaults.conf
monitor/backend/src/main/kotlin/octometer/monitor/backup/BackupPaths.kt
  (about lines 33-34)
monitor/backend/src/main/kotlin/octometer/monitor/security/BindAddress.kt
  and RequestGuard.kt (D12, the loopback bind and the Content Security
  Policy)
monitor/backend/build.gradle.kts (about lines 343-346: the zip file name
  octometer-monitor-<version>.zip and the folder name backend-<version>
  inside it)
.github/workflows/release.yml (about lines 145-161: the built zip path)
docs/superpowers/specs/2026-09-21-octometer-design.md, decisions D2, D3,
  D11, D15, D26, D34, D35, D36, D37, sections 4.4 and 8
Issue #64 comments of 2026-09-22 (the shell-variable form of the erasure
  command) and the brief correction round of 2026-09-27 (the ConfigLoader
  key count, the daily backup file name, the D34 layout today, the
  ConsoleHost_history.txt note, the checkpointed:false action).
The upgrade step of section 3 is a placeholder. Issue #56 gives
  `scripts/upgrade.ps1` and the full text of that step.
The route list case of section 3: added for issue #279 and design decisions D26,
  D46, D47, and D48. It restates the cache sentence and the read-only manage view
  of section 6 in the context of the upgrade order, for a reader of section 3 alone.
Section 7 (the hosted service): issue #295, design decisions D52 and D56, and the
  brief review corrections B2, B5, M1, M2, M7, M8, m4, m13.
Section 7, "The collector": issue #297, design decisions D21, D40, D50, D57, and the
  brief review of 2026-10-01 (B4, B5, M2, M5).
  monitor/backend/Dockerfile, fly.toml, docker-entrypoint.sh,
  tools/local-service/README.md, .github/workflows/ci.yml (the "deploy" job).
"The two image sources": issue #456, .github/workflows/mirror-images.yml, and
  the "e2e" job's registry login of .github/workflows/ci.yml.
-->
