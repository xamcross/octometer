# The public site

`site/` holds the public landing page of Octometer: a Vite build with plain HTML, CSS, and
a small TypeScript entry. It has no framework. `site/design/mockup.html` is the source of
the page text and the page look. A copy change goes through the mockup first, then into
`site/`.

The page sets no cookie and loads no third-party script. Each font ships inside the
bundle; the page makes no request to an outside font host.

The arms of the mark wave one time on load (600 ms); `prefers-reduced-motion: reduce`
turns the wave off. `src/main.ts` moves the focus into the waitlist email field on a
click of "Join the waitlist", with no scroll of its own.

The flow illustration of "How it works" reveals on a scroll: an `IntersectionObserver`
adds the class `node-in` to each node, behind the same guard as the early class `js` of
`index.html`. `prefers-reduced-motion: reduce` shows each node at once, with no move.

## The waitlist (issue #291)

The waitlist runs as the `fetch` handler of the Worker: `site/src/worker.ts`, named as
`main` in `site/wrangler.jsonc`. `run_worker_first` sends only `/api/*`, `/confirm`, and
`/remove` to that handler. Every other path goes to the static assets.

### The bindings and the variables

| Name | Kind | Holds |
| --- | --- | --- |
| `WAITLIST` | D1 database binding | The `waitlist`, `attempts`, and (in test mode) `outbox` tables. |
| `MAIL_API_KEY` | Worker secret | The Resend API key. The owner sets it; an agent never reads it. |
| `SITE_URL` | Variable | The origin of each mail link, for example `https://0ct0meter.com`. The handler also checks the request host against this value. A preview with the production `WAITLIST` binding then writes no row. |
| `MAIL_MODE` | Variable, test only | The value `file` switches the mail sender to a write into the `outbox` table, so a test reads the mail with no call to Resend. |

### The store

`site/migrations/0001_waitlist.sql` holds the table `waitlist` (`email`, `token`,
`created_at`, `confirmed_at`, `unsubscribed_at`) and the table `attempts` (`email_hash`,
`at`), the per-address rate limit of 5 requests each hour. No table and no log line of
this project holds an IP address. The `attempts` column holds the SHA-256 hash of the
address, never the address itself.

Apply the migration before the first local run, and once after the owner creates the
database:

```
npx wrangler d1 migrations apply WAITLIST --local
npx wrangler d1 migrations apply WAITLIST --remote
```

### The cleanup rule

`POST /api/waitlist` deletes an old row at the start of each request: an `attempts` row
older than one hour, and a `waitlist` row that stays unconfirmed for more than 7 days. No
scheduled job runs this cleanup; the next real request runs it instead.

### The local run

```
npm run build
npx wrangler d1 migrations apply WAITLIST --local
npx wrangler dev --var MAIL_MODE:file --var SITE_URL:http://localhost:8787
```

`npm test` runs this same Worker under Playwright: `site/e2e/run.mjs` picks a free port,
clears the local D1 state, and passes that port to `site/e2e/playwright.config.ts`, so
each run starts with an empty database.

### The export of the list for the owner

The site ships no export route. The owner reads the list through a D1 query in the
Cloudflare dashboard: `SELECT email, created_at, confirmed_at FROM waitlist WHERE
confirmed_at IS NOT NULL AND unsubscribed_at IS NULL`. A removed address deletes its
row (the correction round of pull request #326, MAJOR 2); the `confirmed_at` filter
still excludes an unconfirmed address.

## The screenshots of "Three levels in your dashboard"

`site/scripts/screenshots.mjs` takes the three screenshots of the dashboard: the apps
view, the users view of the first app, and the elements view of the first user. Run it
from `site/`, against the local monitor:

```
npm run screenshots
```

The script reads the first app and the first user through the monitor's own API, so it
needs no app id or user id as an argument. It writes six PNG files into
`site/public/screens/`: a 1200 px file and a 600 px file for each view, for the two
sources of the `<picture>` element of each frame.

Two rules:

- A screenshot changes only through this script. Never edit a screenshot file by hand.
- A screenshot shows the demo data only. It never shows a real app or a real user.

## The local commands

Run each command from `site/`.

- `npm ci`: installs the dependencies from `site/package-lock.json`.
- `npm run dev`: starts the local dev server.
- `npm run build`: writes the page into `site/dist/`.
- `npm run preview`: serves `site/dist/` on a local port.
- `npm test` (or `npm run test:e2e`): runs the Playwright suite of `site/e2e/`. The suite
  picks one free port, builds no distribution of its own, and serves `site/dist/`. Run
  `npm run build` first.

## The CI job

The job `site` of `.github/workflows/ci.yml` runs on a change under `site/**`, or on a
change to the workflow file. It installs the dependencies, builds the page, and runs the
Playwright suite. It keeps the Playwright report as an artifact on each run.

## The host: Cloudflare Workers, static assets

Cloudflare Pages is the legacy path now; the project deploys as a Worker with static
assets, through `site/wrangler.jsonc`. Cloudflare Workers Builds builds and deploys the
page from the repository. The maintainer records each step here, so the owner can
repeat it.

### Create the project

1. In the Cloudflare dashboard: **Create an app** → **Continue with GitHub** → pick the
   repository.
2. **Project name**: `octometer`.
3. **Build command**: `npm run build`.
4. **Deploy command**: `npx wrangler deploy`.
5. **Preview command**: `npx wrangler preview`, the dashboard's default. Each branch and
   each pull request gets its own preview environment through that command.
6. **Preview builds**: on. **Cloudflare Access**: off.
7. **Advanced settings** → **Root directory**: `site`.

### The four host rules

1. **Install the Cloudflare Workers Builds GitHub app, and grant it this one
   repository.** A private repository then shows in the "Continue with GitHub" list.
2. **Set the build watch path to `site/*` under Settings → Builds.** Workers Builds
   gives 3 000 build minutes each month on the free plan. The watch path keeps a build
   for a real change to the site only. Exclude the branch `dependabot/*` under
   Settings → Builds → Branch control, so a dependabot branch spends no build minutes.
3. **A pull request from a fork gets no preview build.** The CI job `site` is the check
   for that pull request.
4. **The Worker deploys a push to `main` on its own.** The maintainer merges a pull
   request with a green `site` job only; no separate deploy step runs in CI.

### The project settings

| Setting | Value |
| --- | --- |
| Production branch | `main` |
| Root directory | `site` |
| Build command | `npm run build` |
| Deploy command | `npx wrangler deploy` |
| Preview command | `npx wrangler preview` |
| Node version | The environment variable `NODE_VERSION`, from `site/.nvmrc` |

### The preview URL

Each branch gets its own preview URL: `https://<branch>-octometer.<account>.workers.dev`.

### The domain

The production domain is the apex `0ct0meter.com`. The owner bought the domain on
Cloudflare on 2026-10-01. The owner adds it under the Worker's **Settings** → **Domains
& Routes**. The site has no `www` record for now.
