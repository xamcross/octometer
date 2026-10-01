// The pretest step of "npm test" for the site suite (issue #287,
// correction B2, M8). The config needs one free port before it starts, so
// this script picks the port first, then starts Playwright with that port
// in the environment.
//
// The pick is a bind on port 0, then a close at once, the same pattern as
// e2e/setup/pretest.mjs:29-47. Two agents on the one machine then never
// clash on one fixed port.

import { execSync } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

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

async function main() {
  const port = await pickFreePort();
  console.log(`site e2e: port=${port}`);
  // A single command string through the default shell, so the one call
  // works on Windows (a ".cmd" shim) and on Linux CI alike.
  execSync('npx playwright test', {
    cwd: here,
    stdio: 'inherit',
    env: { ...process.env, SITE_E2E_PORT: String(port) },
  });
}

main().catch((error) => {
  console.error('site e2e run failed:', error);
  process.exit(1);
});
