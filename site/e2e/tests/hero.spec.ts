// The scaffold scenario of the public site (issue #287): the page shows
// the hero and the button points at the waitlist field. Issue #291 adds
// the real waitlist field and grows this scenario.
import { test, expect } from '@playwright/test';

test('the page shows the hero and the button points at the waitlist field', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveTitle('Octometer');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Every click in your app, counted.',
  );

  const button = page.getByRole('link', { name: 'Join the waitlist' });
  await expect(button).toHaveAttribute('href', '#waitlist-email');

  // Review MAJOR 2 of pull request #321: the outline proves the ring, not
  // the box-shadow, because the 80ms box-shadow transition of ".btn" still
  // holds its rest value at the moment the test reads it.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.keyboard.press('Tab');
  await expect(button).toBeFocused();
  await expect(button).toHaveCSS('outline', 'rgb(255, 217, 61) solid 3px');
  await expect(button).toHaveCSS('outline-offset', '2px');
  const boxShadow = await button.evaluate((el) => getComputedStyle(el).boxShadow);
  expect(boxShadow).toContain('0px 0px 0px 7px');
});
