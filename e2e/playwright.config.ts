import * as fs from 'node:fs';
import * as path from 'node:path';

import { defineConfig } from '@playwright/test';

import { DESKTOP_CHROME_USER_AGENT } from './setup/constants';
import { STATE_FILE, STORAGE_STATE_FILE } from './setup/state';

/**
 * Reads the monitor port that `pretest.mjs` reserved, before the global
 * setup starts the monitor on it (decision 1 and decision 2: `baseURL` is
 * the monitor URL). A command that never runs a test, for example
 * `npm run report`, can load this file before any `npm test` run ever
 * wrote the state file; this function then falls back to a placeholder,
 * so that command still works.
 */
function readMonitorBaseUrl(): string {
  try {
    const state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) as {
      ports: { monitor: number };
    };
    return `http://127.0.0.1:${state.ports.monitor}`;
  } catch {
    return 'http://127.0.0.1:0';
  }
}

export default defineConfig({
  testDir: path.join(__dirname, 'tests'),
  // One worker, no parallel file (decision 1): the suite shares one
  // MongoDB container, one demo app, and one monitor for every test.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 5_000 },
  reporter: [['html', { outputFolder: 'playwright-report', open: 'never' }]],
  outputDir: 'test-results',
  globalSetup: path.join(__dirname, 'setup', 'global-setup.ts'),
  globalTeardown: path.join(__dirname, 'setup', 'global-teardown.ts'),
  use: {
    baseURL: readMonitorBaseUrl(),
    userAgent: DESKTOP_CHROME_USER_AGENT,
    trace: 'retain-on-failure',
    // Issue #301, m11: the owner's session, from the global setup. The
    // 13 existing scenarios stay signed in with no change of their own.
    // The new scenario of this issue overrides this with
    // `test.use({ storageState: undefined })`.
    storageState: STORAGE_STATE_FILE,
    // Issue #297, MAJOR 7 of the review of pull request #327: this
    // suite drops the earlier `--disable-features=
    // PrivateNetworkAccessSendPreflights,PrivateNetworkAccessRespect
    // PreflightResults,BlockInsecurePrivateNetworkRequests` list. That
    // list muted Chromium's own private-network preflight check, so
    // the suite never drove the real CORS contract of design decision
    // D50: a minimal reproduction (two plain Node servers, one static
    // page) confirmed Chromium does send a normal preflight for a
    // cross-origin POST with the `X-Octometer-Key` header, with no
    // flag at all, and the monitor answers it (`CollectRoute.kt`'s
    // `Access-Control-Allow-Private-Network` header, gated on the
    // request header and a listed origin, needs no flag either).
    //
    // One flag stays: `--disable-features=LocalNetworkAccessChecks`.
    // A documented Chromium defect needs it. This test's own scenario
    // rewrites the demo page through `context.route(...).fulfill(...)`
    // (`collector.spec.ts`), so Chromium never classifies that
    // document's address space from a real socket; the newer "Local
    // Network Access" check then denies every loopback-to-loopback
    // fetch from it outright, with "Permission was denied for this
    // request to access the `loopback` address space," before any
    // preflight of ours ever runs. The same minimal reproduction, with
    // a `route.fulfill()` step added, reproduced this exact denial,
    // and it passed again the moment this one flag came back. The
    // flag affects this one Chromium defect only; it changes no
    // behaviour of `CollectRoute.kt` or of a real (non-test) page.
    launchOptions: {
      args: ['--disable-features=LocalNetworkAccessChecks'],
    },
  },
  // Chromium only (decision 1). This project sets no device descriptor:
  // a descriptor such as "Desktop Chrome" carries its own `userAgent`
  // value, which would replace the plain string above.
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' },
    },
  ],
});
