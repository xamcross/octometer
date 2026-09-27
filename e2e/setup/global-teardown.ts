import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as net from 'node:net';
import * as path from 'node:path';

import { COMPOSE_PROJECT } from './constants';
import { readState, RUN_DIR } from './state';

const POLL_INTERVAL_MS = 500;
const CLOSE_TIMEOUT_MS = 30_000;

function tcpClosed(host: string, port: number): Promise<boolean> {
  return new Promise((pass) => {
    const socket = net.createConnection({ host, port, timeout: 1000 });
    socket.once('connect', () => {
      socket.destroy();
      pass(false);
    });
    socket.once('error', () => {
      socket.destroy();
      pass(true);
    });
    socket.once('timeout', () => {
      socket.destroy();
      pass(true);
    });
  });
}

async function waitClosed(host: string, port: number): Promise<void> {
  const deadline = Date.now() + CLOSE_TIMEOUT_MS;
  for (;;) {
    if (await tcpClosed(host, port)) {
      return;
    }
    if (Date.now() > deadline) {
      // The teardown states the leak; it never leaves the run silently green.
      throw new Error(`The port ${port} on 127.0.0.1 is still open after ${CLOSE_TIMEOUT_MS} ms.`);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
}

/** Kills the process tree of `pid`. A missing process (already gone) is not a failure. */
function killTree(pid: number): void {
  try {
    if (process.platform === 'win32') {
      execFileSync('taskkill', ['/T', '/F', '/PID', String(pid)], { stdio: 'ignore' });
    } else {
      process.kill(pid, 'SIGTERM');
    }
  } catch {
    // The process already ended. Continue.
  }
}

async function waitProcessGone(pid: number): Promise<void> {
  if (process.platform === 'win32') {
    return; // taskkill /F already waited for the kill.
  }
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
      await new Promise((r) => setTimeout(r, 200));
    } catch {
      return; // ESRCH: the process ended.
    }
  }
  try {
    process.kill(pid, 'SIGKILL');
  } catch {
    // Already gone.
  }
}

/**
 * Removes each entry of `e2e/.run/` except a `*.log` file and the
 * `coverage` folder (issue #176, decision 2, correction of the brief
 * review). Playwright gives `globalTeardown` no failure signal of the
 * run (confirmed against the Playwright API, 2026-09-27), so the logs
 * stay every time, not only after a failed run. `E2E_KEEP_LOGS` is void
 * for this same reason.
 */
function removeRunFolderExceptLogs(): void {
  if (!fs.existsSync(RUN_DIR)) {
    return;
  }
  for (const entry of fs.readdirSync(RUN_DIR)) {
    if (entry.endsWith('.log') || entry === 'coverage') {
      continue;
    }
    fs.rmSync(path.join(RUN_DIR, entry), { recursive: true, force: true });
  }
}

/** Reads the TCP port of one "-javaagent:...=output=tcpserver,...,port=<n>,..." line. */
function agentPort(javaOpts: string | undefined): number | null {
  if (!javaOpts) {
    return null;
  }
  const match = /port=(\d+)/.exec(javaOpts);
  return match ? Number(match[1]) : null;
}

/**
 * Dumps one running JaCoCo agent over its TCP server port into a
 * destfile under `e2e/.run/coverage/` (issue #176, decision 2,
 * correction of the brief review). The dump runs before the kill of
 * the JVM process: the teardown force-kills each process below, so a
 * shutdown hook never runs, and the default agent mode "dumponexit"
 * would write nothing. A dump failure is not fatal to the suite; it is
 * logged and the teardown continues.
 */
function dumpJacocoAgent(cliJar: string, port: number, destFile: string): void {
  fs.mkdirSync(path.dirname(destFile), { recursive: true });
  try {
    execFileSync(
      'java',
      ['-jar', cliJar, 'dump', '--address', '127.0.0.1', '--port', String(port), '--destfile', destFile],
      { stdio: 'ignore' },
    );
  } catch (error) {
    console.error(`global-teardown: the JaCoCo dump of port ${port} failed:`, error);
  }
}

/** Dumps the two JaCoCo agents of the run, when the run set them up (decision 2). */
function dumpJacocoCoverage(): void {
  const cliJar = process.env.JACOCO_CLI_JAR;
  if (!cliJar) {
    return;
  }
  const coverageDir = path.join(RUN_DIR, 'coverage');
  const monitorPort = agentPort(process.env.E2E_JAVA_OPTS_MONITOR);
  if (monitorPort !== null) {
    dumpJacocoAgent(cliJar, monitorPort, path.join(coverageDir, 'monitor.exec'));
  }
  const demoPort = agentPort(process.env.E2E_JAVA_OPTS_DEMO);
  if (demoPort !== null) {
    dumpJacocoAgent(cliJar, demoPort, path.join(coverageDir, 'demo.exec'));
  }
}

async function globalTeardown(): Promise<void> {
  const state = readState();
  const { ports, repoRoot, pids } = state;

  // The dump reads each running agent over its TCP port, before the
  // kill of the process ends the agent (decision 2).
  dumpJacocoCoverage();

  if (pids.demo !== null) {
    killTree(pids.demo);
  }
  if (pids.monitor !== null) {
    killTree(pids.monitor);
  }
  if (pids.demo !== null) {
    await waitProcessGone(pids.demo);
  }
  if (pids.monitor !== null) {
    await waitProcessGone(pids.monitor);
  }

  const composeFile = path.join(repoRoot, 'tools', 'demo-app', 'docker-compose.yml');
  try {
    execFileSync('docker', ['compose', '-f', composeFile, '-p', COMPOSE_PROJECT, 'down', '-v'], {
      cwd: repoRoot,
      stdio: 'ignore',
    });
  } catch (error) {
    console.error('global-teardown: docker compose down failed:', error);
  }

  await Promise.all([
    waitClosed('127.0.0.1', ports.demo),
    waitClosed('127.0.0.1', ports.monitor),
    waitClosed('127.0.0.1', ports.mongo),
  ]);

  removeRunFolderExceptLogs();
}

export default globalTeardown;
