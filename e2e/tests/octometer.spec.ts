import type { APIRequestContext, Locator, Page } from '@playwright/test';

import { ratio } from '../../docs/design/contrast.mjs';
import { expect, test } from '../fixtures';
import { AppsPage } from '../pages/apps-page';
import { BreadcrumbPart } from '../pages/breadcrumb-part';
import { DemoPage } from '../pages/demo-page';
import { ElementsPage } from '../pages/elements-page';
import { FirstPagesPage } from '../pages/first-pages-page';
import { ManagePage } from '../pages/manage-page';
import { SessionsPage } from '../pages/sessions-page';
import { UsersPage } from '../pages/users-page';

/**
 * The wait bound of decision 5b: the flush of the tracker (1 s), the
 * settle lag (1 s), the poll interval (2 s), and the refresh of the
 * frontend (2 s, equal to `refreshSeconds`, itself equal to the poll
 * interval). The scenarios of this file wait through
 * `expect(...).toHaveText` and `toContainText`, each with this same
 * bound, never a fixed sleep (acceptance criterion 3 of issue #175).
 */
const CHAIN_WAIT_MS = 30_000;

/**
 * The top padding of a table caption, in CSS px (issue #376, finding D8:
 * `.panel caption` gave `0` top padding, so the caption text sat on the
 * 3px panel border). This suite runs in real Chromium, so
 * `getComputedStyle` gives back a plain px string here (lesson 1 of the
 * frontend brief is for jsdom, not this suite).
 */
async function captionTopPaddingPx(page: Page, captionId: string): Promise<number> {
  const value = await page
    .locator(`#${captionId}`)
    .evaluate((element) => getComputedStyle(element).paddingTop);
  return parseFloat(value);
}

/** The `#rrggbb` value of a `getComputedStyle` colour string (`rgb(r, g, b)`). */
function toHex(rgbColor: string): string {
  const channels = rgbColor.match(/\d+(\.\d+)?/g);
  if (!channels || channels.length < 3) {
    throw new Error(`"${rgbColor}" is not an rgb() colour.`);
  }
  return (
    '#' +
    channels
      .slice(0, 3)
      .map((channel) => Math.round(Number(channel)).toString(16).padStart(2, '0'))
      .join('')
  );
}

/**
 * The WCAG 2.x contrast ratio of the text color against the background
 * color of one element (issue #384, finding D25: the disabled Delete
 * button measured about 2.07:1 at `opacity: 0.55`; issue #393, MAJOR 3
 * of the review of pull request #428: the pending delete label reused
 * the same floor). The locator evaluate reads the two resolved
 * `rgb(...)` strings of the real browser; the ratio math runs here in
 * Node.
 */
async function disabledButtonContrastRatio(button: Locator): Promise<number> {
  const [textColor, backgroundColor] = (await button.evaluate((element) => {
    const style = getComputedStyle(element);
    return [style.color, style.backgroundColor];
  })) as [string, string];
  return contrastRatio(textColor, backgroundColor);
}

function contrastRatio(colorA: string, colorB: string): number {
  const luminanceA = relativeLuminance(colorA);
  const luminanceB = relativeLuminance(colorB);
  const lighter = Math.max(luminanceA, luminanceB);
  const darker = Math.min(luminanceA, luminanceB);
  return (lighter + 0.05) / (darker + 0.05);
}

function relativeLuminance(rgbColor: string): number {
  const channels = rgbColor.match(/[\d.]+/g);
  if (!channels) {
    throw new Error(`"${rgbColor}" is not an rgb(...) color.`);
  }
  const [red, green, blue] = channels.slice(0, 3).map((value) => Number(value) / 255);
  const linear = (value: number): number =>
    value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
}

/**
 * The count of visual lines of an element's text, through a `Range` over
 * its contents (issue #394, finding D19). `Range.getClientRects` gives
 * one rect for each line of the text itself, unlike `Element.getClientRects`,
 * which gives one rect for the element's own box, also on a flex container
 * or a table cell whose text wraps inside it.
 */
async function lineCount(locator: Locator): Promise<number> {
  return locator.evaluate((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    return range.getClientRects().length;
  });
}

/**
 * The computed font size of one element, in CSS px (issue #380, finding
 * D23: the `--text-s` token moves from 12.8 px to 14 px).
 */
async function elementFontSizePx(page: Page, selector: string): Promise<number> {
  const value = await page
    .locator(selector)
    .first()
    .evaluate((element) => getComputedStyle(element).fontSize);
  return parseFloat(value);
}

/**
 * True when the page itself scrolls sideways (issue #380). `.table-scroll`
 * keeps its own horizontal scroll by design, so this check reads the
 * document element only, not a table.
 */
async function pageOverflowsX(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
}

/**
 * The left padding of a table cell, in CSS px (issue #379, finding D10:
 * the Manage table used a local `th, td { padding: 0.5rem 0.75rem }`
 * rule, 12px on the left, instead of the shared `.panel th, .panel td`
 * rule of styles.css, 16px on the left).
 */
async function cellPaddingLeftPx(locator: Locator): Promise<number> {
  const value = await locator.evaluate((element) => getComputedStyle(element).paddingLeft);
  return parseFloat(value);
}

/**
 * Waits, through `GET /api/apps`, for the status of the named app to
 * leave `NEVER_POLLED` (issue #364, D3). It checks the API, never the
 * rendered badge: a view that reads the list one time, and again only
 * on an action, can hold a stale badge long after the backend answer
 * changes.
 */
async function waitForFirstPoll(
  request: APIRequestContext,
  name: string,
  timeoutMs: number,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const response = await request.get('/api/apps');
        expect(response.status()).toBe(200);
        const rows = (await response.json()) as ReadonlyArray<{ name: string; status: string }>;
        return rows.find((row) => row.name === name)?.status ?? 'NEVER_POLLED';
      },
      { timeout: timeoutMs },
    )
    .not.toBe('NEVER_POLLED');
}

/**
 * One serial file, one shared app (decision 5). The scenarios run in
 * this fixed order because each one reads the state that an earlier one
 * wrote: the app id of scenario (a), the click totals of scenario (b).
 */
