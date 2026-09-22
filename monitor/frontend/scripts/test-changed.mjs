#!/usr/bin/env node
// Runs only the specs of a changed source file (issue #177, step 4).
//
// The Angular unit-test builder gives no "--changed" option (confirmed on
// angular/angular-cli tag v22.1.7, packages/angular/build/src/builders/
// unit-test/schema.json: the full option list has no such entry, and "ng
// test --changed" fails with "Unknown argument: changed"). This script
// finds the changed files itself, with the same "compared to a ref" idea
// as the tracker script, then gives each file to "ng test" through
// "--include". The Angular builder maps a source file to its own spec
// file there (see the "--include" entry of the same schema file).
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const base = process.argv[2] ?? 'origin/main';

function gitOutput(args) {
  return execFileSync('git', args, { cwd: projectRoot, encoding: 'utf8' });
}

let diffOutput;
let untrackedOutput;
try {
  // A two-dot diff compares the working tree against the ref directly, so
  // it holds an uncommitted change too (the same rule as vitest's own
  // "--changed" option).
  diffOutput = gitOutput(['diff', '--name-only', '--relative', base]);
  untrackedOutput = gitOutput(['ls-files', '--others', '--exclude-standard']);
} catch {
  console.error(`The command "git diff" against "${base}" failed. Fetch "${base}" first.`);
  process.exit(1);
}

const files = [...diffOutput.split('\n'), ...untrackedOutput.split('\n')]
  .map((line) => line.trim())
  .filter((line) => line.startsWith('src/') && line.endsWith('.ts'))
  .filter((line, index, all) => all.indexOf(line) === index);

if (files.length === 0) {
  console.log(
    `No changed TypeScript file exists under "src/" against "${base}". The test run stays empty.`,
  );
  process.exit(0);
}

console.log(`The test run covers ${files.length} changed file(s) against "${base}":`);
for (const file of files) {
  console.log(`  ${file}`);
}

const testArgs = ['test', '--watch=false', ...files.flatMap((file) => ['--include', file])];
const result = spawnSync('ng', testArgs, { cwd: projectRoot, stdio: 'inherit', shell: true });
process.exit(result.status ?? 1);
