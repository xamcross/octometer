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
import { DESKTOP_CHROME_USER_AGENT } from '../setup/constants';
import { STORAGE_STATE_FILE } from '../setup/state';

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
 * The style set of one hover check of issue #369 (finding D13 of the UI
 * audit): the fill, the text colour, the shadow, the position, the
 * underline, and the transition duration. This suite runs in real
 * Chromium. `getComputedStyle` already resolves each `var()` token here
 * (lesson 7 of the frontend brief is for jsdom, not this suite).
 */
interface HoverStyle {
  readonly backgroundColor: string;
  readonly color: string;
  readonly boxShadow: string;
  readonly transform: string;
  readonly textDecorationThickness: string;
  readonly transitionDuration: string;
}

async function hoverStyleOf(locator: Locator): Promise<HoverStyle> {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      backgroundColor: style.backgroundColor,
      color: style.color,
      boxShadow: style.boxShadow,
      transform: style.transform,
      textDecorationThickness: style.textDecorationThickness,
      transitionDuration: style.transitionDuration,
    };
  });
}

/**
 * The hover style of one element, read after its transition ends
 * (issue #464). `.btn` (styles.css:401-403) transitions `transform`
 * and `box-shadow` over 80 ms; a plain read right after `locator.hover()`
 * can land inside that window and give an in-between value, not the
 * settled hover value. `transitionDuration` names the length of that
 * window; this polls `hoverStyleOf` until each named field differs from
 * its rest value, with a 2 s cap, and returns the last read style.
 */