test.describe.serial('Octometer, the distribution and the demo app (issue #175)', () => {
  let appId: number;

  test('the manage form registers the demo app', async ({ page, request }) => {
    const mongoPort = process.env.E2E_MONGO_PORT;
    const monitorUrl = process.env.E2E_MONITOR_URL;
    if (!mongoPort) {
      throw new Error('E2E_MONGO_PORT is absent. The global setup did not run, or it failed.');
    }
    if (!monitorUrl) {
      throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
    }
    const manage = new ManagePage(page);
    // Issue #296, the brief review B3: the manage form carries no
    // connection string field any more (it sends an origin list
    // instead); the demo app's own registration keeps this legacy
    // path, now through the API directly, with the dashboard Origin
    // that D12 demands for a POST.
    await manage.addAppWithConnectionString(
      request,
      monitorUrl,
      'demo',
      `mongodb://127.0.0.1:${mongoPort}/exampledb`,
    );
    await manage.goto();
    // D8: the first poll cycle can run inside this same wait.
    await manage.expectStatusOneOf('demo', ['Not polled yet', 'OK'], CHAIN_WAIT_MS);

    // Issue #376: the manage table caption keeps clear space above its
    // text, at a desktop width and at a narrow width. No media query
    // touches `.panel caption` (styles.css), so the same number holds
    // at both.
    expect(await captionTopPaddingPx(page, 'manage-caption')).toBeGreaterThanOrEqual(12);

    // Issue #380, finding D23 of the UI audit: the `--text-s` token
    // renders at 14 px, at 1280 px and at 390 px, with no new page
    // overflow at the narrow width.
    expect(await elementFontSizePx(page, '#manage-caption')).toBeCloseTo(14, 1);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await captionTopPaddingPx(page, 'manage-caption')).toBeGreaterThanOrEqual(12);
    expect(await elementFontSizePx(page, '#manage-caption')).toBeCloseTo(14, 1);
    expect(await pageOverflowsX(page)).toBe(false);
    await page.setViewportSize({ width: 1280, height: 720 });

    // Issue #384, finding D25: the disabled Delete button shows a dashed
    // border and no opacity change, and its text stays at 4.5:1 or more
    // against its fill. The dialog closes with Cancel, so the demo app
    // of this serial file stays for every later scenario. `demoManageRow`
    // is the row this scenario just created above.
    const demoManageRow = manage.row('demo');
    await manage.openDeleteDialog(demoManageRow);
    const confirmDeleteButton = manage.deleteConfirmButton(demoManageRow);
    await expect(confirmDeleteButton).toBeDisabled();
    await expect(confirmDeleteButton).toHaveCSS('border-style', 'dashed');
    await expect(confirmDeleteButton).toHaveCSS('opacity', '1');
    expect(await disabledButtonContrastRatio(confirmDeleteButton)).toBeGreaterThanOrEqual(4.5);
    await manage.cancelDeleteDialog(demoManageRow);

    // Issue #379, finding D10: the Manage table cell padding, the Status
    // badge, and the Patterns alignment now match the apps table, at a
    // desktop width and at a narrow width.
    await expect(manage.statusBadge(demoManageRow)).toHaveCount(1);
    expect(await cellPaddingLeftPx(manage.patternsCell(demoManageRow))).toBe(16);
    await expect(manage.patternsCell(demoManageRow)).toHaveCSS('text-align', 'right');
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(manage.statusBadge(demoManageRow)).toHaveCount(1);
    expect(await cellPaddingLeftPx(manage.patternsCell(demoManageRow))).toBe(16);
    await page.setViewportSize({ width: 1280, height: 720 });

    // The app id comes from `GET /api/apps`, through the request context
    // of the test, never from the page response of the POST (correction
    // of pull request #262: Chromium had already discarded that body by
    // the time the earlier version read it). One read, stored below for
    // every later scenario of this file.
    const response = await request.get('/api/apps');
    expect(response.status()).toBe(200);
    const rows = (await response.json()) as ReadonlyArray<{ appId: number; name: string }>;
    const demoRow = rows.find((row) => row.name === 'demo');
    if (!demoRow) {
      throw new Error('The app "demo" is absent from the answer of GET /api/apps.');
    }
    appId = demoRow.appId;
    expect(appId).toBeGreaterThan(0);

    // Issue #272: the registry derives the database name from the path
    // of the connection string. Two rejected creates, through the same
    // API path (the brief review B3), each read through its own
    // answer, with the same fixed text as before.
    const noDbResponse = await manage.addAppWithConnectionStringExpectingError(
      request,
      monitorUrl,
      'demo-no-db',
      `mongodb://127.0.0.1:${mongoPort}`,
    );
    expect(noDbResponse.status()).toBe(400);
    expect((await noDbResponse.json()).error).toBe(
      'Give the database name in the path of the connection string.',
    );

    const badPortResponse = await manage.addAppWithConnectionStringExpectingError(
      request,
      monitorUrl,
      'demo-bad-port',
      'mongodb://127.0.0.1:99999999/exampledb',
    );
    expect(badPortResponse.status()).toBe(400);
    expect((await badPortResponse.json()).error).toBe('The driver cannot read the connection string.');
  });

  test('the level 1 view shows the status badge OK after the first poll', async ({ page }) => {
    const apps = new AppsPage(page);
    await apps.goto();
    await apps.expectStatus('demo', 'OK', CHAIN_WAIT_MS);

    // Issue #390, finding D16 of the UI audit: the "Last updated" column
    // shows a short relative time, with the full local date and time (no
    // seconds) in the title of the <time> element, and the header carries
    // no zone name any more.
    await expect(apps.lastUpdatedHeader).toHaveText('Last updated');
    const lastUpdatedTime = apps.rowOf('demo').lastUpdated.locator('time');
    await expect(lastUpdatedTime).toBeVisible({ timeout: CHAIN_WAIT_MS });
    await expect(lastUpdatedTime).toHaveText(/^(just now|\d+ (min|hours?|days?) ago)$/);
    const lastUpdatedTitle = await lastUpdatedTime.getAttribute('title');
    expect(lastUpdatedTitle).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);

    // Issue #376: the apps table caption keeps clear space above its
    // text (the acceptance criterion's own end-to-end assertion).
    expect(await captionTopPaddingPx(page, 'apps-caption')).toBeGreaterThanOrEqual(12);

    // Issue #380, finding D23 of the UI audit: the status badge renders
    // at the `--text-s` size, 14 px, at 1280 px and at 390 px, with no
    // new page overflow at the narrow width.
    expect(await elementFontSizePx(page, '.badge')).toBeCloseTo(14, 1);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await elementFontSizePx(page, '.badge')).toBeCloseTo(14, 1);
    expect(await pageOverflowsX(page)).toBe(false);
    await page.setViewportSize({ width: 1280, height: 720 });

    // Issue #346: the apps page shows one breadcrumb item that repeats
    // the "Apps" heading, so the component hides the whole nav here.
    const breadcrumb = new BreadcrumbPart(page);
    await breadcrumb.expectAbsent(CHAIN_WAIT_MS);
  });

  test(
    'three clicks of two users and one anonymous click give the level 1 totals ' +
      '3 clicks, 2 users, 2 sessions (D19 off, D43; the derivation is in the pull request text)',
    async ({ page, newTrackedContext }) => {
      const contextA = await newTrackedContext();
      const contextB = await newTrackedContext();
      const contextAnonymous = await newTrackedContext();

      const demoA = new DemoPage(await contextA.newPage());
      await demoA.goto();
      await demoA.selectUser('amy');
      await demoA.clickButton(1);
      await demoA.clickButton(2);

      const demoB = new DemoPage(await contextB.newPage());
      await demoB.goto();
      await demoB.selectUser('ben');
      await demoB.clickButton(3);

      // The anonymous click: no user is ever selected in this context, so
      // the demo cookie `demo_user` stays absent, and the ingest route
      // drops the whole batch (D19 off, `IngestRequestProcessor` step 8).
      const demoAnonymous = new DemoPage(await contextAnonymous.newPage());
      await demoAnonymous.goto();
      await demoAnonymous.clickButton(1);

      const apps = new AppsPage(page);
      await apps.goto();
      // This one wait proves both halves of acceptance criterion 2: the
      // three real clicks reach the total, and the anonymous click adds
      // nothing to it. One poll cycle reads every app event together, so
      // a leaked anonymous event would already show in this same total.
      await apps.expectTotals(
        'demo',
        { clicks: 3, uniqueUsers: 2, uniqueSessions: 2 },
        CHAIN_WAIT_MS,
      );

      const sessions = new SessionsPage(page);
      await sessions.goto(appId);
      await sessions.expectNoSessions(CHAIN_WAIT_MS);
    },
  );

  test('the users view lists the two users with their click counts', async ({ page }) => {
    const users = new UsersPage(page);
    await users.goto(appId);
    await users.expectRow('amy', { clicks: 2, sessions: 1, uniqueElements: 2 }, CHAIN_WAIT_MS);
    await users.expectRow('ben', { clicks: 1, sessions: 1, uniqueElements: 1 }, CHAIN_WAIT_MS);
    expect(await users.rowCount()).toBe(2);

    // Issue #376: the users table caption keeps clear space above its text.
    expect(await captionTopPaddingPx(page, 'users-caption')).toBeGreaterThanOrEqual(12);

    // Issue #380: the users view (the filter button, the pager, both
    // `.btn-small`) keeps no new page overflow at a narrow width.
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await pageOverflowsX(page)).toBe(false);
    await page.setViewportSize({ width: 1280, height: 720 });

    // Issue #346: the users view keeps its two-item breadcrumb, with a
    // link back to the apps page.
    const breadcrumb = new BreadcrumbPart(page);
    await breadcrumb.expectLabels(['Apps', 'demo'], CHAIN_WAIT_MS);
    await expect(breadcrumb.linkNamed('Apps')).toHaveAttribute('href', /\/apps$/);

    // Issue #374, the audit finding D14: at 360 px wide, the filter
    // input and the Filter button stay on one row, next to each other,
    // and the input keeps its 44 px minimum height.
    await page.setViewportSize({ width: 360, height: 844 });
    const filterInputBox = (await users.filterInput().boundingBox())!;
    const filterButtonBox = (await users.filterButton().boundingBox())!;
    console.log(
      `issue #374: at 360px wide, the filter input box is top ${filterInputBox.y}px, ` +
        `left ${filterInputBox.x}px, height ${filterInputBox.height}px; the Filter ` +
        `button box is top ${filterButtonBox.y}px, left ${filterButtonBox.x}px.`,
    );
    // The button sits to the right of the input, on the same row: its
    // left edge is past the input's left edge, and its vertical span
    // overlaps the input's vertical span.
    expect(filterButtonBox.x).toBeGreaterThan(filterInputBox.x);
    expect(filterButtonBox.y).toBeLessThan(filterInputBox.y + filterInputBox.height);
    expect(filterButtonBox.y + filterButtonBox.height).toBeGreaterThan(filterInputBox.y);
    const filterInputMinHeight = await users
      .filterInput()
      .evaluate((element) => parseFloat(getComputedStyle(element).minHeight));
    console.log(`issue #374: the filter input's minimum height is ${filterInputMinHeight}px.`);
    expect(filterInputMinHeight).toBeGreaterThanOrEqual(44);
    await page.setViewportSize({ width: 1280, height: 720 });
  });

  test(
    'the banner reserves no band when it is empty, and keeps the breadcrumb clickable ' +
      'when it shows (issue #375, the audit finding D7, the correction round of ' +
      'pull request #404)',
    async ({ page }) => {
      const users = new UsersPage(page);
      const breadcrumb = new BreadcrumbPart(page);

      await users.goto(appId);
      await breadcrumb.expectLabels(['Apps', 'demo'], CHAIN_WAIT_MS);

      // The gap between the header band and the breadcrumb, with no error:
      // acceptance criterion 1 asks for no blank band.
      const gapWithNoBanner = await page.evaluate(() => {
        const band = document.querySelector('header.band')!.getBoundingClientRect();
        const nav = document
          .querySelector('nav[aria-label="Breadcrumb"]')!
          .getBoundingClientRect();
        return nav.top - band.bottom;
      });
      console.log(
        `issue #375: the gap between the header band and the breadcrumb is ` +
          `${gapWithNoBanner}px with no banner.`,
      );
      expect(gapWithNoBanner).toBeLessThanOrEqual(8);

      const navTopWithNoBanner = await breadcrumb.topOffset();

      // Forces the monitor API error for the next page load only: GET
      // /api/health answers 503, so the banner shows at once.
      await page.route('**/api/health', (route) => route.fulfill({ status: 503, body: '' }));
      await users.goto(appId);
      const bannerText = page.getByText('The monitor API did not answer.');
      await expect(bannerText).toBeVisible({ timeout: CHAIN_WAIT_MS });
      await breadcrumb.expectLabels(['Apps', 'demo'], CHAIN_WAIT_MS);
      const navTopWithBanner = await breadcrumb.topOffset();
      const bannerHeight = await page
        .locator('.banner-text')
        .evaluate((el) => el.getBoundingClientRect().height);
      await page.unroute('**/api/health');

      console.log(
        `issue #375: the breadcrumb nav top moves from ${navTopWithNoBanner}px to ` +
          `${navTopWithBanner}px when the banner shows, a shift of ` +
          `${navTopWithBanner - navTopWithNoBanner}px; the banner height is ` +
          `${bannerHeight}px.`,
      );
      // A banner that shows moves the content below it down by its own
      // height (the maintainer decision of the correction round), not by
      // the old 48 px band.
      expect(navTopWithBanner - navTopWithNoBanner).toBeCloseTo(bannerHeight, 0);

      // The "Apps" link stays the element at its own centre point, and it
      // stays clickable, with the banner present (BLOCKER-2 of the review
      // of pull request #404).
      const appsLink = breadcrumb.linkNamed('Apps');
      const linkIsOnTop = await appsLink.evaluate((el) => {
        const box = el.getBoundingClientRect();
        const topElement = document.elementFromPoint(
          box.left + box.width / 2,
          box.top + box.height / 2,
        );
        return topElement === el;
      });
      expect(linkIsOnTop).toBe(true);

      await appsLink.click();
      await expect(page).toHaveURL(/\/apps$/);
    },
  );

  test('the level 3 elements view of user A lists two elements with one click each', async ({
    page,
  }) => {
    const elements = new ElementsPage(page);
    await elements.gotoByUser(appId, 'amy');
    await elements.expectRow('demo.button-one', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);
    await elements.expectRow('demo.button-two', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);
    expect(await elements.rowCount()).toBe(2);

    // Issue #376: the elements table caption keeps clear space above its
    // text, at a narrow width too (390px, the issue's own check width).
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await captionTopPaddingPx(page, 'elements-caption')).toBeGreaterThanOrEqual(12);
    await page.setViewportSize({ width: 1280, height: 720 });
  });

  test('the erasure of user B through the API route removes it from both views', async ({
    page,
    request,
  }) => {
    const monitorUrl = process.env.E2E_MONITOR_URL;
    if (!monitorUrl) {
      throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
    }
    // D12: a DELETE request needs an allowed Origin header; the monitor
    // has no erasure form, so this suite calls the route directly. The
    // route erases the monitor copy only, and the poll cycle reads
    // MongoDB forward from a cursor, so it never brings user B back.
    const response = await request.delete(`/api/apps/${appId}/events?userId=ben`, {
      headers: { Origin: monitorUrl },
    });
    expect(response.status()).toBe(200);

    const users = new UsersPage(page);
    await users.goto(appId);
    await expect(users.rowOf('ben').row).toHaveCount(0);
    await users.expectRow('amy', { clicks: 2, sessions: 1, uniqueElements: 2 }, CHAIN_WAIT_MS);
    expect(await users.rowCount()).toBe(1);

    const apps = new AppsPage(page);
    await apps.goto();
    await apps.expectTotals(
      'demo',
      { clicks: 2, uniqueUsers: 1, uniqueSessions: 1 },
      CHAIN_WAIT_MS,
    );

    // Issue #394, finding D19: the "First pages" header and link stay on
    // one line, at the issue's own narrow width (390px). `lineCount` reads
    // a `Range` of the text (one rect for one line), not the element's own
    // box, because the header is a table cell and the link is a flex
    // container: `Element.getClientRects` gives one rect for either kind
    // of box even while its text wraps inside it.
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await lineCount(apps.firstPagesHeader)).toBe(1);
    expect(await lineCount(apps.firstPagesLinkOf('demo'))).toBe(1);

    // Issue #394, implementation step 3: the table still scrolls
    // sideways at this narrow width, instead of shrinking the column.
    const scrollWidths = await page
      .locator('.table-scroll')
      .evaluate((element) => [element.scrollWidth, element.clientWidth]);
    expect(scrollWidths[0]).toBeGreaterThan(scrollWidths[1]);

    await page.setViewportSize({ width: 1280, height: 720 });
  });

  test('the not-found page shows its sentence and the link to the apps', async ({ page }) => {
    await page.goto('/no-such-page');
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expect(page.getByText('This page does not exist.')).toBeVisible();
    await page.getByRole('link', { name: 'Go to the apps' }).click();
    await expect(page).toHaveURL(/\/apps$/);
  });

  test('the breadcrumb names the app and leads to its users (issue #270)', async ({ page }) => {
    const breadcrumb = new BreadcrumbPart(page);

    // A direct load polls the users view's own data route only, so the
    // trail starts with the fallback label "App <id>". The wait below
    // retries until the name of one GET /api/apps/{id} arrives.
    const users = new UsersPage(page);
    await users.goto(appId);
    await breadcrumb.expectLabels(['Apps', 'demo'], CHAIN_WAIT_MS);
    await expect(breadcrumb.currentItem()).toHaveText('demo');
    await expect(breadcrumb.currentItem().getByRole('link')).toHaveCount(0);

    const elements = new ElementsPage(page);
    await elements.gotoByUser(appId, 'amy');
    await breadcrumb.expectLabels(['Apps', 'demo', 'User amy'], CHAIN_WAIT_MS);
    await expect(breadcrumb.linkNamed('demo')).toHaveAttribute(
      'href',
      new RegExp(`/apps/${appId}/users$`),
    );

    await breadcrumb.linkNamed('demo').click();
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
  });

  test('the refresh line keeps a gap above the table', async ({ page }) => {
    const elements = new ElementsPage(page);
    await elements.gotoByUser(appId, 'amy');
    // Waits for the real table render, not the loading text or the
    // empty state, before the bounding boxes are read (issue #271).
    await elements.expectRow('demo.button-one', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);

    const gap = await elements.gapAbovePanel();
    expect(gap).toBeGreaterThanOrEqual(16);
  });

  // Issue #278, design decision D49: the tracker sends the raw page path;
  // it holds no routes option. #275 is not yet on main, so the demo app
  // still has its typed server pattern list "/" only (Application.kt,
  // DEMO_PATH_PATTERNS). A start on the root path still gives "/"; a
  // start on an unknown path still gives "/other" (the correction of the
  // brief review of 2026-09-28, note 4).
  test(
    'the tracker sends the raw path and the kit stores the pattern (design decision D49)',
    async ({ page, newTrackedContext }) => {
      const demoUrl = process.env.E2E_DEMO_URL;
      if (!demoUrl) {
        throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
      }

      // A cookie set before the page load reaches the server with the
      // session-start request too: that request goes out at once, inside
      // the tracker's own start() call, before the page can select a demo
      // user of its own (D19 off, no cookie means an anonymous, dropped
      // batch).
      const rootContext = await newTrackedContext();
      await rootContext.addCookies([{ name: 'demo_user', value: 'cleo', url: demoUrl }]);
      const rootPage = await rootContext.newPage();
      const demoRoot = new DemoPage(rootPage);
      await demoRoot.goto();

      const html = await rootPage.content();
      expect(html).not.toContain('routes');

      // The demo app serves one route, "/" (Application.kt): a page load
      // at another path never reaches it. This suite serves the same page
      // body under that other URL itself, so the real tracker script
      // still runs, with location.pathname set to that other path.
      const otherContext = await newTrackedContext();
      await otherContext.addCookies([{ name: 'demo_user', value: 'cleo', url: demoUrl }]);
      const otherPage = await otherContext.newPage();
      await otherPage.route('**/anything', async (route) => {
        const response = await route.fetch({ url: `${demoUrl}/` });
        await route.fulfill({ response });
      });
      await otherPage.goto(`${demoUrl}/anything`);

      const firstPages = new FirstPagesPage(page);
      await firstPages.goto(appId);
      await firstPages.expectSessions('/', 1, CHAIN_WAIT_MS);
      await firstPages.expectSessions('/other', 1, CHAIN_WAIT_MS);
    },
  );

  // Issue #275, design decision D46: the kit derives its own route
  // pattern list from the routes of the demo app; the app types no
  // list of its own (Application.kt, DEMO_INGEST_SETTINGS). The demo
  // app now serves the parametrized page `/items/{id}`
  // (`StaticPage.kt`, `DemoItemPage`); the kit derives the pattern
  // `/items/:id` for it.
  test(
    'a click on a parametrized route stores its pattern (design decision D46)',
    async ({ page, newTrackedContext }) => {
      const demoUrl = process.env.E2E_DEMO_URL;
      if (!demoUrl) {
        throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
      }

      const itemContext = await newTrackedContext();
      await itemContext.addCookies([{ name: 'demo_user', value: 'amy', url: demoUrl }]);
      const itemPage = new DemoPage(await itemContext.newPage());
      await itemPage.gotoItem('42');
      await itemPage.clickItemView();

      // The session start of the page load of /items/42 carries the raw
      // path; the kit matches it against its derived pattern list and
      // stores /items/:id, never the raw path (contract rule C42).
      const firstPages = new FirstPagesPage(page);
      await firstPages.goto(appId);
      await firstPages.expectSessions('/items/:id', 1, CHAIN_WAIT_MS);

      // Issue #376: the first-pages table caption keeps clear space
      // above its text.
      expect(await captionTopPaddingPx(page, 'first-pages-caption')).toBeGreaterThanOrEqual(12);
    },
  );

  // Issue #277, issue #297: the two scenarios of design decision D47
  // ("the app publishes its route list" and "the manage view shows the
  // list the app published") lived here. The hosted model closes D46 to
  // D48 (the owner's decision of 2026-09-28, the maintainer's note on
  // issue #297 of 2026-10-01): the kit's route pattern publication, and
  // the manage view's read of it, have no caller left in the hosted
  // model. This removes the two scenarios; #277 and #304 record the
  // closure.
});

