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

  // The audit finding L3 (issue #372): the product shot fills the empty
  // right side of the hero at 1280 px. The check measures the empty
  // width to the right of the preview, the audit's own number (the
  // finding states the empty strip, not a ratio).
  const heroPreview = page.locator('.hero-preview');
  await expect(heroPreview).toBeVisible();
  const heroPreviewTable = heroPreview.locator('.hero-preview-table tbody tr');
  await expect(heroPreviewTable).toHaveCount(3);
  const gapAt1280 = await page.evaluate(() => {
    const hero = document.querySelector('.hero');
    const preview = document.querySelector('.hero-preview');
    return (
      hero!.getBoundingClientRect().right - preview!.getBoundingClientRect().right
    );
  });
  expect(gapAt1280).toBeLessThanOrEqual(1);

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

  // The audit finding L15 (issue #370): at 390 px the mark sits beside
  // the wordmark, in the same row, and not alone above it. The 96 px
  // standalone mark hides, and ".hero-mark" shows at 40 px.
  const heroBrandAt390 = await page.evaluate(() => {
    const mark = document.querySelector('.hero-mark') as HTMLElement;
    const word = document.querySelector('.hero-text .word') as HTMLElement;
    const bigMark = document.querySelector('.hero > .mark') as HTMLElement;
    const markRect = mark.getBoundingClientRect();
    const wordRect = word.getBoundingClientRect();
    return {
      bigMarkHidden: getComputedStyle(bigMark).display === 'none',
      markHeight: markRect.height,
      sameRow: markRect.top < wordRect.bottom && wordRect.top < markRect.bottom,
    };
  });
  expect(heroBrandAt390).toEqual({ bigMarkHidden: true, markHeight: 40, sameRow: true });

  // The audit finding L3 (issue #372): at 390 px the preview sits below
  // the copy, with no sideways scroll.
  await expect(heroPreview).toBeVisible();
  const layoutAt390 = await page.evaluate(() => ({
    previewTop: document.querySelector('.hero-preview')!.getBoundingClientRect().top,
    textBottom: document.querySelector('.hero-text')!.getBoundingClientRect().bottom,
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(layoutAt390.previewTop).toBeGreaterThanOrEqual(layoutAt390.textBottom);
  expect(layoutAt390.scrollWidth).toBeLessThanOrEqual(layoutAt390.clientWidth);

  // MAJOR 1 of the review of pull request #423: the window of the preview
  // clipped the Status column between 768 px and about 833 px. The window
  // carries "overflow: hidden" (site.css), so a table wider than the
  // window cuts its right edge; the head cell's own box stays whole, so
  // this check compares the cell's right edge against the window's right
  // edge, not the cell's own scroll width. The breakpoint moves from
  // 48rem to 56rem; this check proves the head cell reads in full at
  // 768 px, the tightest width of the old defect.
  await page.setViewportSize({ width: 768, height: 800 });
  const statusHeadAt768 = await page.evaluate(() => {
    const win = document.querySelector('.hero-preview-window') as HTMLElement;
    const cells = document.querySelectorAll('.hero-preview-table thead th');
    const statusHead = cells[cells.length - 1] as HTMLElement;
    const winRect = win.getBoundingClientRect();
    const headRect = statusHead.getBoundingClientRect();
    return {
      text: statusHead.textContent?.trim(),
      clipped: headRect.right > winRect.right + 1,
    };
  });
  expect(statusHeadAt768).toEqual({ text: 'Status', clipped: false });

  await page.setViewportSize({ width: 1280, height: 720 });

  // Issue #370 keeps the 1280 px layout as it was before the fix: the
  // 96 px standalone mark still shows above the text block, and the
  // small ".hero-mark" of the phone row stays hidden.
  const heroMarkAt1280 = await page.evaluate(() => {
    const big = document.querySelector('.hero > .mark') as HTMLElement;
    const small = document.querySelector('.hero-mark') as HTMLElement;
    return {
      bigVisible: getComputedStyle(big).display !== 'none',
      bigHeight: big.getBoundingClientRect().height,
      smallHidden: getComputedStyle(small).display === 'none',
    };
  });
  expect(heroMarkAt1280).toEqual({ bigVisible: true, bigHeight: 96, smallHidden: true });

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
    const contrastRatio = (rgbA: string, rgbB: string) => {
      const lumA = relativeLuminance(rgbA);
      const lumB = relativeLuminance(rgbB);
      const [lighter, darker] = lumA > lumB ? [lumA, lumB] : [lumB, lumA];
      return (lighter + 0.05) / (darker + 0.05);
    };
    const contrast = contrastRatio(body.color, body.background);
    expect(contrast).toBeGreaterThanOrEqual(4.5);

    // BLOCKER 1 of the review of pull request #423: a bright fill of the
    // preview (the active tab, the two badges) carried no text colour, so
    // the ink token turned near-white in the dark scheme and the text sat
    // on a fill of the same light family. Each pair must keep the token
    // "--on-bright" and clear the 4.5:1 floor.
    const previewPairs = await page.evaluate(() => {
      function read(selector: string) {
        const el = document.querySelector(selector) as HTMLElement;
        const style = getComputedStyle(el);
        return { color: style.color, background: style.backgroundColor };
      }
      return {
        activeTab: read('.hero-preview-tab.is-active'),
        badgeOk: read('.hero-preview-status.is-ok'),
        badgeSlow: read('.hero-preview-status.is-slow'),
      };
    });
    for (const pair of Object.values(previewPairs)) {
      const ratio = contrastRatio(pair.color, pair.background);
      expect(ratio).toBeGreaterThanOrEqual(4.5);
    }

    // MAJOR 1 of the review of pull request #407: the sunny outline sits
    // inside a halo. The halo must keep a 3:1 contrast against the
    // outline in the dark scheme too.
    // The read waits for the 80ms box-shadow transition to settle. A
    // mid-transition read is not the rest value (review MAJOR 2 of pull
    // request #321).
    const button = page.getByRole('link', { name: 'Join the waitlist' });
    // Issue #367 adds the top bar with its three links before the hero, so
    // four Tab presses, not one, reach this button now.
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press('Tab');
    }
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
