// The waitlist scenario of issue #291: an address joins the list,
// confirms through the mail link, and can remove itself. The harness
// runs the real Worker (playwright.config.ts), with "MAIL_MODE=file", so
// this test reads the newest mail row of the local D1 database directly
// (the brief review correction M1), never through a route of its own.
import { execSync } from 'node:child_process';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';

// Issue #426: "site/src/styles/site.css:16" sets a smooth page scroll.
// "reducedMotion: 'reduce'" turns on the rule of "site.css:813-816",
// "scroll-behavior: auto", for each click of this file. No scroll of this
// file is then active when the click check of Playwright runs.
test.use({ reducedMotion: 'reduce' });

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

  // Issue #358: the hero CTA, the section heading, and the submit button
  // show three different labels, each by its own role and name.
  await expect(page.getByRole('link', { name: 'Join the waitlist' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Get an invite' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Send me the link' })).toBeVisible();

  await joinWithAddress(page, address);
  await expect(status).toHaveText(SUCCESS_TEXT);

  const mailBody = readLatestOutboxBody();
  expect(mailBody).toContain('/confirm?token=');
  const confirmLink = extractLink(mailBody, '/confirm');
  const removeLink = extractLink(mailBody, '/remove');

  await page.goto(confirmLink);
  await page.locator('form[action="/confirm"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are on the list.');

  // The correction round of pull request #326, BLOCKER 1: the page text
  // alone proves nothing of the row. "confirmByToken" must have written
  // "confirmed_at".
  const confirmedRows = runD1Query(
    `SELECT confirmed_at FROM waitlist WHERE email = '${address}'`,
  ) as Array<{ confirmed_at: number | null }>;
  expect(confirmedRows).toHaveLength(1);
  expect(confirmedRows[0].confirmed_at).not.toBeNull();

  await page.goto(removeLink);
  await page.locator('form[action="/remove"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are removed.');

  // The correction round of pull request #326, BLOCKER 1 and MAJOR 2:
  // "removeByToken" deletes the row (the notice says "we remove"), so
  // the proof of the remove is that the row is gone, not a flag on it.
  const removedRows = runD1Query(`SELECT email FROM waitlist WHERE email = '${address}'`);
  expect(removedRows).toHaveLength(0);

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

  // The correction round of pull request #326, MAJOR 1: a re-join of a
  // row that "reopenWaitlistRow" touches (an unconfirmed address, or an
  // address that unsubscribed before #326) must refresh "created_at",
  // so the lazy cleanup of a later request never deletes a row that
  // just sent a fresh link. A row joined and removed more than 7 days
  // ago, before pull request #326 deleted on remove, is the one case
  // "d1 execute" can still seed (a wait of 7 days is not possible in a
  // test, and the live remove route deletes a row since MAJOR 2).
  const reopenAddress = `reopen-${stamp}@example.com`;
  await page.goto('/');
  await joinWithAddress(page, reopenAddress);
  await expect(status).toHaveText(SUCCESS_TEXT);
  const firstReopenLinks = readLatestOutboxBody();
  await page.goto(extractLink(firstReopenLinks, '/confirm'));
  await page.locator('form[action="/confirm"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are on the list.');

  const eightDaysAgo = Date.now() - 8 * 24 * 60 * 60 * 1000;
  runD1Query(
    `UPDATE waitlist SET unsubscribed_at = ${Date.now()}, created_at = ${eightDaysAgo} ` +
      `WHERE email = '${reopenAddress}'`,
  );

  await page.goto('/');
  await joinWithAddress(page, reopenAddress);
  await expect(status).toHaveText(SUCCESS_TEXT);
  const reopenConfirmLink = extractLink(readLatestOutboxBody(), '/confirm');

  // A request of a different address runs the lazy cleanup again; a row
  // with a stale "created_at" would not survive this second pass.
  await page.goto('/');
  await joinWithAddress(page, `cleanup-trigger-${stamp}@example.com`);
  await expect(status).toHaveText(SUCCESS_TEXT);

  await page.goto(reopenConfirmLink);
  await page.locator('form[action="/confirm"] button[type="submit"]').click();
  await expect(page.locator('h1')).toHaveText('You are on the list.');
  const reopenedRows = runD1Query(
    `SELECT confirmed_at FROM waitlist WHERE email = '${reopenAddress}'`,
  ) as Array<{ confirmed_at: number | null }>;
  expect(reopenedRows).toHaveLength(1);
  expect(reopenedRows[0].confirmed_at).not.toBeNull();

  // The correction round of pull request #326, MAJOR 4: "page.request"
  // sends no browser "Origin" header of its own, so this call sets a
  // wrong one by hand, and proves the CSRF guard of "POST /api/waitlist".
  const originAddress = `origin-check-${stamp}@example.com`;
  const wrongOriginResponse = await page.request.post('/api/waitlist', {
    headers: { Origin: 'https://evil.example.com', 'Content-Type': 'application/json' },
    data: JSON.stringify({ email: originAddress, website: '' }),
  });
  expect(wrongOriginResponse.status()).toBe(403);
  expect(await wrongOriginResponse.body()).toHaveLength(0);

  // A wrong "Host" answers "404" (the host check that keeps a preview
  // with the production "WAITLIST" binding from writing a row).
  const hostAddress = `host-check-${stamp}@example.com`;
  const wrongHostResponse = await page.request.post('/api/waitlist', {
    headers: { Host: 'evil.example.com', 'Content-Type': 'application/json' },
    data: JSON.stringify({ email: hostAddress, website: '' }),
  });
  expect(wrongHostResponse.status()).toBe(404);

  // No cookie after the whole flow: the join, the confirm, and the
  // remove each answer with no "Set-Cookie" header.
  const cookies = await context.cookies();
  expect(cookies).toHaveLength(0);
});

