// Issue #378, acceptance criterion 2: a check that the three landing
// screenshots are the current capture, not the stale images of findings
// D16, D19, and D22 of the UI audit. The check reads the file system
// only; it opens no page and needs no running Worker.
import { statSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';

// "site/e2e/tests" up two levels to "site/public/screens", the folder
// that "scripts/screenshots.mjs" writes.
const screensDir = join(__dirname, '..', '..', 'public', 'screens');

// The exact byte size of each stale file before issue #378: the apps
// view with the duplicate breadcrumb heading of D22, the "Totals of the
// last 395 days" text of D16, and the empty "First pages" link of D19.
// A recapture that keeps a stale file gives this same size again.
const STALE_SIZE_BYTES: Readonly<Record<string, number>> = {
  'apps-1200.png': 99206,
  'users-1200.png': 98741,
  'elements-1200.png': 112950,
};

test('each landing screenshot file is the current capture, not the stale pre-#378 file', () => {
  for (const [fileName, staleSize] of Object.entries(STALE_SIZE_BYTES)) {
    const filePath = join(screensDir, fileName);
    const stats = statSync(filePath);
    // A stale or an empty file each fail one check below: an empty file
    // fails the first line, and an untouched stale file fails the
    // second line with its own exact byte count (the break proof of
    // the pull request text runs this exact line against the stale
    // value).
    expect(stats.size).toBeGreaterThan(0);
    expect(stats.size).not.toBe(staleSize);
  }
});