// Issue #348: the users view of an app id that no app ever used. This
// scenario names no app of its own, so it needs no serial order against
// the "demo" app chain above.
test(
  'the users view of an unknown app shows the not-found card, and the breadcrumb carries no App <id> label (issue #348)',
  async ({ page }) => {
    const users = new UsersPage(page);
    await users.goto(999);

    await expect(page.getByText('The app is not registered.')).toBeVisible();
    const link = page.getByRole('link', { name: 'Go to the apps' });
    await expect(link).toBeVisible();

    const breadcrumb = new BreadcrumbPart(page);
    await breadcrumb.expectAbsent(CHAIN_WAIT_MS);

    await link.click();
    await expect(page).toHaveURL(/\/apps$/);
  },
);

// Issue #296 (D51, D52, D57): the hosted registration path. This
// scenario names its own app, so it needs no serial order against the
// "demo" app chain above.
test('an app is registered and its keys show one time (issue #296)', async ({ page }) => {
  const manage = new ManagePage(page);
  await manage.goto();
  const appName = `keys-app-${Date.now()}`;
  await manage.addApp({ name: appName, origins: ['https://a.example', 'https://b.example'] });

  const appKeyField = manage.appKeyField();
  const secretKeyField = manage.secretKeyField();
  await expect(appKeyField).toBeVisible();
  await expect(secretKeyField).toBeVisible();
  const firstAppKey = await appKeyField.inputValue();
  const firstSecretKey = await secretKeyField.inputValue();
  expect(firstAppKey.length).toBeGreaterThan(0);
  expect(firstSecretKey.length).toBeGreaterThan(0);
  await expect(manage.scriptTag()).toContainText(`data-app-key="${firstAppKey}"`);

  // A reload of the list shows the app with the short app key, the
  // origins, and no secret key panel any more: that panel is the
  // one-time display of the add form, not a stored value.
  await manage.goto();
  const row = manage.row(appName);
  await expect(row).toBeVisible();
  await expect(manage.appKeyCell(row)).toHaveText(firstAppKey.slice(0, 8));
  await expect(manage.originCells(row)).toHaveText(['https://a.example', 'https://b.example']);

  // "Edit app" adds a third origin, and the list shows three.
  await manage.openEditForm(row);
  await manage.addOriginAndSave(row, 'https://c.example');
  await expect(manage.originCells(row)).toHaveText([
    'https://a.example',
    'https://b.example',
    'https://c.example',
  ]);

  // "New secret key" shows a new key that differs from the first.
  const newSecretKeyField = await manage.rotateSecretKey(row);
  const newSecretKey = await newSecretKeyField.inputValue();
  expect(newSecretKey.length).toBeGreaterThan(0);
  expect(newSecretKey).not.toBe(firstSecretKey);

  // The API answer of GET /api/apps holds no secretKey field.
  const response = await page.request.get('/api/apps');
  expect(response.status()).toBe(200);
  const rows = (await response.json()) as ReadonlyArray<Record<string, unknown>>;
  const createdRow = rows.find((r) => r['name'] === appName);
  if (!createdRow) {
    throw new Error(`The app "${appName}" is absent from the answer of GET /api/apps.`);
  }
  expect(createdRow).not.toHaveProperty('secretKey');
});