async function hoverStyleAfterTransition(
  locator: Locator,
  rest: HoverStyle,
  fields: readonly (keyof HoverStyle)[],
): Promise<HoverStyle> {
  await locator.evaluate((element) => getComputedStyle(element).transitionDuration);
  let latest: HoverStyle = rest;
  await expect
    .poll(
      async () => {
        latest = await hoverStyleOf(locator);
        return fields.every((field) => latest[field] !== rest[field]);
      },
      { timeout: 2_000 },
    )
    .toBe(true);
  return latest;
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

    // Issue #369: the hover rule excludes a disabled `.btn` (pull
    // request #410). A hover over the disabled confirm button gives no
    // shadow change and no shape change.
    const restStyle = await hoverStyleOf(confirmDeleteButton);
    await confirmDeleteButton.hover();
    const hoverStyle = await hoverStyleOf(confirmDeleteButton);
    expect(hoverStyle.transform).toBe(restStyle.transform);
    expect(hoverStyle.boxShadow).toBe(restStyle.boxShadow);

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

  /**
   * Issue #371, finding D5: the Status column of the apps table now sits
   * right after the App column, so a user reaches it at 390px with no
   * full scroll; `.table-scroll` also gets a CSS-only scroll cue, a
   * fading edge that shows only on the side that still holds a clipped
   * column. `apps.html` moved the Status column from index 6 to index 2;
   * `apps-page.ts` reads it at cell index 0, not index 4, to match.
   *
   * A pixel probe of the rendered cue (a screenshot decoded through
   * `<img>.decode()`) gave an `EncodingError` in the CI runner's
   * Chromium. This scenario checks the CSS mechanism and the layout
   * instead: the clip (`scrollWidth > clientWidth`), the Status header
   * inside the unscrolled view (the goal of the sticky first column),
   * and the four-layer `background-image` with the local/scroll
   * attachment split that `styles.css` gives `.table-scroll` (the cue
   * itself: two `--paper` masks tied to the table's own content, two
   * tinted shadows tied to the visible viewport).
   */
  test('the apps table carries the CSS scroll-cue layers, and the Status column stays reachable at 390px (issue #371, D5)', async ({
    page,
  }) => {
    const apps = new AppsPage(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await apps.goto();

    const tableScroll = page.locator('.table-scroll').first();
    const statusHeader = page.getByRole('columnheader', { name: 'Status' });

    // The table still holds more columns than the screen shows
    // (apps.css sets `table { min-width: 50rem }`), so it stays clipped.
    const widths = await tableScroll.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }));
    expect(widths.scrollWidth).toBeGreaterThan(widths.clientWidth);
    console.log(
      `issue #371, D5: .table-scroll scrollWidth is ${widths.scrollWidth}px, clientWidth is ${widths.clientWidth}px, at 390px.`,
    );

    // The Status header's left edge sits inside the visible,
    // unscrolled width of the container, so a user reaches it with no
    // scroll at all.
    const statusBox = await statusHeader.boundingBox();
    const scrollBox = await tableScroll.boundingBox();
    if (!statusBox || !scrollBox) {
      throw new Error('The Status header or the scroll container has no box.');
    }
    const statusOffsetInView = statusBox.x - scrollBox.x;
    console.log(
      `issue #371, D5: the Status header sits ${statusOffsetInView.toFixed(1)}px inside a ${scrollBox.width.toFixed(1)}px-wide view, with no scroll.`,
    );
    expect(statusOffsetInView).toBeLessThan(scrollBox.width);

    // The cue mechanism itself: two opaque masks (`local`, tied to the
    // table's own content) over two tinted shadows (`scroll`, tied to
    // the viewport), four `linear-gradient` layers in all. No script
    // runs this cue; the browser shows or hides each shadow by itself,
    // from the scroll position, so this check reads the wired-up CSS,
    // not a rendered pixel.
    const cueStyle = await tableScroll.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        backgroundImage: style.backgroundImage,
        backgroundAttachment: style.backgroundAttachment,
      };
    });
    console.log(
      `issue #371, D5: .table-scroll background-attachment is "${cueStyle.backgroundAttachment}".`,
    );
    const layerCount = (cueStyle.backgroundImage.match(/linear-gradient\(/g) ?? []).length;
    expect(layerCount).toBe(4);
    expect(cueStyle.backgroundAttachment.replace(/\s+/g, '')).toBe('local,local,scroll,scroll');

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
  const appKeyCell = manage.appKeyCell(row);
  const shortAppKeyText = await appKeyCell.locator('.code').textContent();
  console.log(`issue #386, D27: the app-key cell shows "${shortAppKeyText}" in a monospace font.`);
  expect(shortAppKeyText?.trim()).toBe(firstAppKey.slice(0, 8));
  await expect(manage.originCells(row)).toHaveText(['https://a.example', 'https://b.example']);

  // Issue #386, finding D27: the app-key cell renders in a monospace
  // font of at least 14px, and its Copy button writes the full key to
  // the clipboard and announces "Copied" through the one status region.
  const appKeyCode = appKeyCell.locator('.code');
  const codeFont = await appKeyCode.evaluate((element) => {
    const style = getComputedStyle(element);
    return { family: style.fontFamily, size: parseFloat(style.fontSize) };
  });
  console.log(
    `issue #386, D27: the app-key cell font is "${codeFont.family}" at ${codeFont.size}px.`,
  );
  expect(codeFont.family.toLowerCase()).toContain('monospace');
  expect(codeFont.size).toBeGreaterThanOrEqual(14);

  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  const copyButton = manage.appKeyCopyButton(row);
  // The Copy control reads the shared `.btn` font (D45), not `.code`: a
  // control label is UI chrome, never a data value (docs/components.md,
  // "The code-like value"). This confirms the two stay apart.
  const copyButtonFontFamily = await copyButton.evaluate(
    (element) => getComputedStyle(element).fontFamily,
  );
  console.log(`issue #386, D27: the Copy button font is "${copyButtonFontFamily}".`);
  expect(copyButtonFontFamily.toLowerCase()).not.toContain('monospace');
  await expect(copyButton).toHaveAttribute('title', firstAppKey);
  await copyButton.click();
  await expect(copyButton).toHaveText('Copied');
  const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
  console.log(
    `issue #386, D27: the clipboard holds a key of length ${clipboardText.length}, the full key is of length ${firstAppKey.length}.`,
  );
  expect(clipboardText).toBe(firstAppKey);
  await expect(page.locator('[role="status"]')).toHaveText('Copied');

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

/**
 * Issue #386, finding D27: one glyph marks every empty value of the
 * dashboard. The "demo" app registers through the legacy
 * connection-string path (D32), so it holds no app key: its cell shows
 * the shared en dash, `–`, the same glyph `status-format.ts` uses for
 * an unpolled count, with a hidden "None" text for a screen reader.
 * This scenario also checks the monospace class on the elements
 * selector cell and the sessions session-id link.
 */
test('the app-key cell of an app with no key shows one empty-value glyph, and the selector and session-id cells render in monospace (issue #386, D27)', async ({
  page,
  request,
}) => {
  const manage = new ManagePage(page);
  await manage.goto();
  const demoRow = manage.row('demo');
  const demoAppKeyCell = manage.appKeyCell(demoRow);
  const hiddenText = demoAppKeyCell.locator('.visually-hidden');
  const glyph = demoAppKeyCell.locator('[aria-hidden="true"]');
  await expect(hiddenText).toHaveText('None');
  await expect(glyph).toHaveText('–');
  await expect(demoAppKeyCell.getByRole('button')).toHaveCount(0);

  // A fresh app of its own, with one tracked user click (gives the
  // elements row) and one anonymous session of the same marker (gives
  // the sessions row), the same helpers the pager scenarios use.
  const monitorUrl = readMonitorUrl();
  const { appId, appKey, origin } = await registerPagerApp(request, monitorUrl, 'code-class-app');
  const marker = 386;
  await sendUserAndPage(request, monitorUrl, origin, appKey, marker);
  await sendAnonymousSessionOfSamePath(request, monitorUrl, origin, appKey, marker);

  const elements = new ElementsPage(page);
  await elements.gotoByUser(appId, `pager-user-${marker}`);
  const elementCell = page.locator('tbody th.code').first();
  await expect(elementCell).toBeVisible({ timeout: CHAIN_WAIT_MS });
  const elementFont = await elementCell.evaluate((element) => getComputedStyle(element).fontFamily);
  console.log(`issue #386, D27: the elements selector cell font is "${elementFont}".`);
  expect(elementFont.toLowerCase()).toContain('monospace');

  const sessions = new SessionsPage(page);
  await sessions.goto(appId);
  const sessionLink = page.locator('tbody th a.code').first();
  await expect(sessionLink).toBeVisible({ timeout: CHAIN_WAIT_MS });
  const sessionFont = await sessionLink.evaluate((element) => getComputedStyle(element).fontFamily);
  console.log(`issue #386, D27: the session-id link font is "${sessionFont}".`);
  expect(sessionFont.toLowerCase()).toContain('monospace');
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

  // Issue #364, the correction of pull request #465. A dialog open can
  // scroll the whole page by a few pixels on the CI runner. The cause
  // is unknown: a 12 px document scroll at the dialog open on the
  // Linux runner. Each row check below reads a row's own place
  // against the table, not its raw `y`. A check then stays true of a
  // static row through a window scroll the test does not control.
  const table = page.locator('main table').first();
  const tableBoxBefore = await table.boundingBox();
  const firstRowBoxBefore = await firstRow.boundingBox();
  const secondRowBoxBefore = await secondRow.boundingBox();
  if (!tableBoxBefore || !firstRowBoxBefore || !secondRowBoxBefore) {
    throw new Error('A row of the manage table has no box.');
  }

  // Opens the dialog of the first row, and proves it is a real modal
  // dialog (`showModal()`, not a plain `open` attribute toggle): the CSS
  // pseudo-class `:modal` matches only the top layer, modal case.
  await manage.openEditForm(firstRow);
  await expect(page.locator('dialog:modal')).toHaveCount(1);
  await expect(manage.editDialog(firstRow)).toHaveCount(1);

  const tableBoxDuring = await table.boundingBox();
  const firstRowBoxDuring = await firstRow.boundingBox();
  const secondRowBoxDuring = await secondRow.boundingBox();
  if (!tableBoxDuring || !firstRowBoxDuring || !secondRowBoxDuring) {
    throw new Error('A row of the manage table has no box, with the dialog open.');
  }
  console.log(
    `issue #364: the row height is ${firstRowBoxBefore.height}px before the dialog opens ` +
      `and ${firstRowBoxDuring.height}px while it is open; the row below sits at ` +
      `${secondRowBoxBefore.y - tableBoxBefore.y}px before and ` +
      `${secondRowBoxDuring.y - tableBoxDuring.y}px while the dialog is open, each against the table top.`,
  );
  expect(firstRowBoxDuring.height).toBe(firstRowBoxBefore.height);
  expect(secondRowBoxDuring.y - tableBoxDuring.y).toBe(secondRowBoxBefore.y - tableBoxBefore.y);

  // The rename keeps the exact same length as firstName: a longer name
  // can wrap onto a second line in the narrow App column, which would
  // grow the row for a reason that has nothing to do with the dialog.
  const renamedTo = firstName.replace('edit-dialog-a-', 'edit-dialog-r-');
  await manage.renameAndSave(firstRow, renamedTo);
  const renamedRow = manage.row(renamedTo);
  await expect(renamedRow).toBeVisible();

  const tableBoxAfter = await table.boundingBox();
  const secondRowBoxAfter = await secondRow.boundingBox();
  if (!tableBoxAfter || !secondRowBoxAfter) {
    throw new Error('The row below has no box after the save.');
  }
  expect(secondRowBoxAfter.y - tableBoxAfter.y).toBe(secondRowBoxBefore.y - tableBoxBefore.y);
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

/*
 * The audit wave of 2026-10-09, group D: #385 hoists the `.field`, the
 * `.form-hint`, and the base `dialog` rule of the manage forms and the
 * sign-in form into `styles.css`; #383 darkens the `.form-error` left
 * border and adds the "⚠" glyph; #438 corrects the invalid-field ring
 * pair of `docs/design/contrast.mjs`. The three scenarios below give
 * each issue its own end-to-end assertion.
 */

// Issue #385, finding D26: the `.field` rule used to drift between the
// five component style files (0.75rem, 0.5rem, 1rem). This scenario
// reads the computed `margin-bottom` of the field wrapper on the
// add-app form, the delete dialog, and the sign-in form (a fresh
// context: the default project session would redirect /signin to
// /apps), and confirms the one shared rule gives the three the same
// value.
test('the shared .field rule gives the add-app form, the delete dialog, and the sign-in form the same margin (issue #385)', async ({
  page,
  browser,
}) => {
  // This scenario drives three forms and opens a second browser
  // context; the default 60s test timeout left too little room for the
  // sign-in page under a loaded run.
  test.setTimeout(120_000);

  const manage = new ManagePage(page);
  await manage.goto();
  const appName = `shared-field-385-${Date.now()}`;
  await manage.addApp({ name: appName, origins: ['https://shared-field-385.example'] });

  // `toHaveCSS` polls until the value settles, so a late-applying
  // stylesheet on a freshly loaded page (the sign-in form below) never
  // races a one-shot `evaluate` read.
  const addAppField = page.locator('div.field:has(#add-app-name)');
  await expect(addAppField).toHaveCSS('margin-bottom', '12px');

  const row = manage.row(appName);
  await manage.openDeleteDialog(row);
  const dialogField = row.locator('app-delete-app-dialog div.field:has(.confirm-name)');
  await expect(dialogField).toHaveCSS('margin-bottom', '12px', { timeout: 15_000 });
  await manage.cancelDeleteDialog(row);

  // `newTrackedContext()` inherits this file's default storage state,
  // the owner's signed-in cookie (correction: the fixture merges the
  // project's `use` options, and this file holds no describe-level
  // override, unlike `signin.spec.ts`). A signed-in visit to `/signin`
  // redirects to `/apps` at once (issue #347), so the field never
  // shows. `browser.newContext` with an explicit empty storage state
  // gives the true signed-out visitor this scenario needs.
  const signedOutContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const signedOutPage = await signedOutContext.newPage();
  await signedOutPage.goto('/signin');
  await expect(signedOutPage.getByRole('button', { name: 'Send me a link' })).toBeVisible();
  const signinField = signedOutPage.locator('div.field:has(#signin-email)');
  await expect(signinField).toHaveCSS('margin-bottom', '12px', { timeout: 15_000 });
  await signedOutContext.close();

  console.log(
    'issue #385: the .field margin-bottom reads 12px on the add-app form, the delete ' +
      'dialog, and the sign-in form.',
  );
});

// Issue #383, finding D24: the sign-in form shows its error on the page
// background (`--shallows`, no panel card), where the former `--coral`
// border measured 2.46:1. A bad token on the confirm branch of
// `signin.html` is the deterministic way to raise the form-error paragraph
// with no mail round trip. `opacity: 1` sits beside the ratio (lesson 24
// of the frontend brief): a faded element would pass a color-only check.
//
// BLOCKER 1 of the review of pull request #450: the `.form-error` border
// also sits on a dialog fill (`--paper-raised`), darker than the page in
// the dark scheme. This scenario reads the border on the sign-in page in
// both color schemes, and inside the delete dialog in the dark scheme
// too, so the `--coral-edge` token gets a reading on each background it
// paints.
test('the sign-in form-error message starts with the warning glyph and its left border passes 3:1 on its background, in both color schemes and in a dialog (issue #383)', async ({
  page,
  newTrackedContext,
}) => {
  for (const colorScheme of ['light', 'dark'] as const) {
    const context = await newTrackedContext();
    const signinPage = await context.newPage();
    await signinPage.emulateMedia({ colorScheme });
    await signinPage.goto('/signin?token=e2e-bad-token-383');
    await signinPage.getByRole('button', { name: 'Sign in' }).click();

    const errorText = signinPage.locator('p.form-error');
    await expect(errorText).toBeVisible();
    await expect(errorText).toHaveCSS('opacity', '1');
    const messageText = (await errorText.textContent()) ?? '';
    expect(messageText.startsWith('⚠')).toBe(true);

    const [borderColor, pageBackground] = await errorText.evaluate((element) => [
      getComputedStyle(element).borderLeftColor,
      getComputedStyle(document.body).backgroundColor,
    ]);
    const borderContrast = contrastRatio(borderColor, pageBackground);
    console.log(
      `issue #383 (${colorScheme}): the sign-in form-error left border is "${borderColor}" ` +
        `on "${pageBackground}", ${borderContrast.toFixed(2)}:1.`,
    );
    expect(borderContrast).toBeGreaterThanOrEqual(3);
  }

  // The delete dialog paints its fill with `--paper-raised`. A route
  // abort on the DELETE request is the deterministic way to raise the
  // dialog's own form-error paragraph, with no server change.
  await page.emulateMedia({ colorScheme: 'dark' });
  const manage = new ManagePage(page);
  await manage.goto();
  const appName = `form-error-edge-450-${Date.now()}`;
  await manage.addApp({ name: appName, origins: ['https://form-error-edge-450.example'] });
  const row = manage.row(appName);

  await page.route('**/api/apps/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await route.fallback();
      return;
    }
    await route.abort();
  });
  await manage.openDeleteDialog(row);
  await manage.deleteConfirmNameField(row).fill(appName);
  await manage.deleteConfirmButton(row).click();

  const dialogError = row.locator('app-delete-app-dialog p.form-error');
  await expect(dialogError).toBeVisible();
  await expect(dialogError).toHaveCSS('opacity', '1');
  const [dialogBorderColor, dialogFill] = await dialogError.evaluate((element) => {
    const dialogEl = element.closest('dialog');
    return [
      getComputedStyle(element).borderLeftColor,
      dialogEl ? getComputedStyle(dialogEl).backgroundColor : '',
    ];
  });
  const dialogBorderContrast = contrastRatio(dialogBorderColor, dialogFill);
  console.log(
    `issue #383 (dark, dialog): the delete-dialog form-error left border is ` +
      `"${dialogBorderColor}" on "${dialogFill}", ${dialogBorderContrast.toFixed(2)}:1.`,
  );
  expect(dialogBorderContrast).toBeGreaterThanOrEqual(3);

  await page.unroute('**/api/apps/*');
});

// Issue #438: `docs/design/contrast.mjs` carried the invalid-field ring
// pair as the pre-#264 literal `#ff6b6b` (2.8:1), though the CSS rule
// itself already painted `var(--coral-deep)`. This scenario reads the
// real ring color of a field that a server-side validation error marks
// invalid (two equal origins, rejected by the registry), and confirms
// the painted ring reaches 3:1 on the field fill. `opacity: 1` sits
// beside the ratio for the same reason as the #383 scenario above.
test('the invalid-field ring of the add-app form reaches a 3:1 contrast on the field fill (issue #438)', async ({
  page,
}) => {
  const manage = new ManagePage(page);
  await manage.goto();
  const origin = 'https://ring-438.example';
  await manage.addAppExpectingError(
    { name: `ring-438-${Date.now()}`, origins: [origin, origin] },
    'An origin appears twice.',
  );

  const originsField = page.getByLabel('Origins (one per line)');
  await expect(originsField).toHaveAttribute('aria-invalid', 'true');
  await expect(originsField).toHaveCSS('opacity', '1');

  const [ringColor, fillColor] = await originsField.evaluate((element) => {
    const style = getComputedStyle(element);
    const match = style.boxShadow.match(/rgba?\([^)]+\)/);
    return [match ? match[0] : '', style.backgroundColor];
  });
  const ringContrast = contrastRatio(ringColor, fillColor);
  console.log(
    `issue #438: the invalid-field ring is "${ringColor}" on "${fillColor}", ` +
      `${ringContrast.toFixed(2)}:1.`,
  );
  expect(ringContrast).toBeGreaterThanOrEqual(3);
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

  // Issue #451, corrected in pull request #462 (MAJOR 1 of the review):
  // the dark focus ring reads 3:1 or more on each of its two layers
  // against the fill it sits next to. The "demo" row link paints the
  // row's own `--shallows-deep` fill while the row holds focus, not the
  // plain page background, so the assertion reads the adjacent cell's
  // own painted background, not a literal page colour. `opacity: 1`
  // sits beside each ratio (lesson 24 of the frontend brief).
  const demoLink = apps.rowOf('demo').nameLink;
  await demoLink.focus();
  await expect(demoLink).toHaveCSS('opacity', '1');
  const ringColors = await demoLink.evaluate((element) => {
    const style = getComputedStyle(element);
    const haloMatch = style.boxShadow.match(/rgba?\([^)]+\)/);
    const cell = element.closest('th, td');
    const fill = cell ? getComputedStyle(cell).backgroundColor : '';
    return { line: style.outlineColor, halo: haloMatch ? haloMatch[0] : '', fill };
  });
  expect(ratio(toHex(ringColors.line), toHex(ringColors.halo))).toBeGreaterThanOrEqual(3);
  expect(ratio(toHex(ringColors.halo), toHex(ringColors.fill))).toBeGreaterThanOrEqual(3);

  // BLOCKER 1 of the review of pull request #462: `.band` now gives
  // `--focus-halo` its own value, `--on-bright`, so a focused band
  // control reads 3:1 or more on the band fill too, not the page's own
  // dark value (it read 1.44:1 before this fix).
  await manageAppsPill.focus();
  await expect(manageAppsPill).toHaveCSS('opacity', '1');
  const bandRingColors = await manageAppsPill.evaluate((element) => {
    const style = getComputedStyle(element);
    const haloMatch = style.boxShadow.match(/rgba?\([^)]+\)/);
    return { line: style.outlineColor, halo: haloMatch ? haloMatch[0] : '' };
  });
  expect(ratio(toHex(bandRingColors.line), toHex(bandRingColors.halo))).toBeGreaterThanOrEqual(3);
  expect(ratio(toHex(bandRingColors.halo), toHex(bandFill))).toBeGreaterThanOrEqual(3);

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
// the graphic stays visible (WCAG 1.4.11). BLOCKER 1 of the review of
// pull request #459: the eye fill also keeps a 3:1 contrast against the
// head fill, in the light scheme and in the dark scheme. `opacity: 1`
// rules out a faded ancestor masking the real painted color (lesson 24
// of the frontend brief).
test('the mascot head fill reads 3:1 or more against the header band (issue #439)', async ({
  page,
}) => {
  const apps = new AppsPage(page);
  await apps.goto();
  const head = page.locator('header.band app-octo-mark .head');
  const eye = page.locator('header.band app-octo-mark .eye').first();
  const band = page.locator('header.band');

  await expect(head).toHaveCSS('opacity', '1');
  await expect(eye).toHaveCSS('opacity', '1');

  async function measurePairs(): Promise<{
    headFill: string;
    bandFill: string;
    eyeFill: string;
    headOnBand: number;
    eyeOnHead: number;
  }> {
    const [headFill, bandFill, eyeFill] = await Promise.all([
      head.evaluate((element) => getComputedStyle(element).fill),
      band.evaluate((element) => getComputedStyle(element).backgroundColor),
      eye.evaluate((element) => getComputedStyle(element).fill),
    ]);
    return {
      headFill,
      bandFill,
      eyeFill,
      headOnBand: contrastRatio(headFill, bandFill),
      eyeOnHead: contrastRatio(eyeFill, headFill),
    };
  }

  const light = await measurePairs();
  console.log(
    `issue #439: light scheme, the head fill ${light.headFill} on the band ${light.bandFill} reads ${light.headOnBand.toFixed(2)}:1; the eye fill ${light.eyeFill} on the head reads ${light.eyeOnHead.toFixed(2)}:1.`,
  );
  expect(light.headOnBand).toBeGreaterThanOrEqual(3);
  expect(light.eyeOnHead).toBeGreaterThanOrEqual(3);

  await page.emulateMedia({ colorScheme: 'dark' });
  const dark = await measurePairs();
  console.log(
    `issue #439: dark scheme, the head fill ${dark.headFill} on the band ${dark.bandFill} reads ${dark.headOnBand.toFixed(2)}:1; the eye fill ${dark.eyeFill} on the head reads ${dark.eyeOnHead.toFixed(2)}:1.`,
  );
  expect(dark.headOnBand).toBeGreaterThanOrEqual(3);
  expect(dark.eyeOnHead).toBeGreaterThanOrEqual(3);
});

