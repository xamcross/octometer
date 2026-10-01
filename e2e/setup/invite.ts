import { execFileSync } from 'node:child_process';
import * as path from 'node:path';

import type { RunState } from './state';

/**
 * Runs `--invite <address>` of the launcher (D2, issue #301), against
 * the monitor data dir of the running suite. M7: this call opens the
 * store only; it never starts a server, so it is safe to run while the
 * real monitor process of the suite is already up (SQLite WAL admits
 * the second, short-lived writer).
 */
export function runInviteCommand(state: RunState, email: string): void {
  if (!state.monitorExtractRoot || !state.monitorDataDir) {
    throw new Error('monitorExtractRoot or monitorDataDir is absent. The global setup did not run.');
  }
  const scriptPath = path.join(
    state.monitorExtractRoot,
    'bin',
    process.platform === 'win32' ? 'backend.bat' : 'backend',
  );
  const env = { ...process.env, OCTOMETER_DATA_DIR: state.monitorDataDir };
  if (process.platform === 'win32') {
    execFileSync('cmd.exe', ['/c', scriptPath, '--invite', email], { env, stdio: 'ignore' });
  } else {
    execFileSync(scriptPath, ['--invite', email], { env, stdio: 'ignore' });
  }
}
