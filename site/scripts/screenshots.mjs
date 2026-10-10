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
// view come from the one viewport, with the device scale factor as the
// only difference (2 for the large file, 1 for the phone file). A shorter
// viewport for the phone file cut the data table off; the one viewport
// keeps the full page, at two pixel densities of the one picture.
//
// Issue #359 (the UI audit finding L1): the viewport width drops from
// 1200 px, so the table fills more of the capture and the row text
// renders larger inside the card at the real display width of the
// landing page. The clip below (the fix of issue #351, missing from this
// script until now) cuts each shot to the header, the title row, the
// refresh bar, the table, and 24 px below it; the live images on `main`
// came from a one-time external crop, never from a committed step.
//
// The apps table has seven columns and does not fit the issue's number
// of about 720 px (it needs 905 px at minimum, confirmed with the local
// monitor); the apps card also spans both grid columns of ".levels", so
// it keeps the wider viewport of "APPS_VIEWPORT_WIDTH" (the pull request
// text of issue #359 states this deviation). The users table and the
// elements table fit the issue's number, so they keep it.
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'public', 'screens');

const VIEWPORT_HEIGHT = 800;
const NARROW_VIEWPORT_WIDTH = 720;
const APPS_VIEWPORT_WIDTH = 920;
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
  const viewport = { width: view.width, height: VIEWPORT_HEIGHT };
  const context = await browser.newContext({ viewport, deviceScaleFactor });
  const page = await context.newPage();
  try {
    await page.goto(`${baseURL}${view.path}`);
    await page.waitForFunction((text) => document.title === text, view.title);
    const scroll = page.locator('.table-scroll').first();
    const row = scroll.locator('table tbody tr').first();
    await row.waitFor();
    // The guard of BLOCKER 1: a row that sits below the viewport height
    // would print a shot with no table row inside it. The run fails
    // instead of writing that cut shot.
    const rowBox = await row.boundingBox();
    if (!rowBox || rowBox.y + rowBox.height > viewport.height) {
      throw new Error(
        `The first table row of ${view.path} does not fit the viewport of ` +
          `${viewport.width} by ${viewport.height} px; the shot would cut the table off.`,
      );
    }
    // The guard against an inner scroll: the table must not scroll past
    // the right edge of the viewport, else the shot would print a cut
    // table (the widest column dropped off the right side).
    const scrollInfo = await scroll.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }));
    if (scrollInfo.scrollWidth > scrollInfo.clientWidth + 1) {
      throw new Error(
        `The table of ${view.path} is ${scrollInfo.scrollWidth}px wide, past the ` +
          `viewport width of ${viewport.width}px; the shot would cut a column off.`,
      );
    }
    // The crop of issue #351: the header, the title row, the refresh bar,
    // and the table, with 24 px below it. A shot past the viewport height
    // fails the run instead of printing a cut table.
    const scrollBox = await scroll.boundingBox();
    if (!scrollBox) {
      throw new Error(`The page of ${view.path} has no ".table-scroll" element.`);
    }
    const clipHeight = Math.ceil(scrollBox.y + scrollBox.height + 24);
    if (clipHeight > viewport.height) {
      throw new Error(
        `The table of ${view.path} ends at ${clipHeight}px, past the viewport height of ` +
          `${viewport.height}px; the shot would cut the table off.`,
      );
    }
    return await page.screenshot({
      fullPage: false,
      clip: { x: 0, y: 0, width: viewport.width, height: clipHeight },
    });
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
    { name: 'apps', path: '/apps', title: 'Apps - Octometer', width: APPS_VIEWPORT_WIDTH },
    {
      name: 'users',
      path: `/apps/${appId}/users`,
      title: `Users of App ${appId} - Octometer`,
      width: NARROW_VIEWPORT_WIDTH,
    },
    {
      name: 'elements',
      path: `/apps/${appId}/elements?userId=${encodeURIComponent(userId)}`,
      title: `User ${userId} of App ${appId} - Octometer`,
      width: NARROW_VIEWPORT_WIDTH,
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
