/**
 * The entry of the served IIFE bundle `dist/tracker.iife.js` (design
 * decision D53, issue #298; the brief review of 2026-10-01, B1 and B2).
 *
 * The monitor serves this bundle at `GET /tracker.js`. One script tag
 * starts the tracker, with no npm install and no bundler:
 *
 * ```html
 * <script src="https://app.0ct0meter.com/tracker.js" data-app-key="..."></script>
 * ```
 *
 * At load, this entry reads its own `<script>` element through
 * `document.currentScript`, and two data attributes:
 *
 * - `data-app-key` (required): the app key of design decision D50.
 * - `data-endpoint` (optional): the ingest URL. The default is the
 *   origin of the script's own `src`, plus `/collect`.
 * - `data-user-id` (optional): a pseudonymous id of the signed-in user
 *   (issue #300, design decision D54), passed to the `userId` option of
 *   `createTracker` unchanged.
 *
 * With no `data-app-key`, this entry writes one console warning and
 * starts nothing.
 *
 * This file is the only code that runs at import time of the IIFE
 * bundle. `index.ts`, the ESM entry of the npm package, stays free of
 * such code ("sideEffects": false, design decision D24).
 */

import { createTracker } from './index.js';

const WARNING_PREFIX = 'octometer:';

/** The origin of the script's own `src`, plus `/collect`, or `null` when `src` fails to parse. */
function defaultEndpoint(scriptElement: HTMLScriptElement): string | null {
  try {
    return `${new URL(scriptElement.src).origin}/collect`;
  } catch {
    return null;
  }
}

function startFromScriptTag(): void {
  const scriptElement = document.currentScript;
  if (!(scriptElement instanceof HTMLScriptElement)) {
    console.warn(`${WARNING_PREFIX} the tracker script found no data-app-key attribute. It starts nothing.`);
    return;
  }
  const appKey = scriptElement.getAttribute('data-app-key');
  if (appKey === null || appKey.length === 0) {
    console.warn(`${WARNING_PREFIX} the tracker script holds no data-app-key attribute. It starts nothing.`);
    return;
  }
  const endpointAttribute = scriptElement.getAttribute('data-endpoint');
  const endpoint =
    endpointAttribute !== null && endpointAttribute.length > 0
      ? endpointAttribute
      : defaultEndpoint(scriptElement);
  if (endpoint === null) {
    console.warn(`${WARNING_PREFIX} the tracker script could not read its own src. It starts nothing.`);
    return;
  }
  const userIdAttribute = scriptElement.getAttribute('data-user-id');
  const options: Parameters<typeof createTracker>[0] =
    userIdAttribute !== null && userIdAttribute.length > 0
      ? { endpoint, appKey, userId: userIdAttribute }
      : { endpoint, appKey };
  createTracker(options).start();
}

startFromScriptTag();
