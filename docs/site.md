# The public site

`site/` holds the public landing page of Octometer: a Vite build with plain HTML, CSS, and
a small TypeScript entry. It has no framework. `site/design/mockup.html` is the source of
the page text and the page look. A copy change goes through the mockup first, then into
`site/`.

The page sets no cookie and loads no third-party script. Each font ships inside the
bundle; the page makes no request to an outside font host.

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
