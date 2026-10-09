// The pretest step of `npm test` (decision 1 and decision 3, brief-175).
//
// Playwright reads `playwright.config.ts` before it runs the global
// setup file. This script runs first, through the npm `pretest` hook, so
// three free ports already exist when the config file reads the
// monitor URL for `baseURL`. This script also removes a stale run of a
// former, interrupted suite, so a second run passes (decision 3).
//
// This file stays plain JavaScript. The config, the global setup, and
// the global teardown import TypeScript modules of this same folder
// through the Playwright loader; this one script runs through plain
// `node` before that loader exists.

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const e2eDir = resolve(here, '..');
const repoRoot = resolve(e2eDir, '..');
const runDir = join(e2eDir, '.run');
const stateFile = join(runDir, 'state.json');

// The compose project name of this clone (issue #406). This file stays
// plain JavaScript (see the file comment above), so it cannot import
// the derivation of `constants.ts`; it repeats the same rule instead:
// the override `OCTOMETER_E2E_PROJECT` first, else a hash of the
// clone's absolute path, so two clones never share one project.
function deriveComposeProject() {
  const override = process.env.OCTOMETER_E2E_PROJECT;
  if (override) {
    return override;
  }
  const hash = createHash('sha256').update(repoRoot).digest('hex').slice(0, 8);
  return `octometer-e2e-${hash}`;
}

const composeProject = deriveComposeProject();
const composeFile = join(repoRoot, 'tools', 'demo-app', 'docker-compose.yml');

/** Runs a free-port bind, then closes it at once. A short race stays possible; see the source. */
function pickFreePort() {
  return new Promise((pass, fail) => {
    const server = createServer();
    server.on('error', fail);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : null;
      server.close((closeError) => {
        if (closeError) {
          fail(closeError);
        } else if (port === null) {
          fail(new Error('The port probe gave no port number.'));
        } else {
          pass(port);
        }
      });
    });
  });
}

function removeStaleRun() {
  // A stale compose project of a former, interrupted run of this same
  // clone (decision 3). The derived name of this clone's own project,
  // never the owner's own project "demo-app": this command never
  // touches the owner's demo container.
  try {
    execFileSync('docker', ['compose', '-p', composeProject, 'down', '-v'], {
      cwd: repoRoot,
      stdio: 'ignore',
    });
  } catch {
    // No stale project exists. Continue.
  }
  rmSync(runDir, { recursive: true, force: true });
}

async function main() {
  removeStaleRun();
  mkdirSync(runDir, { recursive: true });
  const [mongo, demo, monitor] = await Promise.all([
    pickFreePort(),
    pickFreePort(),
    pickFreePort(),
  ]);
  const state = {
    ports: { mongo, demo, monitor },
    composeProject,
    repoRoot: repoRoot.split('\\').join('/'),
    pids: { demo: null, monitor: null },
    monitorExtractRoot: null,
    monitorDataDir: null,
    monitorMailDir: null,
  };
  writeFileSync(stateFile, JSON.stringify(state, null, 2), 'utf8');
  console.log(
    `pretest: mongo=${mongo} demo=${demo} monitor=${monitor}, ` +
      `compose project '${composeProject}', compose file '${composeFile}'.`,
  );
}

main().catch((error) => {
  console.error('pretest failed:', error);
  process.exit(1);
});
