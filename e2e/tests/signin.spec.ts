import * as fs from 'node:fs';

import { expect, test } from '../fixtures';
import { AppsPage } from '../pages/apps-page';
import { ManagePage } from '../pages/manage-page';
import { runInviteCommand } from '../setup/invite';
import {
  SIGN_IN_WAIT_BOUND_MS as WAIT_BOUND_MS,
  extractToken,
  mailDir,
  mailFileCount,
  newestMailFile,
  signInNewContext,
  waitForNewMail,
} from '../setup/mail';
import { readState } from '../setup/state';

// Issue #301, D52: this file runs with no storage state of its own, so
// each test signs in on its own, never as the shared owner account of
// the global setup.
test.describe('Sign-in accounts (issue #301)', () => {
  test.use({ storageState: undefined });

  test('two accounts see two different app lists', async ({ page, request, newTrackedContext }) => {
    const dir = mailDir();
    const monitorUrl = process.env.E2E_MONITOR_URL;
    if (!monitorUrl) {
      throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
    }
    const emailA = 'e2e-a@example.com';
    const emailB = 'e2e-b@example.com';
    const emailNobody = 'e2e-nobody@example.com';
    const state = readState();
    runInviteCommand(state, emailA);
    runInviteCommand(state, emailB);

    // A requests a link.
    const beforeA = mailFileCount(dir);
    const signInResponse = await request.post('/api/signin', {
      headers: { Origin: monitorUrl },
      data: { email: emailA },
    });
    expect(signInResponse.status()).toBe(202);
    await waitForNewMail(dir, beforeA);
    const tokenA = extractToken(fs.readFileSync(newestMailFile(dir), 'utf8'));

    // A first GET of the link changes no state: the button still shows
    // on a second GET (M5).
    await page.goto(`/signin?token=${tokenA}`);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

    // The button signs A in.
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/apps$/, { timeout: WAIT_BOUND_MS });

    // A adds an app through the legacy connection-string path of the
    // API (design decision D32, issue #296, the brief review B3): the
    // manage form carries no connection string field any more.
    // page.request, not the bare request fixture: page.request shares
    // the cookie jar of the page's own browser context, so it carries
    // the session cookie that A's sign-in click just set. The bare
    // request fixture is its own, separate context.
    const manage = new ManagePage(page);
    const appName = `signin-a-${Date.now()}`;
    await manage.addAppWithConnectionString(
      page.request,
      monitorUrl,
      appName,
      'mongodb://127.0.0.1:1/exampledb',
    );
    const appsResponse = await page.request.get('/api/apps');
    expect(appsResponse.status()).toBe(200);
    const appRows = (await appsResponse.json()) as ReadonlyArray<{ appId: number; name: string }>;
    const appOfA = appRows.find((row) => row.name === appName);
    if (!appOfA) {
      throw new Error(`The app "${appName}" is absent from the answer of GET /api/apps.`);
    }

    // B signs in with a fresh browser context (no storage state), and
    // sees an empty list: the account filter of issue #301 keeps A's
    // app out of B's answer.
    const contextB = await newTrackedContext();
    const pageB = await contextB.newPage();

    const beforeB = mailFileCount(dir);
    const signInResponseB = await contextB.request.post('/api/signin', {
      headers: { Origin: monitorUrl },
      data: { email: emailB },
    });
    expect(signInResponseB.status()).toBe(202);
    await waitForNewMail(dir, beforeB);
    const tokenB = extractToken(fs.readFileSync(newestMailFile(dir), 'utf8'));
    await pageB.goto(`/signin?token=${tokenB}`);
    await pageB.getByRole('button', { name: 'Sign in' }).click();
    await expect(pageB).toHaveURL(/\/apps$/, { timeout: WAIT_BOUND_MS });

    const appsPageB = new AppsPage(pageB);
    await appsPageB.goto();
    expect(await appsPageB.rowCount()).toBe(0);

    // B opens the URL of the app of A and gets the not-found card
    // (issue #348, D30): the account filter answers 404 for an app
    // that exists but is not B's, the same 404 as an unknown id. The
    // view shows the card in place, and sends no redirect.
    await pageB.goto(`/apps/${appOfA.appId}/users`);
    await expect(pageB.getByText('The app is not registered.')).toBeVisible();
    await expect(pageB).toHaveURL(new RegExp(`/apps/${appOfA.appId}/users$`));

    // An unlisted address gets the same "Check your mail." text, and no
    // mail file.
    const beforeNobody = mailFileCount(dir);
    const signInResponseNobody = await request.post('/api/signin', {
      headers: { Origin: monitorUrl },
      data: { email: emailNobody },
    });
    expect(signInResponseNobody.status()).toBe(202);
    const bodyNobody = (await signInResponseNobody.json()) as { message: string };
    expect(bodyNobody.message).toBe('Check your mail.');
    await pageB.waitForTimeout(1_000);
    const afterNobody = mailFileCount(dir);
    expect(afterNobody).toBe(beforeNobody);

    // A sign-out ends the session (the next /api/me answers 401).
    await pageB.getByRole('button', { name: 'Sign out' }).click();
    await expect(pageB).toHaveURL(/\/signin$/, { timeout: WAIT_BOUND_MS });
    const meResponse = await contextB.request.get('/api/me');
    expect(meResponse.status()).toBe(401);
  });

  test('a signed-in visitor of /signin lands on /apps; a visitor with no session sees the form (issue #347)', async ({
    page,
    newTrackedContext,
  }) => {
    const monitorUrl = process.env.E2E_MONITOR_URL;
    if (!monitorUrl) {
      throw new Error('E2E_MONITOR_URL is absent. The global setup did not run, or it failed.');
    }
    const email = 'e2e-347@example.com';
    const state = readState();
    runInviteCommand(state, email);

    // The signed-in visitor: a fresh context signs in, then a new page
    // of that same context opens the plain /signin route again and
    // must land on /apps with no form in sight.
    const signedInContext = await signInNewContext(newTrackedContext, monitorUrl, email);
    const signedInPage = await signedInContext.newPage();
    await signedInPage.goto('/signin');
    await expect(signedInPage).toHaveURL(/\/apps$/, { timeout: WAIT_BOUND_MS });
    await expect(signedInPage.getByRole('heading', { name: 'Apps' })).toBeVisible();

    // The visitor with no session: the default page of this describe
    // block carries no storage state, so /signin still shows the form.
    // The explicit bound covers a cold Angular bootstrap plus the
    // GET /api/me round trip, on a loaded CI runner.
    await page.goto('/signin');
    await expect(page.getByRole('button', { name: 'Send me a link' })).toBeVisible({
      timeout: WAIT_BOUND_MS,
    });
  });
});
