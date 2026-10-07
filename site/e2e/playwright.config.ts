// The Playwright project of the public site (issue #287). The port comes
// from "run.mjs", which picks one free port and sets it in the
// environment before this config file loads.
import { defineConfig, devices } from '@playwright/test';
import { join } from 'node:path';

const here = __dirname;
const port = Number(process.env.SITE_E2E_PORT ?? 4173);
const baseURL = `http://localhost:${port}`;

export default defineConfig({
  testDir: './tests',
  // An absolute path, so a test run keeps its report and its result
  // files inside "site/e2e/" always, with no care for the cwd of the
  // command that started the run.
  outputDir: join(here, 'test-results'),
  fullyParallel: false,
  workers: 1,
  // Issue #291: "waitlist.spec.ts" runs several "wrangler d1 execute"
  // calls, each one its own process; the default 30 000 ms timeout is
  // too short for that scenario.
  timeout: 90_000,
  reporter: [['html', { outputFolder: join(here, 'playwright-report'), open: 'never' }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
  },
  webServer: {
    // Issue #291, the brief review correction M5: the harness builds the
    // site, applies the D1 migration to a fresh local database, then
    // starts the real Worker (not "vite preview"), so the waitlist
    // routes answer too. "MAIL_MODE" switches the mail sender to the
    // file mock; "SITE_URL" points back at this local origin, so the
    // host check of "src/worker.ts" lets a local write through.
    // Issue #292: the environment "dev" of "wrangler.jsonc" holds no route,
    // so the Worker sees the local host and the host check holds here.
    command:
      `npm run build && npx wrangler d1 migrations apply WAITLIST --local --env dev && ` +
      `npx wrangler dev --env dev --port ${port} --var MAIL_MODE:file --var SITE_URL:http://localhost:${port}`,
    cwd: '..',
    url: baseURL,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