// Issue #381, audit finding L6: the panel used about one third of its
// row at 48rem and above. This scenario measures the panel width
// against the row width at 1280 px, and checks the field and the
// button of a narrow viewport (390 px).
test('the panel fills its row at 1280 px, and the form stays usable at 390 px', async ({
  page,
}) => {
  await page.goto('/');

  const row = page.locator('.wl');
  const panel = page.locator('form.waitlist-form');
  const input = page.locator('#waitlist-email');
  const button = page.locator('form.waitlist-form button[type="submit"]');

  // Issue #381, acceptance criterion 2: the email field shows the
  // placeholder "you@company.com".
  await expect(input).toHaveAttribute('placeholder', 'you@company.com');

  const rowBox = await row.boundingBox();
  const panelBox = await panel.boundingBox();
  if (!rowBox || !panelBox) {
    throw new Error('The row or the panel has no box at 1280 px.');
  }
  // The panel reaches the new cap of 48rem (768 px), far more than the
  // old cap of 26rem (416 px); audit finding L6. The pilot list of
  // step 5 takes the rest of the row, so the row itself has no gap.
  expect(panelBox.width).toBeGreaterThan(750);
  expect(panelBox.width).toBeLessThan(790);
  expect(panelBox.width / rowBox.width).toBeGreaterThan(0.6);

  const inputBox = await input.boundingBox();
  const buttonBox = await button.boundingBox();
  if (!inputBox || !buttonBox) {
    throw new Error('The input or the button has no box at 1280 px.');
  }
  // The field and the button sit on one row: each box crosses the
  // vertical range of the other box.
  expect(inputBox.y).toBeLessThan(buttonBox.y + buttonBox.height);
  expect(buttonBox.y).toBeLessThan(inputBox.y + inputBox.height);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');

  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  const viewportWidth = await page.evaluate(() => window.innerWidth);
  // The page has no horizontal scrollbar at 390 px.
  expect(scrollWidth).toBeLessThanOrEqual(viewportWidth);

  const narrowRowBox = await row.boundingBox();
  const narrowInputBox = await input.boundingBox();
  const narrowButtonBox = await button.boundingBox();
  if (!narrowRowBox || !narrowInputBox || !narrowButtonBox) {
    throw new Error('The row, the input, or the button has no box at 390 px.');
  }
  // The field and the button each fill most of the row at 390 px.
  expect(narrowInputBox.width / narrowRowBox.width).toBeGreaterThan(0.85);
  expect(narrowButtonBox.width / narrowRowBox.width).toBeGreaterThan(0.85);

  // Issue #442 (the correction of the review of pull request #440): the
  // ".wl" grid of issue #381, with the collapsed ".wl-pilot" column,
  // scrolled the page sideways by 14 px at 768 px (and at 800 px and
  // 870 px, the width band of the old defect). "scrollWidth" equal to
  // "clientWidth" means no sideways scroll.
  for (const w of [768, 800, 870]) {
    await page.setViewportSize({ width: w, height: 800 });
    await page.goto('/');
    const probe = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(probe.scrollWidth).toBe(probe.clientWidth);
  }
});
