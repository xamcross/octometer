// The waitlist scenario of issue #291: an address joins the list,
// confirms through the mail link, and can remove itself. The harness
// runs the real Worker (playwright.config.ts), with "MAIL_MODE=file", so
// this test reads the newest mail row of the local D1 database directly
// (the brief review correction M1), never through a route of its own.
import { execSync } from 'node:child_process';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';

// "site/e2e/tests" up two levels to "site", the directory of
// "wrangler.jsonc" and of the local D1 state. "__dirname" matches
// "playwright.config.ts", the same file under the same test runner.
const siteDir = join(__dirname, '..', '..');

const SUCCESS_TEXT =
  'Check your mail. Open the confirmation link, and your address joins the list.';
const ADDRESS_ERROR_TEXT =
  'We need a full address, such as you@example.com. Correct the address, then send it again.';

function runD1Query(sql: string): unknown[] {
  const output = execSync(
    `npx wrangler d1 execute WAITLIST --local --json --command "${sql}"`,
    { cwd: siteDir, encoding: 'utf8' },
  );
  const rows = JSON.parse(output) as Array<{ results?: unknown[] }>;
  return rows[0]?.results ?? [];
}

function readLatestOutboxBody(): string {
  const results = runD1Query('SELECT body FROM outbox ORDER BY at DESC LIMIT 1') as Array<{
    body: string;
  }>;
  if (results.length === 0) {
    throw new Error('The outbox table holds no row.');
  }
  return results[0].body;
}

function countOutboxRows(): number {
  const results = runD1Query('SELECT COUNT(*) AS n FROM outbox') as Array<{ n: number }>;
  return Number(results[0]?.n ?? 0);
}

function extractLink(body: string, path: string): string {
  const pattern = new RegExp(`https?://\\S*${path}\\?token=[A-Za-z0-9_-]+`);
  const match = body.match(pattern);
  if (!match) {
    throw new Error(`The mail body holds no link to "${path}".`);
  }
  return match[0];
}

async function joinWithAddress(page: import('@playwright/test').Page, address: string) {
  await page.fill('#waitlist-email', address);
  await page.locator('form.waitlist-form button[type="submit"]').click();
}

test('an address joins the list, confirms through the link, and can remove itself', async ({
  page,
  context,
}) => {
  const stamp = Date.now();
  const address = `waitlist-${stamp}@example.com`;
  const status = page.locator('#waitlist-status');

  await page.goto('/');
  await joinWithAddress(page, address);
  await expect(status).toHaveText(SUCCESS_TEXT);

  const mailBody = readLatestOutboxBody();
  expect(mailBody).toContain('/confirm?token=');
  const confirmLink = extractLink(mailBody, '/confirm');
  const removeLink = extractLink(mailBody, '/remove');

  await page.goto(confirmLink);
  await page.locator('form[action="/confirm"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are on the list.');

  await page.goto(removeLink);
  await page.locator('form[action="/remove"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are removed.');

  // An invalid address shows the error text, in the same status region.
  await page.goto('/');
  await joinWithAddress(page, 'not-an-address');
  await expect(status).toHaveText(ADDRESS_ERROR_TEXT);

  // Five submits of one address each send a mail; a sixth submit within
  // the hour shows the same success text, and writes no new outbox row
  // (the per-address rate limit of the maintainer's notes).
  const limitedAddress = `rate-${stamp}@example.com`;
  for (let i = 0; i < 5; i++) {
    await page.goto('/');
    await joinWithAddress(page, limitedAddress);
    await expect(status).toHaveText(SUCCESS_TEXT);
  }
  const countBeforeSixthSubmit = countOutboxRows();
  await page.goto('/');
  await joinWithAddress(page, limitedAddress);
  await expect(status).toHaveText(SUCCESS_TEXT);
  expect(countOutboxRows()).toBe(countBeforeSixthSubmit);

  // The honeypot: a filled "website" field gives the success text, and
  // writes no row (the outbox count stays the same).
  const honeypotAddress = `honeypot-${stamp}@example.com`;
  const countBeforeHoneypot = countOutboxRows();
  await page.goto('/');
  await page.fill('#waitlist-email', honeypotAddress);
  await page.evaluate(() => {
    const field = document.getElementById('waitlist-website') as HTMLInputElement | null;
    if (field) {
      field.value = 'a bot filled this field';
    }
  });
  await page.locator('form.waitlist-form button[type="submit"]').click();
  await expect(status).toHaveText(SUCCESS_TEXT);
  expect(countOutboxRows()).toBe(countBeforeHoneypot);

  // No cookie after the whole flow: the join, the confirm, and the
  // remove each answer with no "Set-Cookie" header.
  const cookies = await context.cookies();
  expect(cookies).toHaveLength(0);
});
