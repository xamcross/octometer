import type { APIRequestContext, Locator, Page } from '@playwright/test';

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

/**
 * The WCAG 2.x contrast ratio of the text color against the background
 * color of one element (issue #384, finding D25: the disabled Delete
 * button measured about 2.07:1 at `opacity: 0.55`). The locator
 * evaluate reads the two resolved `rgb(...)` strings of the real
 * browser; the ratio math runs here in Node.
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
