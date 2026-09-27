#!/usr/bin/env node
/**
 * Issue #176, decision 3 (correction round of pull request #273, MAJOR 1
 * and MAJOR 2 of the review comment, 2026-09-28).
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
 * The map of the frontend. Each `.map` file of this build lists its own
 * `sources` entries as `../src/app/...` or `../src/main.ts`, one
 * directory level above the served file. `v8-to-istanbul` resolves that
 * relative path against the served file's own folder, so the resolved
 * path never lands inside `monitor/frontend/src`. This script reads the
 * `.map` file beside each served file, replaces each `../src/` prefix
 * of `sources` with the absolute path of `monitor/frontend/src`, and
 * passes the rewritten map to `v8-to-istanbul` as an explicit source
 * (`v8toIstanbul(path, 0, { source, sourceMap })`), so the resolved path
 * matches the real file on disk.
 *
 * When `v8-to-istanbul` still maps no path under `monitor/frontend/src`
 * for a served file, this script writes the reason as an error with the
 * file name. It gives no coverage number for that file: a byte count of
 * a whole bundle is not a line count, and it can overstate the real
 * coverage (MAJOR 2 of the review of pull request #273).
 */

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve, sep } from 'node:path';
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

/** The prefix of a map "sources" entry that this build writes for a real frontend file. */
const mapSourcePrefix = '../src/';

/** Rewrites each "../src/..." entry of a source map to the real absolute path of the file. */
function rewriteMapSources(rawMap) {
  const rewrittenSources = rawMap.sources.map((sourcePath) =>
    sourcePath.startsWith(mapSourcePrefix)
      ? join(frontendSrcDir, sourcePath.slice(mapSourcePrefix.length))
      : sourcePath,
  );
  return { ...rawMap, sources: rewrittenSources };
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
    const mapPath = `${diskPath}.map`;
    try {
      if (!existsSync(mapPath)) {
        throw new Error(`the map file "${basename(mapPath)}" is absent`);
      }
      const source = readFileSync(diskPath, 'utf8');
      const rawMap = JSON.parse(readFileSync(mapPath, 'utf8'));
      const rewrittenMap = rewriteMapSources(rawMap);
      const converter = v8toIstanbul(diskPath, 0, { source, sourceMap: { sourcemap: rewrittenMap } });
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
        throw new Error(`the rewritten map of "${basename(diskPath)}" holds no path under monitor/frontend/src`);
      }
    } catch (error) {
      unmapped.push({ url, diskPath, reason: String(error && error.message ? error.message : error) });
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
    summaryLines.push('monitor/frontend files with no line map (an error, not a coverage number):');
    for (const item of unmapped) {
      summaryLines.push(`  ${item.url}`);
      summaryLines.push(`    Error: ${item.reason}`);
    }
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
