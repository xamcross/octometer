#!/usr/bin/env node
/**
 * Issue #176, decision 3 (correction of the brief review, 2026-09-27).
 *
 * Reads each `e2e/.run/coverage/js-*.json` file (the raw V8 script
 * coverage of one page, written by `fixtures.ts`), maps it to the
 * original Angular source of `monitor/frontend/src` through
 * `v8-to-istanbul` and the source map of the distribution build (the
 * Gradle property `-PfrontendSourceMap=true`), and writes one lcov file
 * and one text summary.
 *
 * The served bundle sits at
 * `monitor/backend/build/frontend-dist/browser` (not
 * `monitor/frontend/dist/`; confirmed against
 * `monitor/backend/build.gradle.kts`, task `buildFrontend`, on
 * 2026-09-27).
 *
 * When `v8-to-istanbul` cannot map a served file, this script states
 * the reason and gives a raw V8 summary of that file instead: the
 * total byte count of each top-level function range, and the byte
 * count of the ranges with a call count above zero. This is a coarser
 * number than a line count, stated as such in the summary text.
 *
 * The reason, confirmed on 2026-09-27 (the one-hour bound of issue
 * #176, decision 3): the Angular application builder writes each
 * `sources` entry of a `.map` file relative to the built file's own
 * folder, short by exactly one directory level (confirmed with the
 * project's own default, project-relative output path too: the same
 * one-level shortfall shows there, so this is a builder behaviour, not
 * an effect of this project's custom `--output-path`). The default
 * layout hides the defect, because Angular nests the build one level
 * under the given path either way, and the two shortfalls often cancel
 * out by coincidence of the default folder depth. This project's
 * custom `--output-path` (`monitor/backend/build/frontend-dist`, for
 * the distribution) sits outside `monitor/frontend` by four directory
 * levels, not one, so the stored relative path resolves to a folder
 * that does not exist, and `v8-to-istanbul` maps no file. A one-line
 * patch of the `sources` array cannot fix this in general, because the
 * true distance depends on the chosen `--output-path`, not on a fixed
 * offset. Each frontend file therefore falls back to the raw V8
 * summary below.
 */

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import v8toIstanbul from 'v8-to-istanbul';

const here = dirname(fileURLToPath(import.meta.url));
const e2eDir = resolve(here, '..');
const repoRoot = resolve(e2eDir, '..');
const coverageDir = join(e2eDir, '.run', 'coverage');
const frontendDistDir = join(repoRoot, 'monitor', 'backend', 'build', 'frontend-dist', 'browser');
const frontendSrcDir = join(repoRoot, 'monitor', 'frontend', 'src');
const frontendSrcPrefix = frontendSrcDir.endsWith(sep) ? frontendSrcDir : frontendSrcDir + sep;
const lcovOutFile = join(coverageDir, 'frontend-lcov.info');
const summaryOutFile = join(coverageDir, 'frontend-coverage-summary.txt');

/** Reads every `js-<n>.json` file of one e2e run, oldest number first. */
function readCoverageEntries() {
  if (!existsSync(coverageDir)) {
    return [];
  }
  const fileNames = readdirSync(coverageDir).filter((name) => /^js-\d+\.json$/.test(name));
  const entries = [];
  for (const fileName of fileNames) {
    const parsed = JSON.parse(readFileSync(join(coverageDir, fileName), 'utf8'));
    for (const entry of parsed) {
      entries.push(entry);
    }
  }
  return entries;
}

/** Turns a served URL such as "http://127.0.0.1:PORT/main-HASH.js" into a disk path under frontendDistDir. */
function servedUrlToDiskPath(url) {
  try {
    const parsed = new URL(url);
    const relativePath = parsed.pathname.replace(/^\/+/, '');
    if (relativePath.length === 0) {
      return null;
    }
    return join(frontendDistDir, ...relativePath.split('/'));
  } catch {
    return null;
  }
}

/** Turns one file's istanbul statementMap and s (statement hit counts) into a per-line hit map. */
function statementsToLineHits(fileCoverage) {
  const lineHits = new Map();
  for (const [statementId, location] of Object.entries(fileCoverage.statementMap)) {
    const count = fileCoverage.s[statementId] ?? 0;
    for (let line = location.start.line; line <= location.end.line; line += 1) {
      lineHits.set(line, Math.max(lineHits.get(line) ?? 0, count));
    }
  }
  return lineHits;
}

function mergeLineHits(target, source) {
  for (const [line, count] of source) {
    target.set(line, Math.max(target.get(line) ?? 0, count));
  }
}

/** A coarse, function-level byte count: the root range of each function of the entry. */
function rawByteCoverage(entries) {
  let totalBytes = 0;
  let coveredBytes = 0;
  for (const entry of entries) {
    for (const fn of entry.functions ?? []) {
      const rootRange = fn.ranges?.[0];
      if (!rootRange) continue;
      const size = rootRange.endOffset - rootRange.startOffset;
      totalBytes += size;
      if (rootRange.count > 0) {
        coveredBytes += size;
      }
    }
  }
  return { totalBytes, coveredBytes };
}

function writeLcov(mappedFiles) {
  const lines = [];
  for (const [filePath, lineHits] of mappedFiles) {
    lines.push(`SF:${filePath}`);
    const sortedLines = [...lineHits.keys()].sort((a, b) => a - b);
    for (const line of sortedLines) {
      lines.push(`DA:${line},${lineHits.get(line)}`);
    }
    const linesFound = sortedLines.length;
    const linesHit = sortedLines.filter((line) => lineHits.get(line) > 0).length;
    lines.push(`LF:${linesFound}`);
    lines.push(`LH:${linesHit}`);
    lines.push('end_of_record');
  }
  return lines.join('\n') + (lines.length > 0 ? '\n' : '');
}