/**
 * Issue #369 (finding D13 of the UI audit of 2026-10-09): a button, a
 * nav pill, the Pause refresh pill, and a text link had no `:hover`
 * rule. Each check reads `opacity` as `1` first (lesson 24 of the
 * frontend brief). It then reads the text contrast, so the ratio
 * reads the true painted colour.
 */
test('a button, a nav pill, the pause pill, and a link each change under hover (issue #369)', async ({
  page,
  browser,
}) => {
  // The button: the "Add app" submit button of the manage view, a
  // `.btn.btn-primary`.
  const manage = new ManagePage(page);
  await manage.goto();
  const addAppButton = page.getByRole('button', { name: 'Add app' });
  await expect(addAppButton).toHaveCSS('opacity', '1');
  const buttonRest = await hoverStyleOf(addAppButton);
  await addAppButton.hover();
  const buttonHover = await hoverStyleAfterTransition(addAppButton, buttonRest, [
    'boxShadow',
    'transform',
  ]);
  expect(buttonHover.transform).not.toBe(buttonRest.transform);
  expect(buttonHover.boxShadow).not.toBe(buttonRest.boxShadow);
  // The fill and the text colour stay the same. The text contrast of
  // the button stays at its own ratio.
  expect(buttonHover.backgroundColor).toBe(buttonRest.backgroundColor);
  expect(buttonHover.color).toBe(buttonRest.color);
  expect(contrastRatio(buttonHover.color, buttonHover.backgroundColor)).toBeGreaterThanOrEqual(4.5);

  // MAJOR 1 of the review of pull request #445: a mouse press beats
  // the hover. `page.mouse.down()` over the button holds `:hover` and
  // `:active` at the same time, so this reads the real press, not the
  // hover alone.
  const addAppBox = await addAppButton.boundingBox();
  if (!addAppBox) {
    throw new Error('The "Add app" button has no box.');
  }
  await page.mouse.move(addAppBox.x + addAppBox.width / 2, addAppBox.y + addAppBox.height / 2);
  await page.mouse.down();
  // The 80ms transform transition of `.btn` (styles.css) needs a short
  // wait, so the read happens after the press settles, not mid-move.
  await page.waitForTimeout(150);
  const buttonPress = await hoverStyleOf(addAppButton);
  await page.mouse.up();
  expect(buttonPress.transform).toBe('matrix(1, 0, 0, 1, 3, 3)');
  expect(buttonPress.boxShadow).toBe('none');

  // MAJOR 2 of the review of pull request #445: a touch tap leaves no
  // sticky hover. A context with `hasTouch` and `isMobile`, at 390 px,
  // signed in through the same storage state as the default context.
  // `baseURL` repeats here too (the config comment above states the
  // same rule for `userAgent`): a context a test opens itself does not
  // inherit the project `use` block.
  const monitorUrl = process.env.E2E_MONITOR_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  const touchContext = await browser.newContext({
    baseURL: monitorUrl,
    hasTouch: true,
    isMobile: true,
    viewport: { width: 390, height: 844 },
    userAgent: DESKTOP_CHROME_USER_AGENT,
    storageState: STORAGE_STATE_FILE,
  });
  const touchPage = await touchContext.newPage();
  const touchManage = new ManagePage(touchPage);
  await touchManage.goto();
  const touchButton = touchPage.getByRole('button', { name: 'Add app' });
  const touchRest = await hoverStyleOf(touchButton);
  await touchButton.tap();
  await touchPage.waitForTimeout(500);
  const touchAfterTap = await hoverStyleOf(touchButton);
  expect(touchAfterTap.transform).toBe(touchRest.transform);
  expect(touchAfterTap.boxShadow).toBe(touchRest.boxShadow);
  await touchContext.close();

  // The nav pill: "Apps" is not the current link on the manage view.
  const nav = page.getByRole('navigation', { name: 'Main' });
  const appsPill = nav.getByRole('link', { name: 'Apps', exact: true });
  await expect(appsPill).toHaveCSS('opacity', '1');
  const pillRest = await hoverStyleOf(appsPill);
  await appsPill.hover();
  const pillHover = await hoverStyleAfterTransition(appsPill, pillRest, ['backgroundColor']);
  expect(pillHover.backgroundColor).not.toBe(pillRest.backgroundColor);
  expect(contrastRatio(pillHover.color, pillHover.backgroundColor)).toBeGreaterThanOrEqual(4.5);

  // The Pause refresh pill, on the apps view (the refresh bar).
  const apps = new AppsPage(page);
  await apps.goto();
  const pauseButton = page.getByRole('button', { name: 'Pause refresh' });
  await expect(pauseButton).toHaveCSS('opacity', '1');
  const pauseRest = await hoverStyleOf(pauseButton);
  await pauseButton.hover();
  await expect(pauseButton).toHaveCSS('opacity', '1');
  const pauseHover = await hoverStyleAfterTransition(pauseButton, pauseRest, ['backgroundColor']);
  const lightPageBackground = await pauseButton.evaluate(
    () => getComputedStyle(document.body).backgroundColor,
  );
  expect(pauseHover.backgroundColor).not.toBe(pauseRest.backgroundColor);
  expect(contrastRatio(pauseHover.color, pauseHover.backgroundColor)).toBeGreaterThanOrEqual(4.5);
  // Issue #468: the light Pause refresh pill keeps a visible hover cue
  // too. The fill must differ from the light page by 1.5:1 or more.
  const pauseFillContrast = contrastRatio(pauseHover.backgroundColor, lightPageBackground);
  console.log(
    `issue #468 (light): the hovered pause pill fill is "${pauseHover.backgroundColor}" ` +
      `on the page "${lightPageBackground}", ${pauseFillContrast.toFixed(2)}:1.`,
  );
  expect(pauseFillContrast).toBeGreaterThanOrEqual(1.5);

  // The text link: "Go to the apps" of the not-found page, under the
  // global `a` rule, with no pill and no band around it. The link
  // sets no background of its own; it stays transparent. The
  // contrast check below reads the `.panel` fill behind it instead.
  await page.goto('/no-such-page');
  const textLink = page.getByRole('link', { name: 'Go to the apps' });
  const panelBackground = await page
    .locator('.panel')
    .first()
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  await expect(textLink).toHaveCSS('opacity', '1');
  const linkRest = await hoverStyleOf(textLink);
  await textLink.hover();
  const linkHover = await hoverStyleAfterTransition(textLink, linkRest, [
    'textDecorationThickness',
  ]);
  expect(linkHover.textDecorationThickness).not.toBe(linkRest.textDecorationThickness);
  expect(contrastRatio(linkHover.color, panelBackground)).toBeGreaterThanOrEqual(4.5);

  // `prefers-reduced-motion: reduce` (step 5 of the issue): the
  // transition of `.btn` yields to it. The hover shadow of the button
  // above then applies with no animation.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await manage.goto();
  const reducedAddAppButton = page.getByRole('button', { name: 'Add app' });
  await reducedAddAppButton.hover();
  const reducedStyle = await hoverStyleOf(reducedAddAppButton);
  expect(reducedStyle.transitionDuration).toBe('0s');

  // Note N1 of the review of pull request #445: the hovered nav pill
  // stays readable in the dark color scheme too. "Manage apps" is not
  // the current link on the apps view, so its hover rule applies.
  await page.emulateMedia({ reducedMotion: 'no-preference', colorScheme: 'dark' });
  await apps.goto();
  const darkPill = page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Manage apps' });
  const darkPillRest = await hoverStyleOf(darkPill);
  await darkPill.hover();
  await expect(darkPill).toHaveCSS('opacity', '1');
  const darkPillHover = await hoverStyleOf(darkPill);
  expect(darkPillHover.backgroundColor).not.toBe(darkPillRest.backgroundColor);
  expect(
    contrastRatio(darkPillHover.color, darkPillHover.backgroundColor),
  ).toBeGreaterThanOrEqual(4.5);

  // Issue #454: the dark Pause refresh pill keeps a visible hover cue
  // too. The pill sits outside `.band`, so its hover fill is
  // `--shallows-hover`, not `--shallows-bright`. The fill must differ
  // from the dark page by 1.5:1 or more, while the text stays at 4.5:1
  // or more.
  await apps.goto();
  const darkPauseButton = page.getByRole('button', { name: 'Pause refresh' });
  await expect(darkPauseButton).toHaveCSS('opacity', '1');
  const darkPauseRest = await hoverStyleOf(darkPauseButton);
  await darkPauseButton.hover();
  const darkPauseHover = await hoverStyleAfterTransition(darkPauseButton, darkPauseRest, [
    'backgroundColor',
  ]);
  const darkPageBackground = await darkPauseButton.evaluate(
    () => getComputedStyle(document.body).backgroundColor,
  );
  expect(darkPauseHover.backgroundColor).not.toBe(darkPauseRest.backgroundColor);
  expect(
    contrastRatio(darkPauseHover.color, darkPauseHover.backgroundColor),
  ).toBeGreaterThanOrEqual(4.5);
  const darkPauseFillContrast = contrastRatio(
    darkPauseHover.backgroundColor,
    darkPageBackground,
  );
  console.log(
    `issue #454 (dark): the hovered pause pill fill is "${darkPauseHover.backgroundColor}" ` +
      `on the page "${darkPageBackground}", ${darkPauseFillContrast.toFixed(2)}:1.`,
  );
  expect(darkPauseFillContrast).toBeGreaterThanOrEqual(1.5);
});

