// The top navigation bar (issue #367, the audit finding L2): the page held
// no "nav" element and no path to the sign-in page. The bar now carries
// the mark, the wordmark, and three links at 1280 px. The MAJOR 1
// correction of the review of pull request #440: at 390 px the bar
// keeps the mark, the wordmark, and "Sign in", with no sideways scroll
// at either width.
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

  // MAJOR a1 of the review of pull request #413: the board draws the
  // "Sign in" button white (the plain ".btn"), not the sunny primary
  // color of the hero's own "Join the waitlist" button.
  const signInBackground = await signInLink.evaluate(
    (el) => getComputedStyle(el).backgroundColor,
  );
  expect(signInBackground).toBe('rgb(255, 255, 255)');

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

  // The MAJOR 1 correction of the review of pull request #440. At
  // 390 px the mark, the wordmark, and "Sign in" show. The link stays
  // reachable, inside the viewport.
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto('/');
  await expect(word).toBeVisible();
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

/*
 * Issue #409, BLOCKER 2 of the review of pull request #441: the focus
 * outline keeps the brand sunny colour in both schemes. The token
 * "--focus-halo" carries the 3:1 contrast against the page instead (the
 * outer ring of the box-shadow). The check reads the real computed style
 * of a plain link (the "How it works" link holds no ".btn" class, so it
 * keeps the global "a" underline and the global ":focus-visible" ring);
 * "page.hover()" is not needed. Each check also reads the element's own
 * "opacity", so a future transparent state cannot hide a real contrast
 * failure behind a ratio that math alone says is fine.
 */
const toLinear = (channel: number) => {
  const c = channel / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const hexLuminance = (hex: string) => {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
};
const rgbLuminance = (rgb: string) => {
  const [r, g, b] = rgb.match(/\d+/g)!.map(Number);
  return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
};
const ratioOf = (lumA: number, lumB: number) => {
  const [lighter, darker] = lumA > lumB ? [lumA, lumB] : [lumB, lumA];
  return (lighter + 0.05) / (darker + 0.05);
};

async function measureLinkAndHalo(page: import('@playwright/test').Page) {
  const tokens = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement);
    return {
      paper: root.getPropertyValue('--paper').trim(),
      shallows: root.getPropertyValue('--shallows').trim(),
      linkUnderline: root.getPropertyValue('--link-underline').trim(),
      focusHalo: root.getPropertyValue('--focus-halo').trim(),
    };
  });
  const howLink = page.getByRole('link', { name: 'How it works' });
  const decoration = await howLink.evaluate((el) => ({
    color: getComputedStyle(el).textDecorationColor,
    opacity: getComputedStyle(el).opacity,
  }));
  await page.keyboard.press('Tab');
  await expect(howLink).toBeFocused();
  // The halo sits in the "0px 0px 0px 7px" layer of the box-shadow
  // (site.css); its own colour comes just before that layer.
  const halo = await howLink.evaluate((el) => {
    const style = getComputedStyle(el);
    const match = style.boxShadow.match(/rgb\([^)]+\)(?=\s+0px 0px 0px 7px)/);
    return { color: match ? match[0] : null, opacity: style.opacity };
  });
  const topbarBackground = await page.evaluate(
    () => getComputedStyle(document.querySelector('.topbar')!).backgroundColor,
  );
  return { tokens, decoration, halo, topbarBackground };
}

test.describe('the link underline and the focus halo contrast (issue #409)', () => {
  test('the light scheme clears a 3:1 contrast against the page at 1280 px', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto('/');
    const { tokens, decoration, halo, topbarBackground } = await measureLinkAndHalo(page);

    expect(decoration.opacity).toBe('1');
    expect(halo.opacity).toBe('1');
    expect(halo.color).not.toBeNull();

    // The token-level check: "--link-underline" and "--focus-halo" against
    // both page backgrounds (the issue names "--paper" and "--shallows").
    expect(ratioOf(hexLuminance(tokens.linkUnderline), hexLuminance(tokens.paper))).toBeGreaterThanOrEqual(3);
    expect(ratioOf(hexLuminance(tokens.linkUnderline), hexLuminance(tokens.shallows))).toBeGreaterThanOrEqual(3);
    expect(ratioOf(hexLuminance(tokens.focusHalo), hexLuminance(tokens.paper))).toBeGreaterThanOrEqual(3);
    expect(ratioOf(hexLuminance(tokens.focusHalo), hexLuminance(tokens.shallows))).toBeGreaterThanOrEqual(3);

    // The rendered-page check: the real "text-decoration-color" of the
    // link and the real halo colour of its focus ring, against the bar.
    expect(
      ratioOf(rgbLuminance(decoration.color), rgbLuminance(topbarBackground)),
    ).toBeGreaterThanOrEqual(3);
    expect(
      ratioOf(rgbLuminance(halo.color as string), rgbLuminance(topbarBackground)),
    ).toBeGreaterThanOrEqual(3);
  });

  test.describe('the dark color scheme', () => {
    test.use({ colorScheme: 'dark' });

    test('the dark scheme clears a 3:1 contrast against the page at 1280 px', async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 720 });
      await page.goto('/');
      const { tokens, decoration, halo, topbarBackground } = await measureLinkAndHalo(page);

      expect(decoration.opacity).toBe('1');
      expect(halo.opacity).toBe('1');
      expect(halo.color).not.toBeNull();

      expect(ratioOf(hexLuminance(tokens.linkUnderline), hexLuminance(tokens.paper))).toBeGreaterThanOrEqual(3);
      expect(ratioOf(hexLuminance(tokens.linkUnderline), hexLuminance(tokens.shallows))).toBeGreaterThanOrEqual(3);
      expect(ratioOf(hexLuminance(tokens.focusHalo), hexLuminance(tokens.paper))).toBeGreaterThanOrEqual(3);
      expect(ratioOf(hexLuminance(tokens.focusHalo), hexLuminance(tokens.shallows))).toBeGreaterThanOrEqual(3);

      expect(
        ratioOf(rgbLuminance(decoration.color), rgbLuminance(topbarBackground)),
      ).toBeGreaterThanOrEqual(3);
      expect(
        ratioOf(rgbLuminance(halo.color as string), rgbLuminance(topbarBackground)),
      ).toBeGreaterThanOrEqual(3);
    });
  });
});
