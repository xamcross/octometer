import { expect, test } from '../fixtures';
import { AppsPage } from '../pages/apps-page';

/**
 * The wait bound of `collector.spec.ts`'s own `CHAIN_WAIT_MS`: the
 * tracker flush, the settle lag, the poll interval, and the frontend
 * refresh.
 */
const CHAIN_WAIT_MS = 30_000;

/**
 * A small static page with one `data-octo` button and the served
 * script tag of design decision D53. `appKey` is `null` for the
 * "no data-app-key" scenario of acceptance criterion 1 (the issue
 * text): the script tag then holds no `data-app-key` attribute at all.
 */
function pageHtml(monitorUrl: string, appKey: string | null): string {
  const scriptTag =
    appKey === null
      ? `<script src="${monitorUrl}/tracker.js"></script>`
      : `<script src="${monitorUrl}/tracker.js" data-app-key="${appKey}"></script>`;
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>Served tracker test page</title></head>
<body>
<button type="button" data-octo="served-tracker.click">Click</button>
${scriptTag}
</body>
</html>`;
}

/**
 * "A page with a served script tag sends a click" (design decision
 * D53, issue #298). This scenario registers its own app, through the
 * hosted path of `POST /api/apps` (issue #296), so it runs with no
 * serial order against the other scenarios of this suite.
 *
 * The static page comes through `context.route` on the demo origin,
 * the same mechanism as `collector.spec.ts:101-112` (a `file://` page
 * sends `Origin: null`, which the app's own origin list would never
 * match). The page's own origin (the demo origin) is the one origin
 * this test registers; the script's own `src` points cross-origin, at
 * the monitor's `/tracker.js`, which is fine for a plain script tag
 * (no CORS applies to it).
 */
test('a page with the served script tag sends a click', async ({ page, request, newTrackedContext }) => {
  const monitorUrl = process.env.E2E_MONITOR_URL;
  const demoUrl = process.env.E2E_DEMO_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  if (!demoUrl) {
    throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
  }

  const appName = `served-tracker-app-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: [demoUrl] },
  });
  expect(createResponse.status()).toBe(201);
  const created = (await createResponse.json()) as { appId: number; appKey: string };
  const { appId, appKey } = created;
  expect(appId).toBeGreaterThan(0);
  expect(appKey.length).toBeGreaterThan(0);

  const testPageUrl = `${demoUrl}/served-tracker-test-page`;
  const context = await newTrackedContext();
  await context.route(
    (url) => url.toString() === testPageUrl,
    async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: pageHtml(monitorUrl, appKey) });
    },
  );
  const testPage = await context.newPage();
  await testPage.goto(testPageUrl);
  await testPage.getByRole('button', { name: 'Click' }).click();

  // Acceptance criterion 1: the click reaches the apps view, with no
  // other install than the one script tag. expectTotals retries its
  // own assertion up to CHAIN_WAIT_MS, the real wait of the tracker
  // flush, the settle lag, and the poll cycle (lesson 20 of
  // brief-backend.md: never an immediate, always-green read).
  const apps = new AppsPage(page);
  await apps.goto();
  await apps.expectTotals(appName, { clicks: 1, uniqueUsers: 0, uniqueSessions: 1 }, CHAIN_WAIT_MS);

  // Acceptance criterion 3 (the issue text) and the maintainer's note
  // (design decision D53, the brief review of 2026-10-01, B5, M1, M2):
  // the headers of GET /tracker.js. The hash comes from GET /api/me,
  // the real source the keys panel itself reads (never a value this
  // test invents).
  const meResponse = await request.get('/api/me');
  expect(meResponse.status()).toBe(200);
  const me = (await meResponse.json()) as { trackerHash: string | null };
  expect(me.trackerHash).not.toBeNull();
  const trackerHash = me.trackerHash as string;

  const unhashedResponse = await request.get(`${monitorUrl}/tracker.js`);
  expect(unhashedResponse.status()).toBe(200);
  expect(unhashedResponse.headers()['content-type']).toBe('text/javascript; charset=utf-8');
  expect(unhashedResponse.headers()['etag']).toBe(`"${trackerHash}"`);
  expect(unhashedResponse.headers()['cache-control']).toBe('public, max-age=3600');

  const hashedResponse = await request.get(`${monitorUrl}/tracker.js?v=${trackerHash}`);
  expect(hashedResponse.status()).toBe(200);
  expect(hashedResponse.headers()['cache-control']).toBe('public, max-age=31536000, immutable');

  const wrongHashResponse = await request.get(`${monitorUrl}/tracker.js?v=not-the-real-hash`);
  expect(wrongHashResponse.status()).toBe(200);
  expect(wrongHashResponse.headers()['cache-control']).toBe('public, max-age=3600');

  // Acceptance criterion 1's own guard: a page with no data-app-key
  // sends no request to /collect, and it starts no session either.
  // page.on('request') starts before the navigation, so this reads
  // the real network effect (lesson 17 of brief-frontend.md), never a
  // declared value of the page.
  const noKeyPageUrl = `${demoUrl}/served-tracker-test-page-no-key`;
  await context.route(
    (url) => url.toString() === noKeyPageUrl,
    async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: pageHtml(monitorUrl, null) });
    },
  );
  const noKeyPage = await context.newPage();
  const requestedUrls: string[] = [];
  noKeyPage.on('request', (oneRequest) => requestedUrls.push(oneRequest.url()));
  await noKeyPage.goto(noKeyPageUrl);
  // The page's own script bails out at once, with no event to wait
  // for, the same bound wait as the negative assertion of
  // signin.spec.ts:155.
  await noKeyPage.waitForTimeout(1_000);
  // Lesson 18 of brief-frontend.md: assert the count before the loop
  // of assertions below, so a broken intercept (an empty list) fails
  // loudly here, never silently through a vacuous "none of zero"
  // match.
  expect(requestedUrls.length).toBeGreaterThan(0);
  expect(requestedUrls.some((url) => url.includes('/collect'))).toBe(false);
});