/**
 * Issue #446: the review of pull request #445 found that the
 * pre-existing `tr:hover` rules of the apps view, the users view, the
 * sessions view, and the first-pages view carried no
 * `@media (hover: hover)` guard, unlike the control hover rules that
 * issue #369 added in that same pull request. This scenario checks the
 * apps view (the other three views share the identical rule, the same
 * fix, from the same stylesheet pattern). A mouse keeps the hover fill
 * at 1280px; a tap leaves none after 500ms at 390px.
 */
test('a tap on an apps-table row leaves no sticky hover fill in a touch context, and a mouse still keeps it (issue #446)', async ({
  page,
  browser,
}) => {
  const monitorUrl = process.env.E2E_MONITOR_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }

  // The first row (`:nth-child(1)`, odd), never a row picked by name: by
  // this point in the file, earlier scenarios registered several more
  // apps, and an even row would also match the zebra rule of
  // styles.css, `tr:nth-child(even) > *`, at a higher specificity than
  // the hover rule. That zebra-versus-hover case belongs to a
  // different issue; this scenario isolates the `(hover: hover)` guard
  // on a row the zebra rule never touches.
  await page.setViewportSize({ width: 1280, height: 720 });
  const apps = new AppsPage(page);
  await apps.goto();
  const mouseCell = page.locator('tbody tr').first().locator('td').first();
  const mouseRest = await mouseCell.evaluate((element) => getComputedStyle(element).backgroundColor);
  await mouseCell.hover();
  const mouseHover = await mouseCell.evaluate((element) => getComputedStyle(element).backgroundColor);
  console.log(
    `issue #446: at 1280px, the row fill is ${mouseRest} at rest and ${mouseHover} under a mouse hover.`,
  );
  expect(mouseHover).not.toBe(mouseRest);

  const touchContext = await browser.newContext({
    baseURL: monitorUrl,
    hasTouch: true,
    isMobile: true,
    viewport: { width: 390, height: 844 },
    userAgent: DESKTOP_CHROME_USER_AGENT,
    storageState: STORAGE_STATE_FILE,
  });
  const touchPage = await touchContext.newPage();
  // A second context's page starts as a background document in
  // Chromium (`document.hidden` is true): `poll-store.ts` gates every
  // request, the first one too, on `!document.hidden` (the lesson of
  // issue #164), so the apps table would never load a row at all.
  // `bringToFront()` makes this page the visible one.
  await touchPage.bringToFront();
  const touchApps = new AppsPage(touchPage);
  await touchApps.goto();
  // Every cell of a data row opens the name link on a click (D28), and
  // a real tap fires that click, the same way a mouse click does: the
  // row then navigates away before the 500ms wait, and the next
  // locator call waits forever for a row that is gone. A capture-phase
  // listener on the document blocks the click's own default action (and
  // its bubble to the row's own handler) before either one runs, so the
  // row stays in place; a tap still sets the real `:hover` state
  // through Chromium's own touch-to-pointer stack, the state this
  // scenario measures, because that state comes from the pointer input
  // itself, not from the click event.
  await touchPage.evaluate(() => {
    document.addEventListener(
      'click',
      (event) => {
        event.preventDefault();
        event.stopPropagation();
      },
      { capture: true },
    );
  });
  const touchCell = touchApps.rowOf('demo').row.locator('td').first();
  const touchRest = await touchCell.evaluate((element) => getComputedStyle(element).backgroundColor);
  await touchCell.tap();
  await touchPage.waitForTimeout(500);
  const touchAfterTap = await touchCell.evaluate((element) => getComputedStyle(element).backgroundColor);
  console.log(
    `issue #446: at 390px in a touch context, the row fill is ${touchRest} at rest and ${touchAfterTap} 500ms after a tap.`,
  );
  expect(touchAfterTap).toBe(touchRest);
  await touchContext.close();
});

