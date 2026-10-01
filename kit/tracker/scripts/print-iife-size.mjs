// Prints the gzipped size of the IIFE bundle, after each build (design
// decision D53, issue #298). The bound is 6 KB gzipped (the issue
// text). This script fails the build when the bundle breaks that
// bound, so a size regression never reaches a pull request unseen.

import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const BUNDLE_PATH = 'dist/tracker.iife.js';
const MAX_GZIPPED_BYTES = 6 * 1024;

const bytes = readFileSync(BUNDLE_PATH);
const gzippedBytes = gzipSync(bytes).length;
const gzippedKb = (gzippedBytes / 1024).toFixed(2);

console.log(`${BUNDLE_PATH}: ${gzippedBytes} gzipped bytes (${gzippedKb} KB)`);

if (gzippedBytes > MAX_GZIPPED_BYTES) {
  throw new Error(
    `${BUNDLE_PATH} is ${gzippedKb} KB gzipped. The bound is ${MAX_GZIPPED_BYTES / 1024} KB.`,
  );
}