// Issue #364, finding D3 of the UI audit: Edit app opened an inline form
// inside the Actions cell, which grew the row height and pushed each row
// below it down by the same amount. The form now opens in a native
// `<dialog>`, over the top layer, so a row keeps its own height and the
// row below keeps its own position. This scenario measures both numbers
// in a real Chromium layout, before and during the open dialog, and
// again after a save.
//
// Each app registers through the legacy connection-string path (D32),
// against the harness's own compose mongo, the same path test "the
// manage form registers the demo app" uses for "demo": a real,
// reachable database gives the status "Not polled yet" or "OK", with
// no error-text line. The origins path leaves the app with no real
// database; a later poll then flags it "Overprivileged" with an
// error-text line, which grows the row for a reason that has nothing
// to do with the dialog (found while this scenario was written: the
// first version used the origins path and failed on that unrelated
// growth).
test('Edit app opens a dialog, with no row growth and no shift of the row below (issue #364, D3)', async ({
  page,
  request,
}) => {
  const mongoPort = process.env.E2E_MONGO_PORT;
  const monitorUrl = process.env.E2E_MONITOR_URL;
  if (!mongoPort) {
    throw new Error('E2E_MONGO_PORT is absent. The global setup did not run, or it failed.');
  }
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }

  const manage = new ManagePage(page);
  const marker = Date.now();
  const firstName = `edit-dialog-a-${marker}`;
  const secondName = `edit-dialog-b-${marker}`;
  await manage.addAppWithConnectionString(
    request,
    monitorUrl,
    firstName,
    `mongodb://127.0.0.1:${mongoPort}/exampledb_edit_dialog_a_${marker}`,
  );
  await manage.addAppWithConnectionString(
    request,
    monitorUrl,
    secondName,
    `mongodb://127.0.0.1:${mongoPort}/exampledb_edit_dialog_b_${marker}`,
  );

  // Waits, through the API, for the first poll cycle of each app to
  // answer (issue #364 confound). The manage view reads the list one
  // time on load, and again only after an action (the edit save counts
  // as one); it runs no poll timer of its own. A wait on the rendered
  // badge text would never resolve here: the page shows the "Not
  // polled yet" text of the load it already did, with no later
  // refetch to update it. The edit save below does trigger one
  // refetch; without this wait first, that refetch can still turn
  // "Not polled yet" into a different status for a row, change its
  // badge, and move the row below for a reason outside the dialog.
  // The harness's own compose Mongo gives each app the status
  // OVERPRIVILEGED (no per-app credential, so the connection reaches
  // every database); once the first poll sets that status, each later
  // poll of the same connection string gives the same verdict, so the
  // status, and the badge, stay the same for the rest of the scenario.
  await waitForFirstPoll(request, firstName, CHAIN_WAIT_MS);
  await waitForFirstPoll(request, secondName, CHAIN_WAIT_MS);

  await manage.goto();
  const firstRow = manage.row(firstName);
  const secondRow = manage.row(secondName);
  await expect(firstRow).toBeVisible();
  await expect(secondRow).toBeVisible();
  await expect(manage.statusBadge(firstRow)).not.toHaveText(/Not polled yet/);
  await expect(manage.statusBadge(secondRow)).not.toHaveText(/Not polled yet/);

  const firstRowBoxBefore = await firstRow.boundingBox();
  const secondRowBoxBefore = await secondRow.boundingBox();
  if (!firstRowBoxBefore || !secondRowBoxBefore) {
    throw new Error('A row of the manage table has no box.');
  }

  // Opens the dialog of the first row, and proves it is a real modal
  // dialog (`showModal()`, not a plain `open` attribute toggle): the CSS
  // pseudo-class `:modal` matches only the top layer, modal case.
  await manage.openEditForm(firstRow);
  await expect(page.locator('dialog:modal')).toHaveCount(1);
  await expect(manage.editDialog(firstRow)).toHaveCount(1);

  const firstRowBoxDuring = await firstRow.boundingBox();
  const secondRowBoxDuring = await secondRow.boundingBox();
  if (!firstRowBoxDuring || !secondRowBoxDuring) {
    throw new Error('A row of the manage table has no box, with the dialog open.');
  }
  console.log(
    `issue #364: the row height is ${firstRowBoxBefore.height}px before the dialog opens ` +
      `and ${firstRowBoxDuring.height}px while it is open; the row below sits at ` +
      `${secondRowBoxBefore.y}px before and ${secondRowBoxDuring.y}px while the dialog is open.`,
  );
  expect(firstRowBoxDuring.height).toBe(firstRowBoxBefore.height);
  expect(secondRowBoxDuring.y).toBe(secondRowBoxBefore.y);

  // The rename keeps the exact same length as firstName: a longer name
  // can wrap onto a second line in the narrow App column, which would
  // grow the row for a reason that has nothing to do with the dialog.
  const renamedTo = firstName.replace('edit-dialog-a-', 'edit-dialog-r-');
  await manage.renameAndSave(firstRow, renamedTo);
  const renamedRow = manage.row(renamedTo);
  await expect(renamedRow).toBeVisible();

  const secondRowBoxAfter = await secondRow.boundingBox();
  if (!secondRowBoxAfter) {
    throw new Error('The row below has no box after the save.');
  }
  expect(secondRowBoxAfter.y).toBe(secondRowBoxBefore.y);
});

