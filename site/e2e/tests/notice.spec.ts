// The "What we keep" section and the footer notice of the waitlist (issue
// #290): the four points, the link into the notice, the notice sentences,
// the contact link, and the quality floor (no cookie, no third-party
// request, no sideways scroll at 360 px and at 1440 px).
//
// Issue #388 (the audit finding L8) folds the notice into a "<details>"
// element and adds a real footer row; issue #356 (the audit finding L11)
// rewrites the stiff wording. This file checks both.
import { test, expect } from '@playwright/test';

test('the notice is reachable from the form and the footer', async ({ page, context }) => {
  // The request collection starts before the navigation (correction of the
  // brief review), so the list holds the page document request too.
  const requests: string[] = [];
  page.on('request', (request) => requests.push(request.url()));

  await page.goto('/');
  const origin = new URL(page.url()).origin;

  // The four points of "What we keep".
  await expect(page.locator('.points p')).toHaveText([
    'We keep the click, the page pattern, the time, and the host your visitor came from. Nothing else.',
    'Your users get a pseudonymous id and a session id. We set no cookie in your page.',
    'We never store an IP address.',
    'One call erases one user. Each click goes after 12 months.',
  ]);

  // Issue #356, the audit finding L11: the waitlist lead reads in plain
  // language, not "Octometer is in a pilot. Give us your address, and we
  // write to you at the start."
  await expect(page.locator('#waitlist .sec-lead')).toHaveText(
    'Pilot spots are limited. We invite people in order.',
  );

  // Issue #388: the notice starts collapsed, under a plain "Waitlist
  // privacy notice" summary, not a section-scale heading.
  const details = page.locator('details.notice-details');
  const summary = details.locator('summary');
  await expect(summary).toHaveText('Waitlist privacy notice');
  await expect(details).not.toHaveJSProperty('open', true);

  // Issue #388, the audit finding L8: the notice no longer weighs as much
  // as the product. The collapsed block (the summary line only) stays
  // well under the audit's own numbers for the old block (about 440 px
  // at 1280 px, about 530 px on a phone), measured before any click.
  const collapsedHeight = async () =>
    Math.round(await details.evaluate((el) => el.getBoundingClientRect().height));
  await page.setViewportSize({ width: 1280, height: 900 });
  expect(await collapsedHeight()).toBeLessThan(100);
  await page.setViewportSize({ width: 390, height: 900 });
  expect(await collapsedHeight()).toBeLessThan(100);
  await page.setViewportSize({ width: 1280, height: 720 });

  // The link into the notice (issue #356: plain wording, not "the notice
  // of the waitlist") moves the hash, opens the "<details>" element with
  // no script (BLOCKER of the brief review, carried to the "<ul>" that
  // now holds "id=notice" and "tabindex=-1"), and moves the focus there.
  const noticeLink = page
    .locator('section[aria-labelledby="privacy-title"]')
    .getByRole('link', { name: 'Read the waitlist privacy notice.' });
  await expect(noticeLink).toHaveAttribute('href', '#notice');

  const noticeList = page.locator('ul.notice');

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await noticeLink.click();

  await expect(page).toHaveURL(/#notice$/);
  await expect(details).toHaveJSProperty('open', true);
  await expect(noticeList).toBeFocused();
  await expect(summary).toBeInViewport();

  // The notice sentences (issue #356 rewrites one label; the facts stay
  // the same).
  await expect(page.locator('.notice')).toContainText(
    'We use your address for the news of Octometer. We send no other mail.',
  );
  await expect(page.locator('.notice')).toContainText('7 days');
  await expect(page.locator('.notice')).toContainText('How long we keep a confirmed address.');
  await expect(page.locator('.notice')).toContainText(
    'Each mail holds a removal link. You can also write to the contact address.',
  );

  // Issue #388: the link beside the waitlist form also opens the
  // collapsed notice. A fresh load keeps the click a real fragment
  // navigation (not a same-hash no-op).
  await page.goto('/');
  const detailsAgain = page.locator('details.notice-details');
  await expect(detailsAgain).not.toHaveJSProperty('open', true);
  const wlNoticeLink = page
    .locator('.wl-pilot')
    .getByRole('link', { name: 'Read the waitlist privacy notice.' });
  await expect(wlNoticeLink).toHaveAttribute('href', '#notice');
  await wlNoticeLink.click();
  await expect(detailsAgain).toHaveJSProperty('open', true);

  // Issue #388: the page ends on one real footer row: the mark, the
  // copyright line, the contact address, and "Sign in".
  const footRow = page.locator('.foot-row');
  await expect(footRow.locator('svg.mark')).toBeVisible();
  await expect(footRow).toContainText('© 2026 Octometer');
  const contact = footRow.getByRole('link', { name: 'support@0ct0meter.com' });
  await expect(contact).toHaveAttribute('href', 'mailto:support@0ct0meter.com');
  const footRowSignIn = footRow.getByRole('link', { name: 'Sign in' });
  await expect(footRowSignIn).toHaveAttribute('href', 'https://app.0ct0meter.com/signin');

  // The zero domain next to the contact line (issue #365, the audit
  // finding L14): the footer names "0ct0meter.com" and marks the zeros,
  // at a size no smaller than the 14 px floor of issue #357.
  const footMeta = page.locator('.foot-meta');
  await expect(footMeta).toContainText('0ct0meter.com — with zeros');
  const zeroLineSize = await footMeta
    .locator('p', { hasText: 'with zeros' })
    .evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
  expect(zeroLineSize).toBeGreaterThanOrEqual(14);

  // The quality floor: no cookie, after the load and after the click.
  const cookies = await context.cookies();
  expect(cookies).toEqual([]);

  // The quality floor: no request left the page origin.
  expect(requests.length).toBeGreaterThan(0);
  for (const url of requests) {
    expect(url.startsWith(origin)).toBe(true);
  }

  // The quality floor (issue #288): no sideways scroll at 360 px and at
  // 1440 px.
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
