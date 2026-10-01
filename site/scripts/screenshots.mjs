// The screenshot script of the "Three levels" section (issue #289). It
// opens the three read-only views of the local monitor (never "/manage"),
// and writes six PNG files into "site/public/screens/": one pair per view,
// at 1200 px width for the desktop source and at 600 px for the phone
// source of the "<picture>" element.
//
// Run from "site/": node scripts/screenshots.mjs [baseURL]
// The default base URL is the local monitor of the owner's machine. The
// script never changes the monitor; it only reads its pages and its API.
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'public', 'screens');

// The issue names the limit of one file: 150 kB (correction B3 of the
// brief review; the mockup names a different number, and the issue wins).
const SIZE_LIMIT_BYTES = 150 * 1024;
const SCALE_FACTORS_BY_SIZE = [2, 1.5, 1];

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

/** Opens one view, waits for its title and its table rows, and takes the shot. */
async function captureAtScale(browser, baseURL, view, viewport, deviceScaleFactor) {
  const context = await browser.newContext({ viewport, deviceScaleFactor });
  const page = await context.newPage();
  try {
    await page.goto(`${baseURL}${view.path}`);
    await page.waitForFunction((text) => document.title === text, view.title);
    await page.locator('.table-scroll table tbody tr').first().waitFor();
    return await page.screenshot({ fullPage: false });
  } finally {
    await context.close();
  }
}

/** Repeats the shot at a lower scale factor until the file fits the limit. */
async function captureUnderLimit(browser, baseURL, view, viewport) {
  let buffer = null;
  for (const deviceScaleFactor of SCALE_FACTORS_BY_SIZE) {
    buffer = await captureAtScale(browser, baseURL, view, viewport, deviceScaleFactor);
    if (buffer.byteLength <= SIZE_LIMIT_BYTES) {
      return buffer;
    }
  }
  return buffer;
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
  // The two viewports keep one aspect ratio (3:2), so the phone source
  // halves the download with no crop (correction B3 of the brief review).
  const sizes = [
    { suffix: '1200', viewport: { width: 1200, height: 800 } },
    { suffix: '600', viewport: { width: 600, height: 400 } },
  ];

  const browser = await chromium.launch();
  try {
    for (const view of views) {
      for (const size of sizes) {
        const buffer = await captureUnderLimit(browser, baseURL, view, size.viewport);
        const file = join(outDir, `${view.name}-${size.suffix}.png`);
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
