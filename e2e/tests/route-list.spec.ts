import { expect, test } from '../fixtures';
import { FirstPagesPage } from '../pages/first-pages-page';
import { ManagePage } from '../pages/manage-page';
import { runInviteCommand } from '../setup/invite';
import { signInNewContext } from '../setup/mail';
import { readState } from '../setup/state';

/**
 * The wait bound of `collector.spec.ts`'s own `CHAIN_WAIT_MS`: the
 * settle lag (1 s), the poll interval (2 s), and the frontend refresh
 * (2 s). This scenario posts through the collector directly, with no
 * browser click and no tracker flush.
 */
const CHAIN_WAIT_MS = 30_000;

/** A fixed, well-formed UUID for one request's `sessionId` field (the form of `collector.spec.ts`). */
function fixedSessionId(marker: number): string {
  const hex = marker.toString(16).padStart(8, '0');
  return `${hex}-0000-4000-8000-000000000000`;
}

/**
 * Issue #299 (D5, D40): the typed route pattern list of the dashboard,
 * with the default rule for an unlisted path. The scenario registers
 * its own app through the hosted key path (issue #296), and it routes
 * its own page the same way `collector.spec.ts` does: this test then
 * runs with no serial order against the other scenarios.
 *
 * No view of the frontend shows a click path (the correction of the
 * brief review of 2026-10-01, B2, B3): the First pages view
 * (`/apps/{id}/first-pages`) is the one view with a path, a session
 * count per path. This scenario posts one session-start event per
 * path, through `page.request.fetch`, each with its own `sessionId`,
 * the exact form of `collector.spec.ts:229-237`.
 */
test('a typed pattern and the default rule group the paths', async ({ page, request, newTrackedContext }) => {
  const monitorUrl = process.env.E2E_MONITOR_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  const appOrigin = 'https://route-list-app.example';

  const appName = `route-list-app-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: [appOrigin] },
  });
  expect(createResponse.status(), await createResponse.text()).toBe(201);
  const created = (await createResponse.json()) as { appId: number; appKey: string };
  const { appId, appKey } = created;
  expect(appId).toBeGreaterThan(0);
  expect(appKey.length).toBeGreaterThan(0);
  const collectorUrl = `${monitorUrl}/collect`;

  const manage = new ManagePage(page);
  await manage.goto();
  const row = manage.row(appName);
  await expect(row).toBeVisible();
  await manage.openEditForm(row);
  await manage.setRoutePatternsAndSave(row, ['/orders/:id']);

  // Lesson 19 (the brief review of 2026-10-01): a state-change scenario
  // reads the stored state through the API after the action, not the
  // page text alone. A closed form can show while the write never
  // happened.
  await expect(async () => {
    const detailResponse = await request.get(`/api/apps/${appId}`);
    expect(detailResponse.status()).toBe(200);
    const detail = (await detailResponse.json()) as { routePatterns: readonly string[] };
    expect(detail.routePatterns).toEqual(['/orders/:id']);
  }).toPass({ timeout: 5_000 });

  // Four paths: the typed pattern /orders/:id groups both /orders/1 and
  // /orders/spring-sale (a raw segment groups under a `:name` pattern
  // regardless of its own shape); the default rule groups the hex
  // segment of /users/7f3a9c1d2b; /about stays as it is.
  const paths: ReadonlyArray<[number, string]> = [
    [1, '/orders/1'],
    [2, '/orders/spring-sale'],
    [3, '/users/7f3a9c1d2b'],
    [4, '/about'],
  ];
  for (const [marker, path] of paths) {
    const response = await page.request.fetch(collectorUrl, {
      method: 'POST',
      headers: { Origin: appOrigin, 'Content-Type': 'application/json', 'X-Octometer-Key': appKey },
      data: JSON.stringify({
        sessionId: fixedSessionId(marker),
        clicks: [{ element: 'octo:session-start', ageMs: 0, path }],
      }),
    });
    expect(response.status(), await response.text()).toBe(204);
  }

  const firstPages = new FirstPagesPage(page);
  await firstPages.goto(appId);
  await firstPages.expectSessions('/orders/:id', 2, CHAIN_WAIT_MS);
  await firstPages.expectSessions('/users/:id', 1, CHAIN_WAIT_MS);
  await firstPages.expectSessions('/about', 1, CHAIN_WAIT_MS);

  // A second save with a bad pattern shows the fixed error text, and
  // never the pattern text itself (issue #226).
  await manage.goto();
  await manage.openEditForm(row);
  await manage.setRoutePatternsAndSave(row, ['no-leading-slash']);
  await expect(manage.editFormError(row)).toContainText('breaks the route pattern grammar');
  await expect(manage.editFormError(row)).not.toContainText('no-leading-slash');

  // The PUT of another account answers 404 (D52, issue #301): a second
  // account, through `page.request` with the setup of `signin.spec.ts`.
  const state = readState();
  const secondEmail = `route-list-b-${Date.now()}@example.com`;
  runInviteCommand(state, secondEmail);
  const secondContext = await signInNewContext(newTrackedContext, monitorUrl, secondEmail);
  const secondResponse = await secondContext.request.put(`/api/apps/${appId}/routes`, {
    headers: { Origin: monitorUrl },
    data: { patterns: ['/checkout'] },
  });
  expect(secondResponse.status()).toBe(404);
});