/**
 * The painted background colour behind one element: the element's own
 * background when it holds one, else the nearest ancestor's (issue
 * #373, issue #395). `.list-error` and `.error-text` set no background
 * of their own, so each contrast check below reads the panel or the
 * page background instead.
 */
async function paintedColors(locator: Locator): Promise<{ fg: string; bg: string }> {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element);
    let ancestor: Element | null = element.parentElement;
    let background = style.backgroundColor;
    while (ancestor && (background === 'rgba(0, 0, 0, 0)' || background === 'transparent')) {
      background = getComputedStyle(ancestor).backgroundColor;
      ancestor = ancestor.parentElement;
    }
    return { fg: style.color, bg: background };
  });
}

/**
 * Issue #373 (D6 of the UI audit of 2026-10-09): a list view that fails
 * its first load shows the `.list-error` text with a "Try again" button,
 * in place of a loading sentence that never ends. One fresh app, with no
 * event sent to it, gives the users view, the sessions view, and the
 * first-pages view an empty, not a table, once the retry answers; the
 * apps view keeps a table, because the app list itself holds this one
 * app. Each of the four cases routes its own GET to a 500 once, so the
 * assertion above the loop fails on the unpatched view: the loading
 * sentence of issue #373's "Current behaviour" never clears, and no
 * "Try again" button exists for `root().querySelector` to find.
 */
