// The scaffold scenario of the public site (issue #287), grown by issue
// #288 with the wave animation, the focus move into the waitlist field, and
// the overflow proof at 360 px and 1440 px. Issue #291 adds the real
// waitlist field; until then the test injects a stub field.
import { test, expect } from '@playwright/test';

test('the hero shows the promise and the button reaches the form', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveTitle('Octometer');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Every click in your app, counted.',
  );
  await expect(page.locator('.lead')).toHaveText(
    'Octometer counts each click in your web app. One script tag, one dashboard, no cookie banner.',
  );
  await expect(page.locator('.hero-text p.small')).toHaveText(
    'Octometer is in a pilot. Join the waitlist, and we invite you.',
  );

  const button = page.getByRole('link', { name: 'Join the waitlist' });
  await expect(button).toHaveAttribute('href', '#waitlist-email');

  // The correction of the brief review of #288: the test proves the
  // normal wave rule first. An assertion of "none" alone is always green.
  const arms = page.locator('.arms');
  await expect(arms).toHaveCSS('animation-name', 'wave');
  await expect(arms).toHaveCSS('animation-duration', '0.6s');

  // The reduced-motion answer: the arms carry no animation.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(arms).toHaveCSS('animation-name', 'none');

  // Review MAJOR 2 of pull request #321: the outline proves the ring, not
  // the box-shadow, because the 80ms box-shadow transition of ".btn" still
  // holds its rest value at the moment the test reads it. This stop runs
  // before the click below, so the click does not consume the Tab stop.
  await page.keyboard.press('Tab');
  await expect(button).toBeFocused();
  await expect(button).toHaveCSS('outline', 'rgb(255, 217, 61) solid 3px');
  await expect(button).toHaveCSS('outline-offset', '2px');
  const boxShadow = await button.evaluate((el) => getComputedStyle(el).boxShadow);
  expect(boxShadow).toContain('0px 0px 0px 7px');

  // The click moves the focus into the email field. The test injects a
  // stub field, because issue #291 adds the real one.
  await page.evaluate(() => {
    const input = document.createElement('input');
    input.id = 'waitlist-email';
    document.body.appendChild(input);
  });
  await button.click();
  const activeId = await page.evaluate(() => document.activeElement && document.activeElement.id);
  expect(activeId).toBe('waitlist-email');

  // The quality floor (issue #288): no sideways scroll at 360 px and at
  // 1440 px. The Playwright config gives 1280 px only, so the scenario
  // sets both widths itself.
  await page.setViewportSize({ width: 360, height: 800 });
  const at360 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at360.scroll).toBeLessThanOrEqual(at360.client);

  await page.setViewportSize({ width: 1440, height: 900 });
  const at1440 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at1440.scroll).toBeLessThanOrEqual(at1440.client);
});
