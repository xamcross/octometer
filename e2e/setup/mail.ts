import * as fs from 'node:fs';
import * as path from 'node:path';

import { expect } from '@playwright/test';

/**
 * The file-mail helpers of the sign-in flow (issue #301, D52), shared
 * between `signin.spec.ts` and `route-list.spec.ts` (issue #299, the
 * correction of the brief review of 2026-10-01, M9): a second account
 * of a scenario reads its own sign-in token from the same mail
 * directory, with the same wait rule.
 */

/** The wait bound of the sign-in flow: the mail file, the link page, the confirm. */
export const SIGN_IN_WAIT_BOUND_MS = 20_000;

/** The monitor's own mail folder of the running suite (`E2E_MONITOR_MAIL_DIR`). */
export function mailDir(): string {
  const value = process.env.E2E_MONITOR_MAIL_DIR;
  if (!value) {
    throw new Error('E2E_MONITOR_MAIL_DIR is absent. The global setup did not run, or it failed.');
  }
  return value;
}

export function newestMailFile(dir: string): string {
  const files = fs
    .readdirSync(dir)
    .map((name) => {
      const fullPath = path.join(dir, name);
      return { fullPath, mtime: fs.statSync(fullPath).mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
  if (files.length === 0) {
    throw new Error(`The mail folder '${dir}' holds no file.`);
  }
  return files[0].fullPath;
}

export function extractToken(mailText: string): string {
  const match = /token=([A-Za-z0-9_-]+)/.exec(mailText);
  if (!match) {
    throw new Error(`The mail text carries no token:\n${mailText}`);
  }
  return match[1];
}

/** Waits, with the bound of [SIGN_IN_WAIT_BOUND_MS], for [dir] to hold one more file than [beforeCount]. */
export async function waitForNewMail(dir: string, beforeCount: number): Promise<void> {
  await expect
    .poll(() => (fs.existsSync(dir) ? fs.readdirSync(dir).length : 0), { timeout: SIGN_IN_WAIT_BOUND_MS })
    .toBeGreaterThan(beforeCount);
}

/** Counts the files of [dir] now, or 0 when the folder is absent yet. */
export function mailFileCount(dir: string): number {
  return fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
}

/**
 * Signs a fresh browser context in as [email] (issue #301, D52), the
 * flow of `signin.spec.ts`: a POST of `/api/signin`, the mail token,
 * `GET /signin?token=`, and the "Sign in" click. Gives the context
 * back, so a caller still reaches `context.request` with the session
 * cookie set.
 */
export async function signInNewContext(
  newTrackedContext: () => Promise<import('@playwright/test').BrowserContext>,
  monitorUrl: string,
  email: string,
): Promise<import('@playwright/test').BrowserContext> {
  const dir = mailDir();
  const context = await newTrackedContext();
  const page = await context.newPage();
  const before = mailFileCount(dir);
  const signInResponse = await context.request.post('/api/signin', {
    headers: { Origin: monitorUrl },
    data: { email },
  });
  expect(signInResponse.status()).toBe(202);
  await waitForNewMail(dir, before);
  const token = extractToken(fs.readFileSync(newestMailFile(dir), 'utf8'));
  await page.goto(`/signin?token=${token}`);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/apps$/, { timeout: SIGN_IN_WAIT_BOUND_MS });
  return context;
}
