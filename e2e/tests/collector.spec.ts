import { expect, test } from '../fixtures';
import { AppsPage } from '../pages/apps-page';
import { FirstPagesPage } from '../pages/first-pages-page';

/**
 * The wait bound of `octometer.spec.ts`'s own `CHAIN_WAIT_MS`: the
 * tracker flush (1 s), the settle lag (1 s), the poll interval (2 s),
 * and the frontend refresh (2 s).
 */
const CHAIN_WAIT_MS = 30_000;

/** A fixed, well-formed UUID for one test request's `sessionId` field. */
function fixedSessionId(marker: number): string {
  const hex = marker.toString(16).padStart(8, '0');
  return `${hex}-0000-4000-8000-000000000000`;
}

/**
 * The collector of design decision D50 (issue #297). This scenario
 * registers its own app, through the hosted path of `POST /api/apps`
 * (issue #296), so it runs with no serial order against
 * `octometer.spec.ts`'s own "demo" app chain.
 *
 * It drives the real demo page of `tools/demo-app` for its one real
 * click (acceptance criterion 1), through `context.route`: the demo
 * app process itself keeps its own blank `OCTOMETER_DEMO_COLLECTOR_URL`
 * and `OCTOMETER_DEMO_APP_KEY` (so `octometer.spec.ts`'s own 12
 * scenarios, which read the demo app's own kit ingest route, need no
 * change), and this one test's own browser context rewrites the page's
 * two data attributes to the app this test just registered. Read the
 * pull request text for the full reasoning.
 */
