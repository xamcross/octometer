import type { BrowserContext } from '@playwright/test';
import { test as base } from '@playwright/test';

import { DESKTOP_CHROME_USER_AGENT } from './setup/constants';

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

interface Fixtures {
  /** Opens one browser context of its own, with the webdriver flag hidden. Closed at the end. */
  newTrackedContext: () => Promise<BrowserContext>;
}

export const test = base.extend<Fixtures>({
  // The default context and page of every test also hide the flag
  // (decision, correction of the brief review): a test that opens no
  // extra context still drives a tracker-visible page correctly.
  context: async ({ context }, use) => {
    await hideWebdriverFlag(context);
    await use(context);
  },

  newTrackedContext: async ({ browser }, use) => {
    const opened: BrowserContext[] = [];
    await use(async () => {
      // userAgent repeats the config value here (never only in
      // playwright.config.ts): a context that a test opens itself, with
      // browser.newContext(), does not inherit the project `use` block.
      const context = await browser.newContext({ userAgent: DESKTOP_CHROME_USER_AGENT });
      await hideWebdriverFlag(context);
      opened.push(context);
      return context;
    });
    for (const context of opened) {
      await context.close();
    }
  },
});

export { expect } from '@playwright/test';
