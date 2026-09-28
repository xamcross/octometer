import { MongoClient } from 'mongodb';

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

/** The config document of contract rule C44 (design decision D47, issue #276). */
interface RoutePatternsDocument {
  readonly _id: string;
  readonly patterns: ReadonlyArray<string>;
  readonly kitVersion: string;
  readonly publishedAt: Date;
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
    if (!mongoPort) {
      throw new Error('E2E_MONGO_PORT is absent. The global setup did not run, or it failed.');
    }
    const manage = new ManagePage(page);
    await manage.goto();
    await manage.addApp({
      name: 'demo',
      connectionString: `mongodb://127.0.0.1:${mongoPort}/exampledb`,
    });
    // D8: the first poll cycle can run inside this same wait.
    await manage.expectStatusOneOf('demo', ['Not polled yet', 'OK'], CHAIN_WAIT_MS);

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
    // of the connection string. Two rejected submits, each read through
    // the error area, with no wait for a row.
    await manage.addAppExpectingError(
      { name: 'demo-no-db', connectionString: `mongodb://127.0.0.1:${mongoPort}` },
      'Give the database name in the path of the connection string.',
    );

    await manage.addAppExpectingError(
      { name: 'demo-bad-port', connectionString: 'mongodb://127.0.0.1:99999999/exampledb' },
      'The driver cannot read the connection string.',
    );
  });

  test('the level 1 view shows the status badge OK after the first poll', async ({ page }) => {
    const apps = new AppsPage(page);
    await apps.goto();
    await apps.expectStatus('demo', 'OK', CHAIN_WAIT_MS);
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
  });

  test('the level 3 elements view of user A lists two elements with one click each', async ({
    page,
  }) => {
    const elements = new ElementsPage(page);
    await elements.gotoByUser(appId, 'amy');
    await elements.expectRow('demo.button-one', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);
    await elements.expectRow('demo.button-two', { clicks: 1, sessions: 1 }, CHAIN_WAIT_MS);
    expect(await elements.rowCount()).toBe(2);
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
    },
  );

  // Issue #276, design decision D47: the kit publishes its derived route
  // pattern list into octometer_config, on the app's own database user.
  // This scenario reads the document with the mongodb Node driver
  // directly (a pinned dev dependency of e2e/), never through the
  // monitor, because the monitor's own read of this document is a later
  // story.
  test('the app publishes its route list (design decision D47)', async () => {
    const mongoPort = process.env.E2E_MONGO_PORT;
    if (!mongoPort) {
      throw new Error('E2E_MONGO_PORT is absent. The global setup did not run, or it failed.');
    }

    const client = new MongoClient(`mongodb://127.0.0.1:${mongoPort}`);
    try {
      await client.connect();
      const collection = client.db('exampledb').collection<RoutePatternsDocument>('octometer_config');
      const configDoc = await collection.findOne({ _id: 'route-patterns' });
      if (!configDoc) {
        throw new Error('The document "route-patterns" is absent from octometer_config.');
      }

      // Design decision D46: the demo app serves three GET routes, "/",
      // "/items/{id}", and "/tracker/{fileName}" (Application.kt,
      // demoModule). The kit's sort puts a literal segment before a
      // ":name" segment, then orders by text, so "/items/:id" comes
      // before "/tracker/:fileName".
      expect(configDoc.patterns).toEqual(['/', '/items/:id', '/tracker/:fileName']);

      expect(typeof configDoc.kitVersion).toBe('string');
      expect(configDoc.kitVersion.length).toBeGreaterThan(0);

      expect(configDoc.publishedAt).toBeInstanceOf(Date);
      const ageMs = Date.now() - configDoc.publishedAt.getTime();
      expect(ageMs).toBeGreaterThanOrEqual(0);
      expect(ageMs).toBeLessThan(10 * 60 * 1000);
    } finally {
      await client.close();
    }
  });

  // Design decision D48 (issue #277): the monitor reads the published
  // config document at each poll cycle, and caches it in the app row;
  // the manage view shows it read-only. This scenario is the last of
  // the file (the correction of the brief review of 2026-09-28): it
  // renames the app, so a name change here never breaks a later
  // scenario.
  test('the manage view shows the list the app published', async ({ page }) => {
    const manage = new ManagePage(page);
    await manage.goto();
    const demoRow = manage.row('demo');
    await manage.openEditForm(demoRow);

    // The three patterns of design decision D46 (the same list that
    // "the app publishes its route list" above reads straight from
    // MongoDB): a literal segment before a ":name" segment, then text
    // order.
    await expect(manage.routePatternItems(demoRow)).toHaveText(
      ['/', '/items/:id', '/tracker/:fileName'],
      { timeout: CHAIN_WAIT_MS },
    );
    await expect(manage.publishedLine(demoRow)).toContainText('Published by the app on', {
      timeout: CHAIN_WAIT_MS,
    });

    // A submit of the form with a new name changes the name and not
    // the list (the third acceptance criterion of issue #277).
    await manage.renameAndSave(demoRow, 'demo-renamed');
    const renamedRow = manage.row('demo-renamed');
    await expect(renamedRow).toBeVisible();

    await manage.openEditForm(renamedRow);
    await expect(manage.routePatternItems(renamedRow)).toHaveText([
      '/',
      '/items/:id',
      '/tracker/:fileName',
    ]);
  });
});
