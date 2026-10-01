import { execFileSync, spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as http from 'node:http';
import * as net from 'node:net';
import * as path from 'node:path';
import { URL } from 'node:url';

import { chromium } from '@playwright/test';

import { COMPOSE_PROJECT } from './constants';
import { runInviteCommand } from './invite';
import { readState, RUN_DIR, STORAGE_STATE_FILE, writeState } from './state';

const POLL_INTERVAL_MS = 500;
const READY_TIMEOUT_MS = 60_000;

// Issue #301, D52: the one account of the storage state that every
// existing scenario shares. The new scenario "two accounts see two
// different app lists" makes its own two invites, and it runs with
// `test.use({ storageState: undefined })`, so it never signs in as
// this account.
const OWNER_EMAIL = 'e2e-owner@example.com';

/**
 * The path of the Windows `tar.exe` (bsdtar, ships with Windows since
 * 1803; it reads and writes a zip file, unlike GNU tar). This function
 * names the file under `System32` itself, never the bare word `tar`: a
 * Git Bash shell can put its own `tar` (no zip support) earlier on the
 * PATH, and the bare word would then resolve to the wrong program.
 */
function windowsTarPath(): string {
  return path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe');
}

/** Waits, with a poll each 500 ms and a 60 s bound, never a fixed sleep (decision 2). */
async function waitFor(label: string, check: () => Promise<boolean>): Promise<void> {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  for (;;) {
    if (await check()) {
      return;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out after ${READY_TIMEOUT_MS} ms while it waited for ${label}.`);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
}

function tcpOpen(host: string, port: number): Promise<boolean> {
  return new Promise((pass) => {
    const socket = net.createConnection({ host, port, timeout: 1000 });
    socket.once('connect', () => {
      socket.destroy();
      pass(true);
    });
    socket.once('error', () => {
      socket.destroy();
      pass(false);
    });
    socket.once('timeout', () => {
      socket.destroy();
      pass(false);
    });
  });
}

function httpOk(url: string): Promise<boolean> {
  return new Promise((pass) => {
    const request = http.get(url, { timeout: 2000 }, (response) => {
      response.resume();
      pass(response.statusCode === 200);
    });
    request.on('error', () => pass(false));
    request.on('timeout', () => {
      request.destroy();
      pass(false);
    });
  });
}

// Issue #301, brief review B1: /api/health now needs a session, so the
// global setup can no longer probe it before the owner signs in.
// /healthz needs no session (D52), and it answers "ok" in plain text.
function healthzOk(url: string): Promise<boolean> {
  return new Promise((pass) => {
    const request = http.get(url, { timeout: 2000 }, (response) => {
      const chunks: Buffer[] = [];
      response.on('data', (chunk: Buffer) => chunks.push(chunk));
      response.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        pass(response.statusCode === 200 && body === 'ok');
      });
    });
    request.on('error', () => pass(false));
    request.on('timeout', () => {
      request.destroy();
      pass(false);
    });
  });
}

/** Lists the top-level folder name of a zip file, through the tool of the platform. */
function zipTopLevelDir(zipPath: string): string {
  if (process.platform === 'win32') {
    const listing = execFileSync(windowsTarPath(), ['-tf', zipPath], { encoding: 'utf8' });
    const firstLine = listing.split(/\r?\n/).find((line) => line.trim().length > 0);
    if (!firstLine) {
      throw new Error(`The zip '${zipPath}' lists no entry.`);
    }
    return firstLine.split(/[\\/]/)[0];
  }
  const listing = execFileSync('unzip', ['-l', zipPath], { encoding: 'utf8' });
  const entryLine = listing
    .split(/\r?\n/)
    .find((line) => /\d{2}:\d{2}\s+\S/.test(line) && line.trim().split(/\s+/).length >= 4);
  if (!entryLine) {
    throw new Error(`The zip '${zipPath}' lists no entry (unzip -l gave no matching line).`);
  }
  const entryPath = entryLine.trim().split(/\s+/).slice(3).join(' ');
  return entryPath.split('/')[0];
}

function extractZip(zipPath: string, destDir: string): void {
  fs.mkdirSync(destDir, { recursive: true });
  if (process.platform === 'win32') {
    execFileSync(windowsTarPath(), ['-xf', zipPath, '-C', destDir]);
  } else {
    execFileSync('unzip', ['-q', zipPath, '-d', destDir]);
  }
}

function newestZip(distDir: string): string {
  const zips = fs
    .readdirSync(distDir)
    .filter((name) => name.endsWith('.zip'))
    .map((name) => {
      const fullPath = path.join(distDir, name);
      return { fullPath, mtime: fs.statSync(fullPath).mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
  if (zips.length === 0) {
    throw new Error(
      `The folder '${distDir}' holds no zip file. Run ` +
        "'./gradlew :monitor:backend:distZip' before 'npm test'.",
    );
  }
  return zips[0].fullPath;
}

/** Spawns a process on the platform, so a `.bat` script starts through `cmd.exe` on Windows. */
function spawnScript(
  scriptPath: string,
  env: NodeJS.ProcessEnv,
  cwd: string,
  logPath: string,
): number {
  const logStream = fs.createWriteStream(logPath, { flags: 'a' });
  const child =
    process.platform === 'win32'
      ? spawn('cmd.exe', ['/c', scriptPath], { cwd, env, windowsHide: true })
      : spawn(scriptPath, [], { cwd, env });
  child.stdout?.pipe(logStream);
  child.stderr?.pipe(logStream);
  if (child.pid === undefined) {
    throw new Error(`The process of '${scriptPath}' gave no process id.`);
  }
  return child.pid;
}

/** Posts one JSON body to [url], with the `Origin` header that D12 demands for a POST. */
function postJson(url: string, body: unknown, origin: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const target = new URL(url);
    const request = http.request(
      {
        hostname: target.hostname,
        port: target.port,
        path: target.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(data),
          Origin: origin,
        },
      },
      (response) => {
        response.resume();
        response.on('end', () => {
          if (response.statusCode === 202) {
            resolve();
          } else {
            reject(new Error(`POST ${url} answered ${response.statusCode}, expected 202.`));
          }
        });
      },
    );
    request.on('error', reject);
    request.write(data);
    request.end();
  });
}

/** Waits for [mailDir] to hold at least one file, then gives the path of the newest one. */
async function waitForNewestMailFile(mailDir: string): Promise<string> {
  await waitFor(`a mail file in ${mailDir}`, async () => {
    return fs.existsSync(mailDir) && fs.readdirSync(mailDir).length > 0;
  });
  const files = fs
    .readdirSync(mailDir)
    .map((name) => {
      const fullPath = path.join(mailDir, name);
      return { fullPath, mtime: fs.statSync(fullPath).mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
  return files[0].fullPath;
}

/** Reads the sign-in token out of the mail text of D52 ("...token=<token>. If you..."). */
function extractToken(mailText: string): string {
  const match = /token=([A-Za-z0-9_-]+)/.exec(mailText);
  if (!match) {
    throw new Error(`The mail text carries no token:\n${mailText}`);
  }
  return match[1];
}

/**
 * The sign-in flow of the owner account (issue #301, m11): requests a
 * link, reads the newest mail file, opens the link page, clicks "Sign
 * in", and saves the storage state that every existing scenario uses.
 * A scenario that calls the API directly attaches the cookie from that
 * same storage state (the `request` fixture of Playwright reads the
 * project's `storageState` option).
 */
async function signInOwnerAndSaveStorageState(monitorUrl: string, mailDir: string): Promise<void> {
  await postJson(`${monitorUrl}/api/signin`, { email: OWNER_EMAIL }, monitorUrl);
  const mailFile = await waitForNewestMailFile(mailDir);
  const token = extractToken(fs.readFileSync(mailFile, 'utf8'));

  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    // The link page changes no state (M5): this first GET must not sign
    // the owner in by itself. The click of "Sign in" runs the confirm.
    await page.goto(`${monitorUrl}/signin?token=${token}`);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForURL(`${monitorUrl}/apps`);
    await context.storageState({ path: STORAGE_STATE_FILE });
  } finally {
    await browser.close();
  }
}

async function globalSetup(): Promise<void> {
  const state = readState();
  const { ports, repoRoot } = state;
  const composeFile = path.join(repoRoot, 'tools', 'demo-app', 'docker-compose.yml');

  // (a) The MongoDB container, on a free port never fixed at 27017 or
  // 27117 (the owner's own container and mongod, decision 2a).
  const mongoLog = fs.createWriteStream(path.join(RUN_DIR, 'mongo-compose.log'), { flags: 'a' });
  const composeUp = spawn(
    'docker',
    ['compose', '-f', composeFile, '-p', COMPOSE_PROJECT, 'up', '-d', '--wait'],
    { cwd: repoRoot, env: { ...process.env, OCTOMETER_DEMO_MONGO_PORT: String(ports.mongo) } },
  );
  composeUp.stdout.pipe(mongoLog);
  composeUp.stderr.pipe(mongoLog);
  await new Promise<void>((resolvePromise, reject) => {
    composeUp.once('exit', (code) => {
      if (code === 0) {
        resolvePromise();
      } else {
        reject(new Error(`'docker compose up' exited with code ${code}. Read e2e/.run/mongo-compose.log.`));
      }
    });
  });
  // The compose file has no healthcheck (correction of the brief review):
  // `--wait` returns at "running", so this also polls the TCP port.
  await waitFor(`MongoDB on 127.0.0.1:${ports.mongo}`, () => tcpOpen('127.0.0.1', ports.mongo));

  // (b) The demo app, with anonymous clicks off (Application.kt hard-codes
  // this; the correction of the brief review drops the env var).
  const demoAppDir = path.join(repoRoot, 'tools', 'demo-app', 'build', 'install', 'demo-app');
  const demoScript = path.join(
    demoAppDir,
    'bin',
    process.platform === 'win32' ? 'demo-app.bat' : 'demo-app',
  );
  if (!fs.existsSync(demoScript)) {
    throw new Error(
      `The demo app script '${demoScript}' is absent. Run ` +
        "'./gradlew :tools:demo-app:installDist' before 'npm test'.",
    );
  }
  if (process.platform !== 'win32') {
    fs.chmodSync(demoScript, 0o755);
  }
  // Issue #176, decision 2 (correction of the brief review, 2026-09-27).
  // Each JVM process reads its own agent line, never the same line
  // twice: E2E_JAVA_OPTS_DEMO for this process, E2E_JAVA_OPTS_MONITOR
  // for the monitor below. Both come from "jacocoAgentArgs"
  // (monitor/backend/build.gradle.kts). A run with no such variable
  // changes nothing (decision 2, last sentence).
  const demoEnv: NodeJS.ProcessEnv = {
    ...process.env,
    OCTOMETER_DEMO_MONGO_URI: `mongodb://127.0.0.1:${ports.mongo}`,
    OCTOMETER_DEMO_PORT: String(ports.demo),
    ...(process.env.E2E_JAVA_OPTS_DEMO ? { JAVA_OPTS: process.env.E2E_JAVA_OPTS_DEMO } : {}),
  };
  state.pids.demo = spawnScript(demoScript, demoEnv, demoAppDir, path.join(RUN_DIR, 'demo.log'));
  writeState(state);
  await waitFor(`the demo app on http://127.0.0.1:${ports.demo}/`, () =>
    httpOk(`http://127.0.0.1:${ports.demo}/`),
  );

  // (c) The monitor, from the newest release zip, in dev mode on a free
  // port and a temp data dir under e2e/.run/ (decision 2c). The exact
  // root folder name of the zip is read at run time, never assumed
  // (correction of the brief review).
  const distDir = path.join(repoRoot, 'monitor', 'backend', 'build', 'distributions');
  const zipPath = newestZip(distDir);
  const extractDir = path.join(RUN_DIR, 'monitor-extract');
  extractZip(zipPath, extractDir);
  const topLevelDir = zipTopLevelDir(zipPath);
  const monitorRoot = path.join(extractDir, topLevelDir);
  state.monitorExtractRoot = monitorRoot.split('\\').join('/');
  const monitorScript = path.join(
    monitorRoot,
    'bin',
    process.platform === 'win32' ? 'backend.bat' : 'backend',
  );
  if (!fs.existsSync(monitorScript)) {
    throw new Error(
      `The monitor script '${monitorScript}' is absent. The zip '${zipPath}' gave the top-level ` +
        `folder '${topLevelDir}'; read its 'bin' folder to confirm the exact script name.`,
    );
  }
  if (process.platform !== 'win32') {
    fs.chmodSync(monitorScript, 0o755);
  }
  const monitorDataDir = path.join(RUN_DIR, 'monitor-data');
  fs.mkdirSync(monitorDataDir, { recursive: true });
  state.monitorDataDir = monitorDataDir.split('\\').join('/');
  const monitorMailDir = path.join(RUN_DIR, 'mail');
  fs.mkdirSync(monitorMailDir, { recursive: true });
  state.monitorMailDir = monitorMailDir.split('\\').join('/');
  const monitorUrl = `http://127.0.0.1:${ports.monitor}`;
  const monitorEnv: NodeJS.ProcessEnv = {
    ...process.env,
    OCTOMETER_MODE: 'dev',
    OCTOMETER_PORT: String(ports.monitor),
    OCTOMETER_DATA_DIR: monitorDataDir,
    OCTOMETER_SETTLE_LAG_SECONDS: '1',
    OCTOMETER_POLL_INTERVAL_SECONDS: '2',
    // Issue #301, D52, brief review M8, B3: the file mail sender, its
    // folder, and the loopback public URL that the local run and this
    // suite share, so the file sender's host check passes and the
    // sign-in link carries this same origin.
    OCTOMETER_MAIL_SENDER: 'file',
    OCTOMETER_MAIL_DIR: monitorMailDir,
    OCTOMETER_PUBLIC_URL: monitorUrl,
    // Issue #296, D57: the hosted cluster URI of an app with no
    // connection string of its own. The loopback form that
    // ConnectionStringValidator accepts, on the same MongoDB container
    // as the demo app above; the compose file of tools/local-service
    // is a different service, not this suite's Mongo.
    OCTOMETER_CLUSTER_URI: `mongodb://127.0.0.1:${ports.mongo}/octometer_service`,
    ...(process.env.E2E_JAVA_OPTS_MONITOR ? { JAVA_OPTS: process.env.E2E_JAVA_OPTS_MONITOR } : {}),
  };
  // Issue #301, D2: one run of the launcher, before the monitor starts,
  // adds the owner's own invite. M7: this call opens the store only.
  runInviteCommand(state, OWNER_EMAIL);
  state.pids.monitor = spawnScript(
    monitorScript,
    monitorEnv,
    monitorRoot,
    path.join(RUN_DIR, 'monitor.log'),
  );
  writeState(state);
  // Issue #301, brief review B1: /api/health now needs a session, so
  // this probes /healthz instead, which sits outside /api/ and needs
  // none.
  await waitFor(`the monitor healthz route on ${monitorUrl}/healthz`, () =>
    healthzOk(`${monitorUrl}/healthz`),
  );

  // Issue #301, m11: the owner signs in once, here, and every existing
  // scenario reuses that one session through the storage state.
  await signInOwnerAndSaveStorageState(monitorUrl, monitorMailDir);

  // The demo URL goes to the tests through the environment (decision 2).
  // Playwright test workers start after this function returns, and they
  // inherit this process's environment at that moment.
  process.env.E2E_DEMO_URL = `http://127.0.0.1:${ports.demo}`;
  process.env.E2E_MONITOR_URL = monitorUrl;
  process.env.E2E_MONGO_PORT = String(ports.mongo);
  process.env.E2E_MONITOR_MAIL_DIR = monitorMailDir;
}

export default globalSetup;