/** A fixed, well-formed UUID for one test request's `sessionId` field (the form of `collector.spec.ts`). */
function pagerSessionId(marker: number): string {
  const hex = marker.toString(16).padStart(8, '0');
  return `${hex}-0000-4000-8000-000000000000`;
}

/**
 * Registers one fresh app for a pager scenario. Each of the two split
 * scenarios below calls this once, with its own app name, so neither
 * scenario reads a row count that the other scenario wrote (MAJOR 4 of
 * the review of pull request #405: the split removes the shared state
 * that the one combined scenario held).
 */
async function registerPagerApp(
  request: APIRequestContext,
  monitorUrl: string,
  namePrefix: string,
): Promise<{ appId: number; appKey: string; origin: string }> {
  const origin = 'https://pager-e2e.example';
  const appName = `${namePrefix}-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: [origin] },
  });
  expect(createResponse.status()).toBe(201);
  const created = (await createResponse.json()) as { appId: number; appKey: string };
  return { appId: created.appId, appKey: created.appKey, origin };
}

/**
 * Sends one request that gives one user row (a click event, kind 0) and
 * one first-pages row (a session-start event, kind 1) at once, through
 * the collector route (never a mock), the same way `collector.spec.ts`
 * does. A user row needs a real click: a session-start event alone
 * gives that user no row at all (`UserTotalsRouteTest.kt`, "a user with
 * only a session-start row gets no row at all").
 */
async function sendUserAndPage(
  request: APIRequestContext,
  monitorUrl: string,
  origin: string,
  appKey: string,
  marker: number,
): Promise<void> {
  const response = await request.fetch(`${monitorUrl}/collect`, {
    method: 'POST',
    headers: { Origin: origin, 'Content-Type': 'application/json', 'X-Octometer-Key': appKey },
    data: JSON.stringify({
      sessionId: pagerSessionId(marker),
      userId: `pager-user-${marker}`,
      clicks: [
        { element: 'octo:session-start', ageMs: 0, path: `/pager-page-${marker}` },
        { element: 'pager-probe.click', ageMs: 0 },
      ],
    }),
  });
  expect(response.status()).toBe(204);
}

/** Reads `E2E_MONITOR_URL`, or fails with a clear reason. */
function readMonitorUrl(): string {
  const monitorUrl = process.env.E2E_MONITOR_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  return monitorUrl;
}

/**
 * Issue #387 (D15 of the UI audit of 2026-10-09): the pager of the
 * users view, the first-pages view, and the anonymous-sessions view
 * shows only above one page. This scenario writes one real row through
 * the collector route (never a mock), so the page count comes from the
 * store. The two-page scenario below is a separate test (MAJOR 4 of the
 * review of pull request #405), so this one keeps a small time budget.
 */
test('the pager hides for a one-page result (issue #387, D15)', async ({ page, request }) => {
  const monitorUrl = readMonitorUrl();
  const { appId, appKey, origin } = await registerPagerApp(request, monitorUrl, 'pager-one-app');
  await sendUserAndPage(request, monitorUrl, origin, appKey, 1);

  const users = new UsersPage(page);
  await users.goto(appId);
  await users.expectRow('pager-user-1', { clicks: 1, sessions: 1, uniqueElements: 1 }, CHAIN_WAIT_MS);
  await expect(page.locator('nav.pager')).toHaveCount(0);

  const firstPages = new FirstPagesPage(page);
  await firstPages.goto(appId);
  await firstPages.expectSessions('/pager-page-1', 1, CHAIN_WAIT_MS);
  await expect(page.locator('nav.pager')).toHaveCount(0);
});

/**
 * Issue #387 (D15 of the UI audit of 2026-10-09): 51 distinct users and
 * 51 distinct first pages give a page count of 2 (the page size is 50
 * rows, `UserTotalsRoute.kt`, `FirstPagesRoute.kt`). The pager then
 * shows on the users view and on the first-pages view, with no
 * `aria-current` on the page-label span. A split scenario of its own
 * (MAJOR 4 of the review of pull request #405) keeps the 51 requests
 * and the two `toContainText` waits inside one 60 s test budget, with no
 * one-page work ahead of them.
 */
test('the pager shows for a two-page result, with no aria-current (issue #387, D15)', async ({
  page,
  request,
}) => {
  const monitorUrl = readMonitorUrl();
  const { appId, appKey, origin } = await registerPagerApp(request, monitorUrl, 'pager-two-app');
  for (let marker = 1; marker <= 51; marker += 1) {
    await sendUserAndPage(request, monitorUrl, origin, appKey, marker);
  }

  const users = new UsersPage(page);
  await users.goto(appId);
  await expect(page.locator('nav.pager')).toContainText('Page 1 of 2', { timeout: CHAIN_WAIT_MS });
  await expect(page.locator('nav.pager [aria-current]')).toHaveCount(0);

  const firstPages = new FirstPagesPage(page);
  await firstPages.goto(appId);
  await expect(page.locator('nav.pager')).toContainText('Page 1 of 2', { timeout: CHAIN_WAIT_MS });
  await expect(page.locator('nav.pager [aria-current]')).toHaveCount(0);
});

// Issue #392 (D18 of the UI audit of 2026-10-09): the elements caption
// names the subject of the selected view. This scenario writes one real
// click through the collector route (never a mock), with a known
// sessionId, and opens the elements view filtered to that session.
test('the elements caption names "this session" on a session view (issue #392, D18)', async ({
  page,
  request,
}) => {
  const monitorUrl = readMonitorUrl();
  const { appId, appKey, origin } = await registerPagerApp(
    request,
    monitorUrl,
    'session-caption-app',
  );
  const marker = 392;
  await sendUserAndPage(request, monitorUrl, origin, appKey, marker);

  const elements = new ElementsPage(page);
  await elements.gotoBySession(appId, pagerSessionId(marker));
  await elements.expectRow('pager-probe.click', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);
  await expect(elements.caption()).toHaveText(
    'The elements that this session touched, with the click totals of each element.',
    { timeout: CHAIN_WAIT_MS },
  );
});

/** A fixed, well-formed UUID for one anonymous-session marker (issue #368, D4). */
function anonymousSessionId(marker: number): string {
  const hex = (marker + 0x10000).toString(16).padStart(8, '0');
  return `${hex}-0000-4000-8000-000000000000`;
}

/**
 * Sends one session-start event with no user id, so the session counts
 * as anonymous (`SessionsRoute.kt`). It reuses the path of the matching
 * `sendUserAndPage` marker, so the first-pages view still counts 51
 * distinct paths, not 102 (issue #368, D4).
 */
async function sendAnonymousSessionOfSamePath(
  request: APIRequestContext,
  monitorUrl: string,
  origin: string,
  appKey: string,
  marker: number,
): Promise<void> {
  const response = await request.fetch(`${monitorUrl}/collect`, {
    method: 'POST',
    headers: { Origin: origin, 'Content-Type': 'application/json', 'X-Octometer-Key': appKey },
    data: JSON.stringify({
      sessionId: anonymousSessionId(marker),
      clicks: [{ element: 'octo:session-start', ageMs: 0, path: `/pager-page-${marker}` }],
    }),
  });
  expect(response.status()).toBe(204);
}

/**
 * The rendered height of one control, in CSS px (issue #368, D4). This
 * suite runs in real Chromium, so a bounding box gives the painted
 * height, the number of a real touch target, not only the declared
 * `min-height` value.
 */
async function controlHeightPx(locator: Locator): Promise<number> {
  const box = await locator.boundingBox();
  if (box === null) {
    throw new Error('The control has no bounding box; it is not visible.');
  }
  return box.height;
}

/**
 * Issue #368 (D4 of the UI audit of 2026-10-09): the Filter button of
 * the users view, and each pager button of the users view, the sessions
 * view, and the first-pages view, render at a minimum height of 44 px.
 * The audit measured about 27 px before the fix: `.filter-form button`
 * and `.pager button` each carried a `min-height: 24px` rule of a
 * higher selector specificity than `.btn`. This scenario checks the
 * 1280 px width and the 390 px width, the issue's own two check widths.
 */
test('the Filter button and the pager buttons meet the 44px minimum height (issue #368, D4)', async ({
  page,
  request,
}) => {
  const monitorUrl = readMonitorUrl();
  const { appId, appKey, origin } = await registerPagerApp(request, monitorUrl, 'control-height-app');
  for (let marker = 1; marker <= 51; marker += 1) {
    await sendUserAndPage(request, monitorUrl, origin, appKey, marker);
    await sendAnonymousSessionOfSamePath(request, monitorUrl, origin, appKey, marker);
  }

  const users = new UsersPage(page);
  const sessions = new SessionsPage(page);
  const firstPages = new FirstPagesPage(page);
  const pagerButtons = page.locator('nav.pager button');

  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 800 });

    await users.goto(appId);
    await expect(page.locator('nav.pager')).toContainText('Page 1 of 2', { timeout: CHAIN_WAIT_MS });
    const filterHeight = await controlHeightPx(page.getByRole('button', { name: 'Filter' }));
    expect(filterHeight).toBeGreaterThanOrEqual(44);
    await expect(pagerButtons).toHaveCount(2);
    for (const button of await pagerButtons.all()) {
      expect(await controlHeightPx(button)).toBeGreaterThanOrEqual(44);
    }

    await sessions.goto(appId);
    await expect(page.locator('nav.pager')).toContainText('Page 1 of 2', { timeout: CHAIN_WAIT_MS });
    await expect(pagerButtons).toHaveCount(2);
    for (const button of await pagerButtons.all()) {
      expect(await controlHeightPx(button)).toBeGreaterThanOrEqual(44);
    }

    await firstPages.goto(appId);
    await expect(page.locator('nav.pager')).toContainText('Page 1 of 2', { timeout: CHAIN_WAIT_MS });
    await expect(pagerButtons).toHaveCount(2);
    for (const button of await pagerButtons.all()) {
      expect(await controlHeightPx(button)).toBeGreaterThanOrEqual(44);
    }
  }
});

// Issue #377, finding D9 of the UI audit: the table sits close to the
// panel border, with no empty strip below the last row.
test('the manage table has no bottom margin below the last row (issue #377)', async ({ page }) => {
  const manage = new ManagePage(page);
  await manage.goto();
  await expect(manage.tableScroll()).toHaveCSS('margin-bottom', '0px');
});

// Issue #362, finding D2 of the UI audit: the three Manage actions sit in
// one row at a narrow width, with no stack into separate rows. The table
// scrolls inside `.table-scroll` instead.
test('the manage actions cell holds one row at 390px width (issue #362)', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  const manage = new ManagePage(page);
  await manage.goto();
  const row = manage.row('demo');
  const actionRow = manage.actionRow(row);
  const box = await actionRow.boundingBox();
  if (!box) {
    throw new Error('The action row of the "demo" row has no box.');
  }
  // One row of 44px-tall buttons, plus padding, stays under 60px; three
  // stacked rows measure about 160px (the audit number of finding D2).
  expect(box.height).toBeLessThan(60);

  // The table scrolls inside `.table-scroll` instead of shrinking its
  // columns, so `.table-scroll` holds more content than it shows.
  const scrollWidths = await manage.tableScroll().evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
  }));
  expect(scrollWidths.scrollWidth).toBeGreaterThan(scrollWidths.clientWidth);
});

// Issue #389, finding D29 of the UI audit: the gap above the add-app
// heading matches the gap below the submit button, within 2px.
test('the add-app heading has no top margin (issue #389)', async ({ page }) => {
  const manage = new ManagePage(page);
  await manage.goto();
  await expect(manage.addAppHeading()).toHaveCSS('margin-top', '0px');

  const gaps = await page.evaluate(() => {
    const panel = document.querySelector('.add-app') as HTMLElement;
    const heading = panel.querySelector('h2') as HTMLElement;
    const button = panel.querySelector('button[type="submit"]') as HTMLElement;
    const panelRect = panel.getBoundingClientRect();
    const headingRect = heading.getBoundingClientRect();
    const buttonRect = button.getBoundingClientRect();
    return {
      topGap: headingRect.top - panelRect.top,
      bottomGap: panelRect.bottom - buttonRect.bottom,
    };
  });
  expect(Math.abs(gaps.topGap - gaps.bottomGap)).toBeLessThanOrEqual(2);
});

// Issue #393, finding D30 of the UI audit: the delete-confirm button
// shows "Deleting…" with aria-busy="true" while the delete request is
// in flight. The route below holds the DELETE answer open, so the
// pending state stays visible long enough for the assertions; the real
// request can finish in under one render frame.
test('the delete-confirm button shows a Deleting label while the request is pending (issue #393, D30)', async ({
  page,
  request,
}) => {
  const monitorUrl = readMonitorUrl();
  const appName = `delete-pending-app-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: ['https://delete-pending.example'] },
  });
  expect(createResponse.status()).toBe(201);

  const manage = new ManagePage(page);
  await manage.goto();
  const row = manage.row(appName);
  await expect(row).toBeVisible();

  let releaseDelete = () => {};
  const deleteHeld = new Promise<void>((resolve) => {
    releaseDelete = resolve;
  });
  await page.route('**/api/apps/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await route.fallback();
      return;
    }
    await deleteHeld;
    const response = await route.fetch();
    await route.fulfill({ response });
  });

  await manage.openDeleteDialog(row);
  await manage.deleteConfirmNameField(row).fill(appName);
  const confirmButton = manage.deleteConfirmButton(row);
  const cancelButton = row.getByRole('button', { name: 'Cancel' });
  await expect(confirmButton).toHaveText('Delete this app');
  await expect(confirmButton).not.toHaveAttribute('aria-busy');

  // MAJOR 2 of the review of pull request #428: the confirm button
  // keeps the width of its longer, rest-state label, so Cancel does
  // not move under the pointer once the pending label shrinks it.
  const restConfirmBox = await confirmButton.boundingBox();
  const restCancelBox = await cancelButton.boundingBox();
  if (!restConfirmBox || !restCancelBox) {
    throw new Error('The confirm button or the Cancel button has no box in the rest state.');
  }

  await confirmButton.click();
  await expect(confirmButton).toHaveText('Deleting…');
  await expect(confirmButton).toHaveAttribute('aria-busy', 'true');
  await expect(confirmButton).toBeDisabled();

  const pendingConfirmBox = await confirmButton.boundingBox();
  const pendingCancelBox = await cancelButton.boundingBox();
  if (!pendingConfirmBox || !pendingCancelBox) {
    throw new Error('The confirm button or the Cancel button has no box in the pending state.');
  }
  console.log(
    `issue #393, MAJOR 2: the confirm button width is ${restConfirmBox.width}px at rest ` +
      `and ${pendingConfirmBox.width}px pending; the Cancel button left x is ` +
      `${restCancelBox.x}px at rest and ${pendingCancelBox.x}px pending.`,
  );
  expect(Math.abs(pendingConfirmBox.width - restConfirmBox.width)).toBeLessThanOrEqual(1);
  expect(Math.abs(pendingCancelBox.x - restCancelBox.x)).toBeLessThanOrEqual(1);

  // MAJOR 3 of the review of pull request #428: pull request #410
  // gives `.btn[disabled]` the shallows fill and the ink-soft text, in
  // place of the old opacity rule, so the pending label stays at an
  // accessible contrast with no rule of its own. The `opacity: 1`
  // check documents that #410 state (lesson 24 of the frontend brief:
  // a contrast check beside an explicit opacity assertion, since
  // `getComputedStyle` colors alone ignore a faded element).
  await expect(confirmButton).toHaveCSS('opacity', '1');
  const pendingContrast = await disabledButtonContrastRatio(confirmButton);
  console.log(`issue #393, MAJOR 3: the pending label contrast is ${pendingContrast.toFixed(2)}:1.`);
  expect(pendingContrast).toBeGreaterThanOrEqual(4.5);

  releaseDelete();
  await expect(row.locator('dialog[aria-labelledby^="delete-app-heading"]')).toBeHidden();
  await page.unroute('**/api/apps/*');

  // Reads the stored state, not only the page text: the app is truly
  // gone from the registry's own answer (lesson 19 of the frontend
  // brief).
  const afterResponse = await request.get('/api/apps');
  const afterRows = (await afterResponse.json()) as ReadonlyArray<{ name: string }>;
  expect(afterRows.some((r) => r.name === appName)).toBe(false);
});

