import type { APIRequestContext } from '@playwright/test';
import { request as apiRequestFactory } from '@playwright/test';

import { expect, test } from '../fixtures';
import { UsersPage } from '../pages/users-page';

/**
 * The wait bound of `collector.spec.ts`'s own `CHAIN_WAIT_MS`: the
 * tracker flush, the settle lag, the poll interval, and the frontend
 * refresh.
 */
const CHAIN_WAIT_MS = 30_000;

/** The `lastSuccessAt` field of `appId`'s own row of `GET /api/apps`, or `null` when absent. */
async function readLastSuccessAt(request: APIRequestContext, appId: number): Promise<string | null> {
  const response = await request.get('/api/apps');
  const rows = (await response.json()) as ReadonlyArray<{ appId: number; lastSuccessAt: string | null }>;
  const row = rows.find((entry) => entry.appId === appId);
  return row?.lastSuccessAt ?? null;
}

/**
 * Waits for `cycles` poll cycles of `appId` to complete, each one seen
 * through a change of `lastSuccessAt` (the same pattern as
 * `collector.spec.ts`: lesson 20 of `brief-backend.md`, never an
 * immediate, always-green read).
 */
async function waitForPollCycles(
  request: APIRequestContext,
  appId: number,
  cycles: number,
  timeoutMs: number,
): Promise<void> {
  let previous = await readLastSuccessAt(request, appId);
  for (let cycle = 0; cycle < cycles; cycle += 1) {
    await expect(async () => {
      const current = await readLastSuccessAt(request, appId);
      expect(current).not.toBeNull();
      expect(current).not.toBe(previous);
    }).toPass({ timeout: timeoutMs });
    previous = await readLastSuccessAt(request, appId);
  }
}

interface UserTotalsRow {
  userId: string | null;
  clicks: number;
  sessions: number;
  uniqueElements: number;
}

/** The level 2 rows of `appId`'s own `GET /api/apps/{id}/users`. */
async function readUserRows(request: APIRequestContext, appId: number): Promise<UserTotalsRow[]> {
  const response = await request.get(`/api/apps/${appId}/users`);
  const body = (await response.json()) as { rows: UserTotalsRow[] };
  return body.rows;
}

/**
 * Waits, through the raw API and not the frontend's own 2-second
 * refresh timer, for `predicate` to match one row of `appId`'s own
 * users. The frontend table reads the exact same backend data, so this
 * wait proves the backend side of the chain (the tracker flush, the
 * collector write, the poll cycle) finished, before the test spends
 * its own timeout budget on the frontend's one extra refresh.
 */
async function waitForUserRow(
  request: APIRequestContext,
  appId: number,
  predicate: (row: UserTotalsRow) => boolean,
  timeoutMs: number,
): Promise<void> {
  await expect(async () => {
    const rows = await readUserRows(request, appId);
    expect(rows.some(predicate)).toBe(true);
  }).toPass({ timeout: timeoutMs });
}

/**
 * A small static page with one `data-octo` button and the served
 * script tag of design decision D53 (the model of `served-tracker.spec.ts`),
 * with the `data-user-id` attribute of issue #300 added.
 */
