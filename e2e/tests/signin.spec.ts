import * as fs from 'node:fs';
import * as path from 'node:path';

import { expect, test } from '../fixtures';
import { AppsPage } from '../pages/apps-page';
import { ManagePage } from '../pages/manage-page';
import { runInviteCommand } from '../setup/invite';
import { readState } from '../setup/state';

/** The wait bound of the sign-in flow: the mail file, the link page, the confirm. */
const WAIT_BOUND_MS = 20_000;

function newestMailFile(mailDir: string): string {
  const files = fs
    .readdirSync(mailDir)
    .map((name) => {
      const fullPath = path.join(mailDir, name);
      return { fullPath, mtime: fs.statSync(fullPath).mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
  if (files.length === 0) {
    throw new Error(`The mail folder '${mailDir}' holds no file.`);
  }
  return files[0].fullPath;
}

function extractToken(mailText: string): string {
  const match = /token=([A-Za-z0-9_-]+)/.exec(mailText);
  if (!match) {
    throw new Error(`The mail text carries no token:\n${mailText}`);
  }
  return match[1];
}

/** Waits, with the bound of [WAIT_BOUND_MS], for [mailDir] to hold one more file than [beforeCount]. */
async function waitForNewMail(mailDir: string, beforeCount: number): Promise<void> {
  await expect
    .poll(() => (fs.existsSync(mailDir) ? fs.readdirSync(mailDir).length : 0), { timeout: WAIT_BOUND_MS })
    .toBeGreaterThan(beforeCount);
}

function mailDir(): string {
  const value = process.env.E2E_MONITOR_MAIL_DIR;
  if (!value) {
    throw new Error('E2E_MONITOR_MAIL_DIR is absent. The global setup did not run, or it failed.');
  }
  return value;
}

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
    const beforeA = fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
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

    // A adds an app through the present manage form.
    const manage = new ManagePage(page);
    await manage.goto();
    const appName = `signin-a-${Date.now()}`;
    await manage.addApp({ name: appName, connectionString: 'mongodb://127.0.0.1:1/exampledb' });

    // page.request, not the bare request fixture: page.request shares
    // the cookie jar of the page's own browser context, so it carries
    // the session cookie that A's sign-in click just set. The bare
    // request fixture is its own, separate context.
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

    const beforeB = fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
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

    // B opens the URL of the app of A and gets the not-found state
    // (D30, the existing 404 redirect of the users view, now driven by
    // the account filter instead of an unknown id). The view clears the
    // query parameter from the URL right after it shows the message
    // (D30), so this asserts the message and the final path, not the
    // transient query string.
    await pageB.goto(`/apps/${appOfA.appId}/users`);
    await expect(pageB.getByText(`App ${appOfA.appId} is not registered.`)).toBeVisible();
    await expect(pageB).toHaveURL(/\/apps$/, { timeout: WAIT_BOUND_MS });

    // An unlisted address gets the same "Check your mail." text, and no
    // mail file.
    const beforeNobody = fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
    const signInResponseNobody = await request.post('/api/signin', {
      headers: { Origin: monitorUrl },
      data: { email: emailNobody },
    });
    expect(signInResponseNobody.status()).toBe(202);
    const bodyNobody = (await signInResponseNobody.json()) as { message: string };
    expect(bodyNobody.message).toBe('Check your mail.');
    await pageB.waitForTimeout(1_000);
    const afterNobody = fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
    expect(afterNobody).toBe(beforeNobody);

    // A sign-out ends the session (the next /api/me answers 401).
    await pageB.getByRole('button', { name: 'Sign out' }).click();
    await expect(pageB).toHaveURL(/\/signin$/, { timeout: WAIT_BOUND_MS });
    const meResponse = await contextB.request.get('/api/me');
    expect(meResponse.status()).toBe(401);
  });
});
