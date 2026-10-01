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

  await page.keyboard.press('Tab');
  await expect(button).toBeFocused();
  const boxShadow = await button.evaluate((el) => getComputedStyle(el).boxShadow);
  expect(boxShadow).not.toBe('none');
});