function pageHtml(monitorUrl: string, appKey: string, userId: string): string {
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>User id erasure test page</title></head>
<body>
<button type="button" data-octo="user-id-erasure.click">Click</button>
<script src="${monitorUrl}/tracker.js" data-app-key="${appKey}" data-user-id="${userId}"></script>
</body>
</html>`;
}

/**
 * "A user id appears in the users view and one erasure removes it"
 * (issue #300, design decisions D54 and D55). This scenario registers
 * its own app, through the hosted path of `POST /api/apps` (issue
 * #296), so it runs with no serial order against the other scenarios
 * of this suite.
 *
 * The page comes through `context.route`, the same mechanism as
 * `served-tracker.spec.ts`. The erasure calls use a fresh
 * `APIRequestContext` with no storage state (the correction round of
 * 2026-10-01, B3): the secret header alone must carry the call, with
 * no session cookie of this suite's own global sign-in.
 *
 * The wrong-secret call runs before the real erasure, not after
 * (a deliberate reorder of the issue text's own order): the user must
 * still exist for "removes nothing" to be an assertion that can fail
 * (lessons 16 to 20 of `brief-backend.md`). A wrong secret that erased
 * the user by mistake would otherwise leave this test green by
 * accident, because the real erasure right after would erase it anyway.
 */
test('a user id appears in the users view and one erasure removes it', async ({
  page,
  request,
  newTrackedContext,
}) => {
  // This scenario chains two real collector flows and two real erasure
  // calls, each with its own poll-cycle wait; the default test timeout
  // of 60 s is too tight a ceiling for the sum (playwright.config.ts).
  test.setTimeout(150_000);

  const monitorUrl = process.env.E2E_MONITOR_URL;
  const demoUrl = process.env.E2E_DEMO_URL;
  if (!monitorUrl) {
    throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
  }
  if (!demoUrl) {
    throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
  }

  const appName = `user-id-erasure-app-${Date.now()}`;
  const createResponse = await request.post('/api/apps', {
    headers: { Origin: monitorUrl },
    data: { name: appName, origins: [demoUrl] },
  });
  expect(createResponse.status()).toBe(201);
  const created = (await createResponse.json()) as { appId: number; appKey: string; secretKey: string };
  const { appId, appKey, secretKey } = created;
  expect(appId).toBeGreaterThan(0);
  expect(appKey.length).toBeGreaterThan(0);
  expect(secretKey.length).toBeGreaterThan(0);

  const userId = 'team/42';
  const testPageUrl = `${demoUrl}/user-id-erasure-test-page`;
  const context = await newTrackedContext();
  await context.route(
    (url) => url.toString() === testPageUrl,
    async (route) => {
      await route.fulfill({ status: 200, contentType: 'text/html', body: pageHtml(monitorUrl, appKey, userId) });
    },
  );
  const testPage = await context.newPage();
  await testPage.goto(testPageUrl);
  const clickButton = testPage.getByRole('button', { name: 'Click' });
  await clickButton.click();
  await clickButton.click();

  // The raw API wait proves the backend chain finished (the tracker
  // flush, the collector write, the poll cycle), before the UI check
  // below spends its own budget on the frontend's one extra refresh.
  await waitForUserRow(
    request,
    appId,
    (row) => row.userId === userId && row.clicks === 2,
    CHAIN_WAIT_MS,
  );
  const users = new UsersPage(page);
  await users.goto(appId);
  // Acceptance criterion 1: a user id with a slash works, and the two
  // clicks of the one session show under it.
  await users.expectRow(userId, { clicks: 2, sessions: 1, uniqueElements: 1 }, CHAIN_WAIT_MS);

  // A fresh context with no storage state (B3): the secret header
  // alone decides the next two calls, never this suite's own session
  // cookie.
  const secretContext = await apiRequestFactory.newContext({ baseURL: monitorUrl });
  try {
    // Acceptance criterion 1: a wrong secret answers 404 and removes
    // nothing. markerWrongSecretValue is a fixture value, never a real
    // credential (the secret scan rule generic-api-key).
    const markerWrongSecretValue = 'not-the-real-secret-key';
    const wrongSecretResponse = await secretContext.fetch(
      `/api/apps/${appId}/events?userId=${encodeURIComponent(userId)}`,
      { method: 'DELETE', headers: { 'X-Octometer-Secret': markerWrongSecretValue } },
    );
    expect(wrongSecretResponse.status()).toBe(404);
    await waitForPollCycles(request, appId, 2, CHAIN_WAIT_MS);
    await users.goto(appId);
    await users.expectRow(userId, { clicks: 2, sessions: 1, uniqueElements: 1 }, CHAIN_WAIT_MS);

    // Acceptance criterion 1: one erasure call removes the user from
    // the cluster and the cache, with the percent-encoded id.
    const eraseResponse = await secretContext.fetch(
      `/api/apps/${appId}/events?userId=${encodeURIComponent(userId)}`,
      { method: 'DELETE', headers: { 'X-Octometer-Secret': secretKey } },
    );
    expect(eraseResponse.status()).toBe(200);
    const eraseBody = (await eraseResponse.json()) as {
      deleted: number;
      checkpointed: boolean;
      clusterDeleted: number;
    };
    expect(eraseBody.deleted).toBeGreaterThan(0);
    // BLOCKER 2 of the review of pull request #333: the cluster row
    // count of eraseUserEventsFromCluster, beside the cache row count.
    // The session holds three cluster documents of this user: the one
    // octo:session-start document and the two click documents. The
    // users view shows "clicks: 2" because that count excludes the
    // session-start document; the cluster count does not.
    expect(eraseBody.clusterDeleted).toBe(3);
  } finally {
    await secretContext.dispose();
  }

  await waitForPollCycles(request, appId, 2, CHAIN_WAIT_MS);
  await expect(async () => {
    const rows = await readUserRows(request, appId);
    expect(rows.some((row) => row.userId === userId)).toBe(false);
  }).toPass({ timeout: CHAIN_WAIT_MS });
  await users.goto(appId);
  await users.expectNoRow(userId, CHAIN_WAIT_MS);

  // Acceptance criterion 1: a click without a usable id counts as
  // anonymous. An email address never passes (design decision D54).
  const anonymousUserId = 'someone@example.com';
  const anonymousPageUrl = `${demoUrl}/user-id-erasure-test-page-anonymous`;
  await context.route(
    (url) => url.toString() === anonymousPageUrl,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'text/html',
        body: pageHtml(monitorUrl, appKey, anonymousUserId),
      });
    },
  );
  const anonymousPage = await context.newPage();
  await anonymousPage.goto(anonymousPageUrl);
  await anonymousPage.getByRole('button', { name: 'Click' }).click();

  await waitForUserRow(
    request,
    appId,
    (row) => row.userId === null && row.clicks === 1,
    CHAIN_WAIT_MS,
  );
  await users.goto(appId);
  const anonymousLocators = users.rowOf(null);
  await expect(anonymousLocators.clicks).toHaveText('1', { timeout: CHAIN_WAIT_MS });
  await expect(anonymousLocators.sessions).toHaveText('1', { timeout: CHAIN_WAIT_MS });
  await expect(anonymousLocators.uniqueElements).toHaveText('1', { timeout: CHAIN_WAIT_MS });
  await expect(users.rowOf(anonymousUserId).row).toHaveCount(0);
});