test('a failed first load shows the error state, and "Try again" reloads the list (issue #373, D6)', async ({
  page,
  request,
}) => {
  const monitorUrl = readMonitorUrl();
  const { appId } = await registerPagerApp(request, monitorUrl, 'list-error-app');

  const cases: ReadonlyArray<{
    name: string;
    url: string;
    apiGlob: string;
    errorText: string;
    afterSuccess: 'table' | 'empty';
    emptyText?: string;
  }> = [
    {
      name: 'apps',
      url: '/apps',
      apiGlob: '**/api/apps',
      errorText: 'Octometer cannot read the app list. Try again.',
      afterSuccess: 'table',
    },
    {
      name: 'users',
      url: `/apps/${appId}/users`,
      apiGlob: `**/api/apps/${appId}/users*`,
      errorText: 'Octometer cannot read the user list. Try again.',
      afterSuccess: 'empty',
      emptyText: 'No app user has clicked yet.',
    },
    {
      name: 'sessions',
      url: `/apps/${appId}/sessions?anonymous=true`,
      apiGlob: `**/api/apps/${appId}/sessions*`,
      errorText: 'Octometer cannot read the anonymous session list. Try again.',
      afterSuccess: 'empty',
      emptyText: 'No anonymous session yet.',
    },
    {
      name: 'first-pages',
      url: `/apps/${appId}/first-pages`,
      apiGlob: `**/api/apps/${appId}/first-pages*`,
      errorText: 'Octometer cannot read the first page list. Try again.',
      afterSuccess: 'empty',
      emptyText: 'No first page yet.',
    },
  ];

  for (const testCase of cases) {
    let hits = 0;
    await page.route(testCase.apiGlob, (route) => {
      hits += 1;
      return hits === 1 ? route.fulfill({ status: 500, body: '' }) : route.continue();
    });

    await page.goto(testCase.url);

    const errorParagraph = page.locator('p.list-error');
    await expect(errorParagraph).toHaveText(testCase.errorText, { timeout: CHAIN_WAIT_MS });
    await expect(page.locator('table')).toHaveCount(0);
    const retryButton = page.getByRole('button', { name: 'Try again' });
    await expect(retryButton).toBeVisible();
    await expect(retryButton).toHaveCSS('opacity', '1');

    await retryButton.click();
    await expect(errorParagraph).toHaveCount(0, { timeout: CHAIN_WAIT_MS });
    if (testCase.afterSuccess === 'table') {
      await expect(page.locator('table')).toBeVisible({ timeout: CHAIN_WAIT_MS });
    } else {
      await expect(page.getByText(testCase.emptyText!)).toBeVisible({ timeout: CHAIN_WAIT_MS });
    }

    await page.unroute(testCase.apiGlob);
    console.log(
      `issue #373, D6: the ${testCase.name} view showed "${testCase.errorText}" on a routed ` +
        `500, with no table, and recovered after "Try again".`,
    );
  }

  // Step 2 of the implementation: the error state keeps 4.5:1 in both
  // colour schemes. The apps view stands for the five views; they share
  // the one `.list-error` rule of styles.css. Issue #453 adds the
  // `opacity: 1` check beside the contrast check (lesson 24 of the
  // frontend brief, the review of issue #410): a contrast ratio read
  // from `getComputedStyle` colours alone ignores a faded element.
  // Issue #453 also reads the border-left colour: the plain `var(--coral)`
  // border read 2.46:1 on the light page background, under the 3:1 floor
  // of WCAG 1.4.11; `--coral-edge` is the fix.
  for (const colorScheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme });
    let hits = 0;
    await page.route('**/api/apps', (route) => {
      hits += 1;
      return hits === 1 ? route.fulfill({ status: 500, body: '' }) : route.continue();
    });
    await page.goto('/apps');
    const errorParagraph = page.locator('p.list-error');
    await expect(errorParagraph).toBeVisible({ timeout: CHAIN_WAIT_MS });
    await expect(errorParagraph).toHaveCSS('opacity', '1');
    const colors = await paintedColors(errorParagraph);
    const contrast = ratio(toHex(colors.fg), toHex(colors.bg));
    console.log(
      `issue #453: the list-error text contrast in ${colorScheme} mode is ${contrast.toFixed(2)}:1, ` +
        `with opacity 1.`,
    );
    expect(contrast).toBeGreaterThanOrEqual(4.5);

    const [borderColor, pageBackground] = await errorParagraph.evaluate((element) => [
      getComputedStyle(element).borderLeftColor,
      getComputedStyle(document.body).backgroundColor,
    ]);
    const borderContrast = contrastRatio(borderColor, pageBackground);
    console.log(
      `issue #453: the list-error left border is "${borderColor}" on "${pageBackground}", ` +
        `${borderContrast.toFixed(2)}:1 in ${colorScheme} mode.`,
    );
    expect(borderContrast).toBeGreaterThanOrEqual(3);

    await page.unroute('**/api/apps');
  }
  await page.emulateMedia({ colorScheme: 'light' });
});