/**
 * Reads the border radius and the box shadow of one control (issue
 * #360, finding D11). Chromium gives back a plain px string and a
 * plain `rgb(...) Npx Npx 0px 0px` string here (real Chromium, not
 * jsdom), so a direct string check is exact.
 */
async function controlLook(locator: Locator): Promise<{
  radius: string;
  shadow: string;
}> {
  return locator.evaluate((element) => ({
    radius: getComputedStyle(element).borderRadius,
    shadow: getComputedStyle(element).boxShadow,
  }));
}

// Issue #360, finding D11 of the UI audit: one pill family (a
// full-round radius, no shadow) and one button family (a 12px radius,
// a hard shadow), with no third look on screen. "Sign out" moves into
// its own account menu, clear of the row of nav pills.
test('the nav pills, the "Pause refresh" pill, and the action buttons each keep one family (issue #360)', async ({
  page,
}) => {
  const apps = new AppsPage(page);
  await apps.goto();

  const navPill = page
    .locator('nav[aria-label="Main"]')
    .getByRole('link', { name: 'Apps', exact: true });
  const pausePill = page.getByRole('button', { name: 'Pause refresh' });
  const signOutButton = page.locator('.account-menu').getByRole('button', { name: 'Sign out' });

  const navPillLook = await controlLook(navPill);
  const pausePillLook = await controlLook(pausePill);
  const signOutLook = await controlLook(signOutButton);

  // The two pills share one radius and one shadow (none).
  expect(pausePillLook).toEqual(navPillLook);
  expect(navPillLook.shadow).toBe('none');

  // "Sign out" keeps the other family: a different radius, and a
  // shadow that is not "none".
  expect(signOutLook.radius).not.toBe(navPillLook.radius);
  expect(signOutLook.shadow).not.toBe('none');

  // A second action button, on a different page, keeps the same
  // button look as "Sign out".
  const manage = new ManagePage(page);
  await manage.goto();
  const addAppButton = page.getByRole('button', { name: 'Add app' });
  const addAppLook = await controlLook(addAppButton);
  expect(addAppLook).toEqual(signOutLook);

  // "Sign out" sits in its own row, clear of the row of nav pills: its
  // box does not share a y range with the nav pill's box.
  await apps.goto();
  const navBox = await navPill.boundingBox();
  const signOutBox = await page
    .locator('.account-menu')
    .getByRole('button', { name: 'Sign out' })
    .boundingBox();
  if (!navBox || !signOutBox) {
    throw new Error('The nav pill or the "Sign out" button has no box.');
  }
  expect(signOutBox.y).toBeGreaterThanOrEqual(navBox.y + navBox.height);
});

