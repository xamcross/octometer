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