/**
 * BLOCKER 1 of the review of pull request #452: a failed `GET
 * /api/health` must give the same error state as a failed list request,
 * not the loading sentence that never ends. The old `listError()` guard
 * read `firstLoadPending()`, a flag that never clears while the health
 * route fails, because `intervalMs$` then emits no interval and the
 * store sends no data request. The fix reads `data()` and `error()`
 * alone. The health route stays routed to 500 through the whole test, so
 * the assertion also proves that "Try again" still recovers the list
 * through its own `GET /api/apps`, with no interval from the health
 * route (issue #373, "Current behaviour", second mode).
 */
test('a failed health request shows the error state too, and "Try again" still recovers the list (issue #373, D6, BLOCKER 1 of the review of pull request #452)', async ({
  page,
}) => {
  await page.route('**/api/health', (route) => route.fulfill({ status: 500, body: '' }));

  const apps = new AppsPage(page);
  await apps.goto();

  const errorParagraph = page.locator('p.list-error');
  await expect(errorParagraph).toHaveText('Octometer cannot read the app list. Try again.', {
    timeout: CHAIN_WAIT_MS,
  });
  await expect(page.getByText('Loading apps…')).toHaveCount(0);
  await expect(page.locator('table')).toHaveCount(0);

  const retryButton = page.getByRole('button', { name: 'Try again' });
  await expect(retryButton).toBeVisible();
  await retryButton.click();

  // The health route still answers 500 here: the list recovers through
  // its own GET /api/apps alone, with no help from the health interval.
  await expect(errorParagraph).toHaveCount(0, { timeout: CHAIN_WAIT_MS });
  await expect(page.locator('table')).toBeVisible({ timeout: CHAIN_WAIT_MS });

  await page.unroute('**/api/health');
  console.log(
    'issue #373, D6, BLOCKER 1: the apps view showed the error state on a failed health ' +
      'request, with no table and no "Loading apps…" sentence, and "Try again" recovered ' +
      'the list while the health route still answered 500.',
  );
});