// Issue #361, finding D1 of the UI audit: the dashboard shell uses the
// dark tokens under `prefers-color-scheme: dark`, not the bright page.
// Each contrast assertion sits beside an `opacity` assertion (lesson 24
// of the frontend brief): a faded control passes a naive contrast
// check, so this test reads the real `opacity` value next to each
// contrast measurement, never the contrast alone.
test('the dashboard shell uses the dark tokens under prefers-color-scheme: dark (issue #361)', async ({
  page,
}) => {
  await page.emulateMedia({ colorScheme: 'dark' });

  const apps = new AppsPage(page);
  await apps.goto();

  // The body keeps the dark `--shallows` token (#14122a), not the
  // bright page (#e6f3ff).
  const body = page.locator('body');
  await expect(body).toHaveCSS('background-color', 'rgb(20, 18, 42)');
  await expect(body).toHaveCSS('opacity', '1');
  const bodyColor = await body.evaluate((element) => getComputedStyle(element).color);
  expect(ratio(toHex(bodyColor), '#14122a')).toBeGreaterThanOrEqual(4.5);

  // The band label pair (MAJOR 1 of the review of pull request #435):
  // `--paper-bright` keeps the plain nav pill ("Manage apps") readable
  // on the octo band in the dark scheme too, not dark text on a dark
  // fill.
  const manageAppsPill = page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Manage apps' });
  await expect(manageAppsPill).toBeVisible();
  await expect(manageAppsPill).toHaveCSS('opacity', '1');
  const pillColors = await manageAppsPill.evaluate((element) => {
    const style = getComputedStyle(element);
    return { fg: style.color, bg: style.backgroundColor, edge: style.borderTopColor };
  });
  expect(ratio(toHex(pillColors.fg), toHex(pillColors.bg))).toBeGreaterThanOrEqual(4.5);

  // The control edge pair (MAJOR 3 of the review): `.band` sets its own
  // `--line-control`, so the pill edge still reads 3:1 on the octo band
  // fill in the dark scheme, not the page's own dark `--ink`.
  const bandFill = await page
    .locator('header.band')
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(ratio(toHex(pillColors.edge), toHex(bandFill))).toBeGreaterThanOrEqual(3);

  // The "demo" app badge: the mark and the word of status-format.ts keep
  // 4.5:1 on their fill in the dark scheme too (the badge text is
  // `--text-s`, never a large text).
  const badge = apps.rowOf('demo').status.locator('.badge');
  await expect(badge).toBeVisible();
  await expect(badge).toHaveCSS('opacity', '1');
  const badgeColors = await badge.evaluate((element) => {
    const style = getComputedStyle(element);
    return { fg: style.color, bg: style.backgroundColor };
  });
  expect(ratio(toHex(badgeColors.fg), toHex(badgeColors.bg))).toBeGreaterThanOrEqual(4.5);

  // The disabled "Delete this app" button of the delete dialog
  // (`.btn[disabled]`, issue #384/#410): a shallows fill, an ink-soft
  // text, a dashed border, and no opacity change. This reads the real
  // `opacity` (it must stay `1`, not a faded value) next to the
  // contrast of the dark-scheme pair.
  const manage = new ManagePage(page);
  await manage.goto();
  const row = manage.row('demo');
  await manage.openDeleteDialog(row);
  const confirmButton = manage.deleteConfirmButton(row);
  await expect(confirmButton).toBeVisible();
  await expect(confirmButton).toBeDisabled();
  await expect(confirmButton).toHaveCSS('border-style', 'dashed');
  await expect(confirmButton).toHaveCSS('opacity', '1');
  expect(await disabledButtonContrastRatio(confirmButton)).toBeGreaterThanOrEqual(4.5);
  await manage.cancelDeleteDialog(row);
});

