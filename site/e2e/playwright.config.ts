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
  reporter: [['html', { outputFolder: join(here, 'playwright-report'), open: 'never' }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
  },
  webServer: {
    // The build runs as its own CI step (correction m8); this preview
    // step only serves the already-built "site/dist" folder.
    command: `npx vite preview --port ${port} --strictPort`,
    cwd: '..',
    url: baseURL,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
