import * as fs from 'node:fs';
import * as path from 'node:path';

/** The folder of one e2e run: `e2e/.run/`. `pretest.mjs` creates it. */
export const RUN_DIR = path.resolve(__dirname, '..', '.run');

/** The one state file of the run. The setup, the config, and the teardown share it. */
export const STATE_FILE = path.join(RUN_DIR, 'state.json');

/**
 * The one storage state file of the run (issue #301, m11). The global
 * setup writes it, and `playwright.config.ts` reads its fixed path at
 * config load time, before the setup ever runs; the new scenario of
 * issue #301 overrides it with `test.use({ storageState: undefined })`.
 */
export const STORAGE_STATE_FILE = path.join(RUN_DIR, 'storage-state.json');

/** The three ports of one run, each free at the moment `pretest.mjs` reserved it. */
export interface RunPorts {
  readonly mongo: number;
  readonly demo: number;
  readonly monitor: number;
}

/** The process ids of the run, for the teardown. `null` before the global setup starts them. */
export interface RunPids {
  demo: number | null;
  monitor: number | null;
}

/** The state of one e2e run, written by `pretest.mjs` and completed by `global-setup.ts`. */
export interface RunState {
  readonly ports: RunPorts;
  readonly composeProject: string;
  readonly repoRoot: string;
  pids: RunPids;
  monitorExtractRoot: string | null;
  monitorDataDir: string | null;
  /** The file mail sender folder of issue #301, D52. Null before the global setup sets it. */
  monitorMailDir: string | null;
}

/** Reads the state file. It throws a clear message when `npm test` did not run `pretest` first. */
export function readState(): RunState {
  if (!fs.existsSync(STATE_FILE)) {
    throw new Error(
      `The state file '${STATE_FILE}' is absent. Run 'npm test' in e2e/, not ` +
        `'npx playwright test' alone: 'npm test' runs 'pretest' first, and 'pretest' ` +
        'reserves the ports and writes this file.',
    );
  }
  return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) as RunState;
}

/** Writes the whole state file again, with an indent of 2 spaces. */
export function writeState(state: RunState): void {
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), 'utf8');
}
