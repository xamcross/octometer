// The top navigation bar (issue #367, the audit finding L2): the page held
// no "nav" element and no path to the sign-in page. The bar now carries
// the mark, the wordmark, and three links at 1280 px, and only the mark
// and "Sign in" at 390 px, with no sideways scroll at either width.
import { test, expect } from '@playwright/test';

test('the top bar carries the mark, the wordmark, and the links, and fits at 1280 px and 390 px', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('/');

  const nav = page.getByRole('navigation', { name: 'Site navigation' });
  await expect(nav).toBeVisible();

  const howLink = nav.getByRole('link', { name: 'How it works' });
  const privacyLink = nav.getByRole('link', { name: 'Privacy' });
  const signInLink = nav.getByRole('link', { name: 'Sign in' });
  const word = page.locator('.topbar-brand .word');

  // At 1280 px the mark, the wordmark, and all three links show.
  await expect(word).toBeVisible();
  await expect(howLink).toBeVisible();
  await expect(privacyLink).toBeVisible();
  await expect(signInLink).toBeVisible();
  await expect(signInLink).toHaveAttribute('href', 'https://app.0ct0meter.com/signin');

  // The audit finding L2: the page held no "nav" element before this fix
  // (a bar height of 0 px). The bar now measures a real height, with no
  // sideways scroll at 1280 px.
  const heightWide = await page
    .locator('.topbar')
    .evaluate((el) => el.getBoundingClientRect().height);
  expect(heightWide).toBeGreaterThan(0);
  const wide = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(wide.scroll).toBeLessThanOrEqual(wide.client);

  // The page carries no skip link, so the bar leads the Tab order: the
  // first Tab stop is the first link of the bar.
  await page.keyboard.press('Tab');
  await expect(howLink).toBeFocused();

  // At 390 px only the mark and "Sign in" show, and the link stays
  // reachable, inside the viewport.
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto('/');
  await expect(word).toBeHidden();
  await expect(howLink).toBeHidden();
  await expect(privacyLink).toBeHidden();
  await expect(signInLink).toBeVisible();

  const heightNarrow = await page
    .locator('.topbar')
    .evaluate((el) => el.getBoundingClientRect().height);
  expect(heightNarrow).toBeGreaterThan(0);
  const narrow = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(narrow.scroll).toBeLessThanOrEqual(narrow.client);

  const signInBox = await signInLink.boundingBox();
  expect(signInBox).not.toBeNull();
  expect(signInBox!.x).toBeGreaterThanOrEqual(0);
  expect(signInBox!.x + signInBox!.width).toBeLessThanOrEqual(390);

  // At 390 px the hidden links leave the Tab order: one Tab press
  // reaches "Sign in" directly.
  await page.keyboard.press('Tab');
  await expect(signInLink).toBeFocused();
});
