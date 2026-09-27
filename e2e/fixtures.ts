import * as fs from 'node:fs';
import * as path from 'node:path';

import type { BrowserContext, Page } from '@playwright/test';
import { test as base } from '@playwright/test';

import { DESKTOP_CHROME_USER_AGENT } from './setup/constants';
import { RUN_DIR } from './setup/state';

/**
 * The V8 script coverage of one page (issue #176, decision 3). One
 * counter for the whole suite run, so two pages of one test, and pages
 * of two different tests, never share a file name.
 */
let coverageFileCounter = 0;

/**
 * Starts the V8 coverage of one page, and writes its entries at the end
 * of the test (the page `close` event; Playwright closes each default
 * page and each tracked context at the end of its own test).
 * `resetOnNavigation: false` keeps the coverage of an earlier
 * navigation, because one test drives more than one URL of the demo
 * app and of the monitor (decision 3, correction of the brief review).
 */
async function trackPageCoverage(page: Page): Promise<void> {
  try {
    await page.coverage.startJSCoverage({ resetOnNavigation: false });
  } catch (error) {
    // A page of a browser other than Chromium gives no page.coverage.
    // This suite runs Chromium only (playwright.config.ts), so this
    // catch is a guard, not an expected path.
    console.error('fixtures: startJSCoverage failed:', error);
    return;
  }
  page.on('close', () => {
    void flushPageCoverage(page);
  });
}

async function flushPageCoverage(page: Page): Promise<void> {
  try {
    const entries = await page.coverage.stopJSCoverage();
    const coverageDir = path.join(RUN_DIR, 'coverage');
    fs.mkdirSync(coverageDir, { recursive: true });
    const fileNumber = ++coverageFileCounter;
    fs.writeFileSync(path.join(coverageDir, `js-${fileNumber}.json`), JSON.stringify(entries), 'utf8');
  } catch (error) {
    console.error('fixtures: the V8 coverage flush of one page failed:', error);
  }
}

/**
 * Defines `navigator.webdriver` as `false` on a context (correction of
 * the brief review of 2026-09-27). The tracker of `kit/tracker` sends
 * nothing while this property is `true`, and Playwright sets it to
 * `true` on each context it opens; `StaticPage.kt` (`tools/demo-app`)
 * passes no `ignoreWebdriver` option to the tracker, so this suite must
 * hide the property itself, on the browser side, and it changes no line
 * of the demo app or the kit to do it.
 */
async function hideWebdriverFlag(context: BrowserContext): Promise<void> {
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', {
      get: () => false,
      configurable: true,
    });
  });
}

/**
 * Hides the webdriver flag, and tracks the V8 coverage of each page of
 * this context (issue #176, decision 3, correction of the brief
 * review). `fixtures.ts` overrides the default `context` fixture only
 * (correction of the brief review of 2026-09-27), so this function
 * hooks `context.on('page', ...)` for the pages of `newTrackedContext()`
 * and of `context.newPage()` too, not only the one default page.
 */
async function hideWebdriverFlagAndTrackCoverage(context: BrowserContext): Promise<void> {
  await hideWebdriverFlag(context);
  context.on('page', (page) => {
    void trackPageCoverage(page);
  });
}

interface Fixtures {
  /** Opens one browser context of its own, with the webdriver flag hidden. Closed at the end. */
  newTrackedContext: () => Promise<BrowserContext>;
}

export const test = base.extend<Fixtures>({
  // The default context and page of every test also hide the flag
  // (decision, correction of the brief review): a test that opens no
  // extra context still drives a tracker-visible page correctly. It
  // also tracks the V8 coverage of that default page (issue #176,
  // decision 3).
  context: async ({ context }, use) => {
    await hideWebdriverFlagAndTrackCoverage(context);
    await use(context);
  },

  newTrackedContext: async ({ browser }, use) => {
    const opened: BrowserContext[] = [];
    await use(async () => {
      // userAgent repeats the config value here (never only in
      // playwright.config.ts): a context that a test opens itself, with
      // browser.newContext(), does not inherit the project `use` block.
      const context = await browser.newContext({ userAgent: DESKTOP_CHROME_USER_AGENT });
      await hideWebdriverFlagAndTrackCoverage(context);
      opened.push(context);
      return context;
    });
    for (const context of opened) {
      await context.close();
    }
  },
});

export { expect } from '@playwright/test';