/**
 * Issue #395 (D20 of the UI audit of 2026-10-09): the raw error text of
 * a failed app's Status cell renders at 14px monospace, with a 2-line
 * clamp, and the full raw text stays in the element's `title` attribute.
 * A mock answer of `GET /api/apps` stands in for a real failed poll, the
 * same way the health-banner mock above stands in for a real API
 * outage: issue #395 names no app state that a real collector run must
 * reach, only the rendering of a `lastError` string the API already
 * gives. The placeholder connection string holds no real credential
 * (`<user>:<password>`, `exampledb`), the fixture form of the secret-scan
 * rule.
 */
test('a failed app shows a short status text with the raw error in the title (issue #395, D20)', async ({
  page,
}) => {
  const rawError =
    'mongodb+srv://<user>:<password>@cluster0.example.mongodb.net/exampledb: connection ' +
    'refused after 30000 ms, retrying with backoff; no replica reachable; check the network ' +
    'access list and the credentials.';
  const failedRow = {
    appId: 1,
    name: 'status-cell-app',
    clicks: 0,
    uniqueUsers: 0,
    uniqueSessions: 0,
    status: 'UNREACHABLE',
    lastSuccessAt: null,
    lastError: rawError,
    nextPollAt: null,
    gaps: [],
  };
  await page.route('**/api/apps', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([failedRow]),
    }),
  );

  const apps = new AppsPage(page);
  await apps.goto();

  const errorParagraph = page.locator('p.error-text');
  await expect(errorParagraph).toBeVisible();

  const style = await errorParagraph.evaluate((element) => {
    const computed = getComputedStyle(element);
    return {
      fontSize: computed.fontSize,
      fontFamily: computed.fontFamily,
      opacity: computed.opacity,
    };
  });
  console.log(`issue #395, D20: the error text font is ${style.fontSize} ${style.fontFamily}.`);
  expect(style.fontSize).toBe('14px');
  expect(style.fontFamily.toLowerCase()).toContain('monospace');
  expect(style.opacity).toBe('1');

  // `Range.getClientRects()` (the `lineCount` helper above) counts every
  // laid-out line, clamped or not, because `-webkit-line-clamp` clips the
  // paint, not the layout. The clamp itself shows instead through the
  // element's own box: `clientHeight` stops at 2 lines, while
  // `scrollHeight` still measures the full, unclamped text.
  const clampMetrics = await errorParagraph.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      lineClamp: style.webkitLineClamp,
      display: style.display,
      overflow: style.overflow,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
    };
  });
  console.log(
    `issue #395, D20: the ${rawError.length}-character error text clamps at ` +
      `clientHeight ${clampMetrics.clientHeight}px against a full scrollHeight of ` +
      `${clampMetrics.scrollHeight}px (line-clamp ${clampMetrics.lineClamp}, ` +
      `display ${clampMetrics.display}, overflow ${clampMetrics.overflow}).`,
  );
  // Chromium resolves the legacy `display: -webkit-box` keyword to its own
  // internal box type (`flow-root` on this build), so this assertion reads
  // the declared `-webkit-line-clamp` and `overflow` values, plus the real
  // box-height proof below, not the resolved `display` keyword.
  expect(clampMetrics.lineClamp).toBe('2');
  expect(clampMetrics.overflow).toBe('hidden');
  expect(clampMetrics.scrollHeight).toBeGreaterThan(clampMetrics.clientHeight);

  await expect(errorParagraph).toHaveAttribute('title', rawError);

  for (const colorScheme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme });
    await apps.goto();
    const colors = await paintedColors(page.locator('p.error-text'));
    const contrast = ratio(toHex(colors.fg), toHex(colors.bg));
    console.log(`issue #395, D20: the error text contrast in ${colorScheme} mode is ${contrast.toFixed(2)}:1.`);
    expect(contrast).toBeGreaterThanOrEqual(4.5);
  }
  await page.emulateMedia({ colorScheme: 'light' });

  await page.unroute('**/api/apps');
});