/**
 * The left gap and the right gap of the icon-and-label group inside the
 * "Pause refresh" pill, in CSS px (issue #363). A hidden check mark once
 * added about 14 px of blank space on the left only, so the label sat
 * off-center.
 */
async function pauseButtonContentGapsPx(
  button: Locator,
): Promise<{ left: number; right: number }> {
  const [buttonBox, iconBox, labelBox] = await Promise.all([
    button.boundingBox(),
    button.locator('.icon').boundingBox(),
    button.locator('span:not(.icon)').boundingBox(),
  ]);
  if (!buttonBox || !iconBox || !labelBox) {
    throw new Error('The Pause refresh button, its icon, or its label has no box.');
  }
  const contentLeft = Math.min(iconBox.x, labelBox.x);
  const contentRight = Math.max(iconBox.x + iconBox.width, labelBox.x + labelBox.width);
  return {
    left: contentLeft - buttonBox.x,
    right: buttonBox.x + buttonBox.width - contentRight,
  };
}

// Issue #363, finding D12 of the UI audit: the "Pause refresh" label
// centers inside its pill, in each state. A hidden check mark once held
// about 14 px of blank space before the label; this scenario measures
// the left gap against the right gap of the icon-and-label group.
test('the Pause refresh label centers inside its pill, in each state (issue #363)', async ({
  page,
}) => {
  const apps = new AppsPage(page);
  await apps.goto();
  const pausePill = page.getByRole('button', { name: 'Pause refresh' });

  const gapsBeforeClick = await pauseButtonContentGapsPx(pausePill);
  console.log(
    `issue #363: before the click, the left gap is ${gapsBeforeClick.left.toFixed(2)}px, the right gap is ${gapsBeforeClick.right.toFixed(2)}px.`,
  );
  expect(Math.abs(gapsBeforeClick.left - gapsBeforeClick.right)).toBeLessThanOrEqual(1);

  await pausePill.click();
  const resumePill = page.getByRole('button', { name: 'Resume refresh' });
  const gapsAfterClick = await pauseButtonContentGapsPx(resumePill);
  console.log(
    `issue #363: after the click, the left gap is ${gapsAfterClick.left.toFixed(2)}px, the right gap is ${gapsAfterClick.right.toFixed(2)}px.`,
  );
  expect(Math.abs(gapsAfterClick.left - gapsAfterClick.right)).toBeLessThanOrEqual(1);

  // Restores the refresh for a later test of the same serial file.
  await resumePill.click();
});

// Issue #397, finding D21 of the UI audit: the header band keeps the
// brand mark, the nav pills, and the account control on one row at a
// phone width. The account-menu row of issue #433 stays as its own row
// below, so the whole header holds two rows, not three.
test('the header band holds two rows, not three, at 390px width (issue #397)', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 800 });
  const apps = new AppsPage(page);
  await apps.goto();

  const band = page.locator('header.band');
  const brand = page.locator('.band-row .brand');
  const nav = page.locator('.band-row app-main-nav nav');
  const accountButton = page.locator('.account-menu').getByRole('button', { name: 'Account' });
  const signOutButton = page.locator('.account-menu').getByRole('button', { name: 'Sign out' });

  await expect(page.locator('.account-email')).toBeHidden();
  await expect(accountButton).toBeVisible();

  const [bandBox, brandBox, navBox, accountButtonBox, signOutBox] = await Promise.all([
    band.boundingBox(),
    brand.boundingBox(),
    nav.boundingBox(),
    accountButton.boundingBox(),
    signOutButton.boundingBox(),
  ]);
  if (!bandBox || !brandBox || !navBox || !accountButtonBox || !signOutBox) {
    throw new Error('The band, the brand mark, the nav, or an account control has no box.');
  }
  console.log(
    `issue #397: the band height is ${bandBox.height}px; the brand box is y=${brandBox.y} h=${brandBox.height}, the nav box is y=${navBox.y} h=${navBox.height}, the account menu top is ${signOutBox.y}px.`,
  );

  // Two vertical ranges overlap when each box starts above the other
  // box's bottom edge; a shared row shows this overlap, even when the
  // two boxes center at different heights (a 28px icon beside a 44px
  // nav pill, both inside one 68px row).
  function sharesRow(
    a: { y: number; height: number },
    b: { y: number; height: number },
  ): boolean {
    return a.y < b.y + b.height && b.y < a.y + a.height;
  }

  // The brand mark and the nav pills share one row.
  expect(sharesRow(brandBox, navBox)).toBe(true);
  // The account button and "Sign out" share the account-menu row, with
  // no overlap against the first row (issue #433).
  expect(sharesRow(accountButtonBox, signOutBox)).toBe(true);
  expect(sharesRow(brandBox, accountButtonBox)).toBe(false);
  expect(accountButtonBox.y).toBeGreaterThanOrEqual(brandBox.y + brandBox.height);
  // Two rows of controls, each well under the 175px the issue measured
  // for three stacked rows.
  expect(bandBox.height).toBeLessThan(140);
  // The first row fits with no horizontal overflow: a `nowrap` rule
  // alone, with no room made for it, would clip or scroll instead of
  // sharing a row the right way.
  expect(await pageOverflowsX(page)).toBe(false);

  // The account button still gives the user the full email, on a press.
  await accountButton.click();
  await expect(page.locator('.account-email-popup')).toBeVisible();
});

// Issue #439, the confirmation comment of pull request #435: the mascot
// head fill on the header band keeps a 3:1 contrast against the band, so
// the graphic stays visible (WCAG 1.4.11). `opacity: 1` rules out a
// faded ancestor masking the real painted color (lesson 24 of the
// frontend brief).
test('the mascot head fill reads 3:1 or more against the header band (issue #439)', async ({
  page,
}) => {
  const apps = new AppsPage(page);
  await apps.goto();
  const head = page.locator('header.band app-octo-mark .head');
  const band = page.locator('header.band');

  await expect(head).toHaveCSS('opacity', '1');
  const [headFill, bandFill] = await Promise.all([
    head.evaluate((element) => getComputedStyle(element).fill),
    band.evaluate((element) => getComputedStyle(element).backgroundColor),
  ]);
  const ratio = contrastRatio(headFill, bandFill);
  console.log(
    `issue #439: the mascot head fill is ${headFill} on the band ${bandFill}, a ratio of ${ratio.toFixed(2)}:1.`,
  );
  expect(ratio).toBeGreaterThanOrEqual(3);
});
