// The scaffold scenario of the public site (issue #287), grown by issue
// #288 with the wave animation, the focus move into the waitlist field, and
// the overflow proof at 360 px and 1440 px. Issue #291 adds the real
// waitlist field that the click below now reaches.
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
  const heroNote = page.locator('.hero-text p.small');
  await expect(heroNote).toHaveText(
    'Octometer is in a pilot. Join the waitlist, and we invite you.',
  );

  // The audit finding L12 (issue #357): "--text-s" rises from 12.8 px
  // (0.8rem) to 14 px (0.875rem). The check runs at the default 1280 px
  // width and again at 390 px, the two widths of the issue text.
  const noteFontSize1280 = await heroNote.evaluate(
    (el) => parseFloat(getComputedStyle(el).fontSize),
  );
  expect(noteFontSize1280).toBe(14);

  await page.setViewportSize({ width: 390, height: 800 });
  const noteFontSize390 = await heroNote.evaluate(
    (el) => parseFloat(getComputedStyle(el).fontSize),
  );
  expect(noteFontSize390).toBe(14);
  await page.setViewportSize({ width: 1280, height: 720 });

  const button = page.getByRole('link', { name: 'Join the waitlist' });
  await expect(button).toHaveAttribute('href', '#waitlist-email');

  // MAJOR 1 of the review of pull request #322: "getComputedStyle" reports
  // the declared name, also with no matching "@keyframes" rule. The check
  // reads the real animation object, which exists only with the rule.
  const arms = page.locator('.arms');
  const wave = await arms.evaluate((el) => {
    const [a] = el.getAnimations();
    return a
      ? {
          name: a.animationName,
          frames: a.effect.getKeyframes().length,
          ms: a.effect.getTiming().duration,
          runs: a.effect.getTiming().iterations,
        }
      : null;
  });
  expect(wave).toEqual({ name: 'wave', frames: 4, ms: 600, runs: 1 });

  // The reduced-motion answer: the arms carry no animation.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(arms).toHaveCSS('animation-name', 'none');

  // Review MAJOR 2 of pull request #321: the outline proves the ring, not
  // the box-shadow, because the 80ms box-shadow transition of ".btn" still
  // holds its rest value at the moment the test reads it. This stop runs
  // before the click below, so the click does not consume the Tab stop.
  // Issue #367 adds the top bar with its three links before the hero, so
  // four Tab presses, not one, reach this button now.
  for (let i = 0; i < 4; i++) {
    await page.keyboard.press('Tab');
  }
  await expect(button).toBeFocused();
  await expect(button).toHaveCSS('outline', 'rgb(255, 217, 61) solid 3px');
  await expect(button).toHaveCSS('outline-offset', '2px');
  const boxShadow = await button.evaluate((el) => getComputedStyle(el).boxShadow);
  expect(boxShadow).toContain('0px 0px 0px 7px');

  // BLOCKER 1 of the review of pull request #322: a fragment navigation to
  // a focusable target moves the focus by itself, with no script. The
  // capture-phase listener cancels that navigation, so only the script of
  // "main.ts" can move the focus, and the assertion proves that script.
  await page.evaluate(() => {
    document.addEventListener('click', (event) => event.preventDefault(), true);
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

test.describe('the dark color scheme (issue #366, the UI audit finding L16)', () => {
  test.use({ colorScheme: 'dark' });

  test('a dark system preference gives the dark token values', async ({ page }) => {
    await page.goto('/');

    const body = await page.evaluate(() => {
      const style = getComputedStyle(document.body);
      return { background: style.backgroundColor, color: style.color };
    });

    // The light values of "--shallows" and "--ink" (tokens.css), so the
    // dark-mode read must differ from each one.
    expect(body.background).not.toBe('rgb(230, 243, 255)');
    expect(body.color).not.toBe('rgb(36, 31, 61)');

    // The contrast ratio of the body text against the body background,
    // the formula of WCAG 2.1 (relative luminance, then the ratio of the
    // two luminance values plus 0.05).
    const toLinear = (channel: number) => {
      const c = channel / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    const relativeLuminance = (rgb: string) => {
      const [r, g, b] = rgb.match(/\d+/g)!.map(Number);
      return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
    };
    const lumText = relativeLuminance(body.color);
    const lumBackground = relativeLuminance(body.background);
    const [lighter, darker] =
      lumText > lumBackground ? [lumText, lumBackground] : [lumBackground, lumText];
    const contrast = (lighter + 0.05) / (darker + 0.05);
    expect(contrast).toBeGreaterThanOrEqual(4.5);

    // MAJOR 1 of the review of pull request #407: the sunny outline sits
    // inside a halo. The halo must keep a 3:1 contrast against the
    // outline in the dark scheme too.
    // The read waits for the 80ms box-shadow transition to settle. A
    // mid-transition read is not the rest value (review MAJOR 2 of pull
    // request #321).
    const button = page.getByRole('link', { name: 'Join the waitlist' });
    await page.keyboard.press('Tab');
    await expect(button).toBeFocused();
    let rawShadow = '';
    for (let i = 0; i < 20; i += 1) {
      rawShadow = await button.evaluate((el) => getComputedStyle(el).boxShadow);
      if (rawShadow.includes('0px 0px 0px 7px')) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    expect(rawShadow).toContain('0px 0px 0px 7px');
    const outlineColor = await button.evaluate((el) => getComputedStyle(el).outlineColor);
    const halo = rawShadow.match(/rgb\([^)]+\)(?=\s+0px 0px 0px 7px)/);
    expect(halo).not.toBeNull();
    const lumOutline = relativeLuminance(outlineColor);
    const lumHalo = relativeLuminance((halo as RegExpMatchArray)[0]);
    const [ringLighter, ringDarker] =
      lumOutline > lumHalo ? [lumOutline, lumHalo] : [lumHalo, lumOutline];
    const ringContrast = (ringLighter + 0.05) / (ringDarker + 0.05);
    expect(ringContrast).toBeGreaterThanOrEqual(3);
  });
});
