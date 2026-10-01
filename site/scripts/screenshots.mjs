// The screenshot script of the "Three levels" section (issue #289). It
// opens the three read-only views of the local monitor (never "/manage"),
// and writes six PNG files into "site/public/screens/": one pair per view,
// for the two sources of the "<picture>" element.
//
// Run from "site/": node scripts/screenshots.mjs [baseURL]
// The default base URL is the local monitor of the owner's machine. The
// script never changes the monitor; it only reads its pages and its API.
//
// Fix of BLOCKER 1, the review of pull request #324: the two files of one
// view come from the one viewport 1200 by 800 px, with the device scale
// factor as the only difference (2 for the large file, 1 for the phone
// file). A shorter viewport for the phone file cut the data table off; the
// one viewport keeps the full page, at two pixel densities of the one
// picture.
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'public', 'screens');

const VIEWPORT = { width: 1200, height: 800 };
const FILE_VARIANTS = [
  { suffix: '1200', deviceScaleFactor: 2 },
  { suffix: '600', deviceScaleFactor: 1 },
];

/** Reads the first app row of the demo monitor. */
async function readFirstAppId(baseURL) {
  const response = await fetch(`${baseURL}/api/apps`);
  if (!response.ok) {
    throw new Error(`GET /api/apps gave the status ${response.status}.`);
  }
  const rows = await response.json();
  if (!rows.length) {
    throw new Error('The demo monitor has no app row yet.');
  }
  return rows[0].appId;
}

/** Reads the first user row of one app of the demo monitor. */
async function readFirstUserId(baseURL, appId) {
  const response = await fetch(`${baseURL}/api/apps/${appId}/users`);
  if (!response.ok) {
    throw new Error(`GET /api/apps/${appId}/users gave the status ${response.status}.`);
  }
  const body = await response.json();
  if (!body.rows.length) {
    throw new Error('The demo monitor has no user row yet.');
  }
  return body.rows[0].userId;
}

/** Opens one view, waits for its title and its first table row, and takes the shot. */
async function captureAtScale(browser, baseURL, view, deviceScaleFactor) {
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor });
  const page = await context.newPage();
  try {
    await page.goto(`${baseURL}${view.path}`);
    await page.waitForFunction((text) => document.title === text, view.title);
    const row = page.locator('.table-scroll table tbody tr').first();
    await row.waitFor();
    // The guard of BLOCKER 1: a row that sits below the viewport height
    // would print a shot with no table row inside it. The run fails
    // instead of writing that cut shot.
    const rowBox = await row.boundingBox();
    if (!rowBox || rowBox.y + rowBox.height > VIEWPORT.height) {
      throw new Error(
        `The first table row of ${view.path} does not fit the viewport of ` +
          `${VIEWPORT.width} by ${VIEWPORT.height} px; the shot would cut the table off.`,
      );
    }
    return await page.screenshot({ fullPage: false });
  } finally {
    await context.close();
  }
}

async function main() {
  const baseURL = process.argv[2] ?? 'http://127.0.0.1:7431';
  await mkdir(outDir, { recursive: true });

  const appId = await readFirstAppId(baseURL);
  const userId = await readFirstUserId(baseURL, appId);

  const views = [
    { name: 'apps', path: '/apps', title: 'Apps - Octometer' },
    {
      name: 'users',
      path: `/apps/${appId}/users`,
      title: `Users of App ${appId} - Octometer`,
    },
    {
      name: 'elements',
      path: `/apps/${appId}/elements?userId=${encodeURIComponent(userId)}`,
      title: `User ${userId} of App ${appId} - Octometer`,
    },
  ];

  const browser = await chromium.launch();
  try {
    for (const view of views) {
      for (const variant of FILE_VARIANTS) {
        const buffer = await captureAtScale(browser, baseURL, view, variant.deviceScaleFactor);
        const file = join(outDir, `${view.name}-${variant.suffix}.png`);
        await writeFile(file, buffer);
        console.log(`site screenshots: wrote ${file} (${buffer.byteLength} bytes)`);
      }
    }
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error('site screenshots: the run failed:', error);
  process.exit(1);
});