async function main() {
  mkdirSync(coverageDir, { recursive: true });
  const entries = readCoverageEntries();
  if (entries.length === 0) {
    const message =
      'No e2e/.run/coverage/js-*.json file exists. Run the browser suite before this script.';
    writeFileSync(summaryOutFile, message + '\n', 'utf8');
    writeFileSync(lcovOutFile, '', 'utf8');
    console.log(message);
    return;
  }

  const entriesByUrl = new Map();
  for (const entry of entries) {
    if (!entry.url || !entry.url.startsWith('http')) {
      continue;
    }
    const list = entriesByUrl.get(entry.url) ?? [];
    list.push(entry);
    entriesByUrl.set(entry.url, list);
  }

  const mappedFiles = new Map();
  const unmapped = [];
  const outOfScope = [];

  for (const [url, urlEntries] of entriesByUrl) {
    const diskPath = servedUrlToDiskPath(url);
    if (!diskPath || !existsSync(diskPath)) {
      // No file of monitor/frontend's own build serves this URL (for
      // example the demo app page, or kit/tracker's own script): a
      // different codebase, out of the scope of this report, not a
      // mapping failure of monitor/frontend.
      outOfScope.push({ url, entries: urlEntries });
      continue;
    }
    try {
      const converter = v8toIstanbul(diskPath);
      await converter.load();
      for (const entry of urlEntries) {
        converter.applyCoverage(entry.functions ?? []);
      }
      const istanbulData = converter.toIstanbul();
      let mappedAnyFile = false;
      for (const [filePath, fileCoverage] of Object.entries(istanbulData)) {
        if (!filePath.startsWith(frontendSrcPrefix)) {
          continue;
        }
        if (/\.spec\.ts$/.test(filePath)) {
          continue;
        }
        mappedAnyFile = true;
        const lineHits = statementsToLineHits(fileCoverage);
        const existing = mappedFiles.get(filePath);
        if (existing) {
          mergeLineHits(existing, lineHits);
        } else {
          mappedFiles.set(filePath, lineHits);
        }
      }
      if (!mappedAnyFile) {
        unmapped.push({
          url,
          reason:
            'The source map of this file resolves each source outside monitor/frontend/src ' +
            '(the Angular builder short-folder-level defect stated at the top of this script).',
          entries: urlEntries,
        });
      }
    } catch (error) {
      unmapped.push({ url, reason: String(error && error.message ? error.message : error), entries: urlEntries });
    }
  }

  writeFileSync(lcovOutFile, writeLcov(mappedFiles), 'utf8');

  const summaryLines = [];
  summaryLines.push(`Octometer e2e frontend coverage, ${new Date().toISOString()}`);
  summaryLines.push('');
  summaryLines.push('Mapped files (line coverage, monitor/frontend/src, no *.spec.ts):');
  let grandLinesFound = 0;
  let grandLinesHit = 0;
  const sortedFiles = [...mappedFiles.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [filePath, lineHits] of sortedFiles) {
    const linesFound = lineHits.size;
    const linesHit = [...lineHits.values()].filter((count) => count > 0).length;
    grandLinesFound += linesFound;
    grandLinesHit += linesHit;
    const relativePath = filePath.slice(repoRoot.length + 1).split(sep).join('/');
    const percent = linesFound === 0 ? '0.0' : ((linesHit / linesFound) * 100).toFixed(1);
    summaryLines.push(`  ${relativePath}: ${linesHit}/${linesFound} lines (${percent}%)`);
  }
  const totalPercent = grandLinesFound === 0 ? '0.0' : ((grandLinesHit / grandLinesFound) * 100).toFixed(1);
  summaryLines.push('');
  summaryLines.push(`Total: ${grandLinesHit}/${grandLinesFound} lines (${totalPercent}%)`);

  if (unmapped.length > 0) {
    summaryLines.push('');
    summaryLines.push('monitor/frontend files with no line map (the raw V8 byte coverage of the whole served file):');
    let grandCoveredBytes = 0;
    let grandTotalBytes = 0;
    for (const item of unmapped) {
      const { totalBytes, coveredBytes } = rawByteCoverage(item.entries);
      grandCoveredBytes += coveredBytes;
      grandTotalBytes += totalBytes;
      const bytePercent = totalBytes === 0 ? '0.0' : ((coveredBytes / totalBytes) * 100).toFixed(1);
      summaryLines.push(`  ${item.url}`);
      summaryLines.push(`    Reason: ${item.reason}`);
      summaryLines.push(`    Raw V8 bytes: ${coveredBytes}/${totalBytes} (${bytePercent}%, function-level, not a line count)`);
    }
    const grandBytePercent = grandTotalBytes === 0 ? '0.0' : ((grandCoveredBytes / grandTotalBytes) * 100).toFixed(1);
    summaryLines.push('');
    summaryLines.push(
      `Total raw V8 bytes of these files: ${grandCoveredBytes}/${grandTotalBytes} (${grandBytePercent}%, function-level, not a line count)`,
    );
  }

  if (outOfScope.length > 0) {
    summaryLines.push('');
    summaryLines.push(
      'Out of scope (a different codebase: the demo app page, or the kit/tracker script; not monitor/frontend):',
    );
    for (const item of outOfScope) {
      summaryLines.push(`  ${item.url}`);
    }
  }

  const summaryText = summaryLines.join('\n') + '\n';
  writeFileSync(summaryOutFile, summaryText, 'utf8');
  console.log(summaryText);
}

main().catch((error) => {
  console.error('coverage-report failed:', error);
  process.exitCode = 1;
});