test('a click in the demo page reaches the apps view through the collector', async ({
  page,
  request,
  newTrackedContext,
}) => {
  const monitorUrl = process.env.E2E_MONITOR_URL;
  const demoUrl = process.env.E2E_DEMO_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  if (!demoUrl) {
    throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
  }

  const appName = `collector-app-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: [demoUrl] },
  });
  expect(createResponse.status()).toBe(201);
  const created = (await createResponse.json()) as { appId: number; appKey: string };
  const { appId, appKey } = created;
  expect(appId).toBeGreaterThan(0);
  expect(appKey.length).toBeGreaterThan(0);
  const collectorUrl = `${monitorUrl}/collect`;

  const apps = new AppsPage(page);

  // Acceptance criterion 1: one click on a data-octo element of the
  // demo page reaches the apps view through the collector, within two
  // poll cycles.
  const context = await newTrackedContext();
  await context.route(
    (url) => url.origin === new URL(demoUrl).origin && url.pathname === '/',
    async (route) => {
      const response = await route.fetch();
      const body = (await response.text())
        .replace('data-octo-collector-url=""', `data-octo-collector-url="${collectorUrl}"`)
        .replace('data-octo-app-key=""', `data-octo-app-key="${appKey}"`);
      await route.fulfill({ response, body });
    },
  );
  const demoPage = await context.newPage();
  await demoPage.goto(demoUrl);
  await demoPage.getByRole('button', { name: 'Button one' }).click();

  await apps.goto();
  await apps.expectTotals(appName, { clicks: 1, uniqueUsers: 0, uniqueSessions: 1 }, CHAIN_WAIT_MS);

  // Acceptance criterion 2 (an answer code of design decision D50): an
  // unknown key answers 204, and it changes no number. markerUnknownAppKey
  // is a fixture value, never a real credential (the secret scan rule
  // generic-api-key).
  const markerUnknownAppKey = 'not-a-real-app-key';
  const unknownKeyResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: { Origin: demoUrl, 'Content-Type': 'application/json', 'X-Octometer-Key': markerUnknownAppKey },
    data: JSON.stringify({
      sessionId: fixedSessionId(1),
      clicks: [{ element: 'collector-probe.unknown-key', ageMs: 0 }],
    }),
  });
  expect(unknownKeyResponse.status()).toBe(204);
  await apps.goto();
  await apps.expectTotals(appName, { clicks: 1, uniqueUsers: 0, uniqueSessions: 1 }, CHAIN_WAIT_MS);

  // Acceptance criterion 2: a request from an unlisted origin answers
  // 204, and it changes no number.
  const unlistedOriginResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: {
      Origin: 'https://unlisted-origin.example',
      'Content-Type': 'application/json',
      'X-Octometer-Key': appKey,
    },
    data: JSON.stringify({
      sessionId: fixedSessionId(2),
      clicks: [{ element: 'collector-probe.unlisted-origin', ageMs: 0 }],
    }),
  });
  expect(unlistedOriginResponse.status()).toBe(204);
  await apps.goto();
  await apps.expectTotals(appName, { clicks: 1, uniqueUsers: 0, uniqueSessions: 1 }, CHAIN_WAIT_MS);

  // Acceptance criterion 2: two requests with two different
  // `X-Test-Client-Ip` values (the suite's own stand-in for
  // `Fly-Client-IP`, through `OCTOMETER_CLIENT_IP_HEADER`) get two rate
  // limit counters, read through the dev-only test hook of the brief
  // review of 2026-10-01 (M5).
  const firstClientIp = '203.0.113.10';
  const secondClientIp = '203.0.113.20';
  const firstIpResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: {
      Origin: demoUrl,
      'Content-Type': 'application/json',
      'X-Octometer-Key': appKey,
      'X-Test-Client-Ip': firstClientIp,
    },
    data: JSON.stringify({
      sessionId: fixedSessionId(3),
      clicks: [{ element: 'collector-probe.first-ip', ageMs: 0 }],
    }),
  });
  expect(firstIpResponse.status()).toBe(204);
  const secondIpResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: {
      Origin: demoUrl,
      'Content-Type': 'application/json',
      'X-Octometer-Key': appKey,
      'X-Test-Client-Ip': secondClientIp,
    },
    data: JSON.stringify({
      sessionId: fixedSessionId(4),
      clicks: [{ element: 'collector-probe.second-ip', ageMs: 0 }],
    }),
  });
  expect(secondIpResponse.status()).toBe(204);

  const countersResponse = await request.get('/api/dev/collector-counters');
  expect(countersResponse.status()).toBe(200);
  const countersBody = (await countersResponse.json()) as {
    counters: ReadonlyArray<{ key: string; count: number }>;
  };
  const firstKey = `${appId}:${firstClientIp}`;
  const secondKey = `${appId}:${secondClientIp}`;
  const firstCounter = countersBody.counters.find((entry) => entry.key === firstKey);
  const secondCounter = countersBody.counters.find((entry) => entry.key === secondKey);
  if (!firstCounter || !secondCounter) {
    throw new Error(
      `The counters of ${firstKey} and ${secondKey} are both expected; got: ` +
        JSON.stringify(countersBody.counters),
    );
  }
  expect(firstCounter.count).toBeGreaterThanOrEqual(1);
  expect(secondCounter.count).toBeGreaterThanOrEqual(1);
  expect(firstKey).not.toBe(secondKey);

  // Acceptance criterion 2: a body over 16 KB answers 400.
  const oversizedPath = `/${'a'.repeat(20_000)}`;
  const oversizedResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: { Origin: demoUrl, 'Content-Type': 'application/json', 'X-Octometer-Key': appKey },
    data: JSON.stringify({
      sessionId: fixedSessionId(5),
      clicks: [{ element: 'collector-probe.oversized', ageMs: 0, path: oversizedPath }],
    }),
  });
  expect(oversizedResponse.status()).toBe(400);

  // Acceptance criterion: a path `/orders/42` lands as `/orders/:id`
  // (design decision D40's default rule, `octometer.kit.core.path.DefaultRoutePattern`).
  // A session-start entry carries the path; the first-pages view shows
  // the stored pattern, with no account filter on the user (the
  // collector stores no user id in this story, B3).
  const sessionStartResponse = await request.fetch(collectorUrl, {
    method: 'POST',
    headers: { Origin: demoUrl, 'Content-Type': 'application/json', 'X-Octometer-Key': appKey },
    data: JSON.stringify({
      sessionId: fixedSessionId(6),
      clicks: [{ element: 'octo:session-start', ageMs: 0, path: '/orders/42' }],
    }),
  });
  expect(sessionStartResponse.status()).toBe(204);

  const firstPages = new FirstPagesPage(page);
  await firstPages.goto(appId);
  await firstPages.expectSessions('/orders/:id', 1, CHAIN_WAIT_MS);
});
