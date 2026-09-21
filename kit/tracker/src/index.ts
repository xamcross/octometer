/**
 * The click tracker of Octometer.
 *
 * The tracker sends a click on a `data-octo` element to the ingest route of
 * the app (design decision D24, contract rule C12). The tracker is off until
 * the app calls `start()`. Do this only after the consent signal of the app.
 *
 * The module runs no code at import time and takes no action during
 * server-side rendering, where `document` is absent.
 */

import { matchPreparedPath, prepareRoutes, type PreparedRoute } from './path-match.js';

/** An option of the tracker. `endpoint` is mandatory; the rest have a default. */
export interface TrackerOptions {
  /** The ingest URL of the app, for example `/api/octometer/v1/clicks`. */
  endpoint: string;
  /** The `fetch` credentials mode. The default is `same-origin`. */
  credentials?: RequestCredentials;
  /** A function that returns extra request headers. */
  headers?: () => Record<string, string>;
  /** The delay, in milliseconds, before the tracker sends a filled queue. The default is 5000. */
  flushIntervalMs?: number;
  /**
   * The ordered route pattern list of the app (contract rule C42), for
   * example `['/', '/articles', '/articles/*', '/history/:id']`. With this
   * option, each click entry holds `path`: the pattern text, with a `*`
   * segment kept as the real segment. Without this option, no click entry
   * holds `path`.
   */
  routes?: readonly string[];
}

/** The tracker instance. Call `start()` after consent, and `stop()` to end tracking. */
export interface Tracker {
  /** Starts the click listener. A second call has no extra effect. */
  start(): void;
  /**
   * Empties the queue, stops the pending timer, and removes the click
   * listener. It also removes the session id from `sessionStorage`, but
   * only when `start()` ran at least one time before.
   */
  stop(): void;
}

const SESSION_STORAGE_KEY = 'octo_session_id';
/** The `element` rule of the contract, rule C4: 1 to 100 characters, this pattern. */
const ELEMENT_PATTERN = /^[A-Za-z0-9_.:-]{1,100}$/;
/**
 * The reserved element prefix of the contract, rule C38, in each letter
 * case. A click on a `data-octo` value with this prefix records no click,
 * with no exception: issue #107 sends `octo:session-start` through its
 * own call, never through a click on a page element.
 */
const RESERVED_ELEMENT_PREFIX = /^octo:/i;
/** The queue holds a maximum of 200 entries (design decision D24). */
const MAX_QUEUE_SIZE = 200;
/** One request holds a maximum of 50 clicks (contract rule C17). */
const MAX_BATCH_SIZE = 50;
const DEFAULT_FLUSH_INTERVAL_MS = 5000;
const DEFAULT_CREDENTIALS: RequestCredentials = 'same-origin';

interface QueueEntry {
  element: string;
  queuedAt: number;
  /** The stored path of the click, present only with the routes option. */
  path?: string;
}

/** Creates one tracker instance. The tracker holds its own queue and its own session id. */
export function createTracker(options: TrackerOptions): Tracker {
  if (typeof options.endpoint !== 'string' || options.endpoint.length === 0) {
    throw new TypeError('The tracker needs a non-empty endpoint.');
  }
  const endpoint = options.endpoint;
  const credentials = options.credentials ?? DEFAULT_CREDENTIALS;
  const getExtraHeaders = options.headers;
  const flushIntervalMs = options.flushIntervalMs ?? DEFAULT_FLUSH_INTERVAL_MS;
  // The routes option is checked and split one time here, not at each
  // click: a bad entry is dropped with one console warning that names its
  // index, and an empty or an all-bad list counts as no routes option at
  // all (contract rule C42 gives the same rule to the server).
  const preparedRoutes = prepareRoutesOption(options.routes);

  let queue: QueueEntry[] = [];
  let timerId: ReturnType<typeof setTimeout> | null = null;
  let sessionId: string | null = null;
  let started = false;
  let everStarted = false;
  let droppedOctoPrefix = false;
  let clickListener: ((event: Event) => void) | null = null;

  function start(): void {
    if (started) {
      return;
    }
    if (typeof document === 'undefined') {
      // The tracker takes no action during server-side rendering.
      return;
    }
    started = true;
    everStarted = true;
    clickListener = (event: Event) => handleClick(event);
    document.addEventListener('click', clickListener, true);
  }

  function stop(): void {
    started = false;
    queue = [];
    if (timerId !== null) {
      clearTimeout(timerId);
      timerId = null;
    }
    if (clickListener !== null && typeof document !== 'undefined') {
      document.removeEventListener('click', clickListener, true);
    }
    clickListener = null;
    sessionId = null;
    droppedOctoPrefix = false;
    if (everStarted) {
      // A started tracker always removes the key, also with no flush before.
      // A tracker that never started still touches no storage.
      everStarted = false;
      removeStoredSessionId();
    }
  }

  function handleClick(event: Event): void {
    const eventPath = typeof event.composedPath === 'function' ? event.composedPath() : [];
    const target = findOctoElement(eventPath);
    if (target === null || isDisabled(target)) {
      return;
    }
    const name = target.getAttribute('data-octo');
    if (name === null || !ELEMENT_PATTERN.test(name)) {
      return;
    }
    // The prefix octo: belongs to the contract, not to an app page (rule
    // C38). Issue #107 sends octo:session-start through its own call.
    if (RESERVED_ELEMENT_PREFIX.test(name)) {
      if (!droppedOctoPrefix) {
        droppedOctoPrefix = true;
        console.warn('octometer: a data-octo value must not start with "octo:". The tracker drops it.');
      }
      return;
    }
    // The path of a click is the page at the time of the click, not the
    // page at the time of the later flush (an app can navigate between the
    // two, because the tracker holds the click in its queue).
    const path = computePath(preparedRoutes);
    enqueue(name, path);
  }

  /**
   * Matches the current page against the prepared routes list. Gives
   * `undefined` without a routes option. Gives `/other` when the matcher
   * itself throws: no throw of the tracker reaches the page, also here.
   */
  function computePath(routes: readonly PreparedRoute[] | undefined): string | undefined {
    if (routes === undefined) {
      return undefined;
    }
    try {
      return matchPreparedPath(routes, location.pathname);
    } catch {
      return '/other';
    }
  }

  function enqueue(element: string, path: string | undefined): void {
    if (queue.length >= MAX_QUEUE_SIZE) {
      // The queue drops the oldest entry when it is full (design decision D24).
      queue.shift();
    }
    const entry: QueueEntry = { element, queuedAt: monotonicNow() };
    if (path !== undefined) {
      entry.path = path;
    }
    queue.push(entry);
    if (queue.length === 1) {
      scheduleFlush();
    }
  }

  function scheduleFlush(): void {
    if (timerId !== null) {
      // One timer at a time. A later click of the same batch joins the queue.
      return;
    }
    timerId = setTimeout(() => {
      timerId = null;
      try {
        flush();
      } catch {
        // The tracker gives no error to the page. It drops the batch instead.
      }
    }, flushIntervalMs);
  }

  function flush(): void {
    if (queue.length === 0) {
      return;
    }
    // The queue empties before the session id call, so a throw there drops
    // only this one batch. A later click still starts a new timer.
    const entries = queue;
    queue = [];
    const id = getOrCreateSessionId();
    for (let offset = 0; offset < entries.length; offset += MAX_BATCH_SIZE) {
      void sendBatch(id, entries.slice(offset, offset + MAX_BATCH_SIZE));
    }
  }

  function sendBatch(id: string, batch: QueueEntry[]): Promise<void> {
    const now = monotonicNow();
    const clicks = batch.map((entry) => {
      const click: { element: string; ageMs: number; path?: string } = {
        element: entry.element,
        ageMs: Math.max(0, Math.round(now - entry.queuedAt)),
      };
      if (entry.path !== undefined) {
        click.path = entry.path;
      }
      return click;
    });
    const headers: Record<string, string> = {};
    if (getExtraHeaders) {
      try {
        Object.assign(headers, getExtraHeaders());
      } catch {
        // The tracker sends the batch with the default headers only.
      }
    }
    // The tracker owns the content type. It drops each spelling of the
    // header name first, so a header object cannot carry the name two
    // times with a different case (contract rule C12).
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === 'content-type') {
        delete headers[name];
      }
    }
    headers['Content-Type'] = 'application/json';
    return fetch(endpoint, {
      method: 'POST',
      credentials,
      headers,
      referrerPolicy: 'no-referrer',
      body: JSON.stringify({ sessionId: id, clicks }),
    })
      .then(() => undefined)
      .catch(() => {
        // Issue #36 adds the retry rule for a network error, a 5xx, and a 429.
      });
  }

  function getOrCreateSessionId(): string {
    if (sessionId !== null) {
      return sessionId;
    }
    const stored = readStoredSessionId();
    const id = stored ?? generateUuid();
    sessionId = id;
    writeStoredSessionId(id);
    return id;
  }

  function readStoredSessionId(): string | null {
    try {
      return window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      // A private window or a blocked store keeps the id in memory only.
      return null;
    }
  }

  function writeStoredSessionId(id: string): void {
    try {
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, id);
    } catch {
      // A private window or a blocked store keeps the id in memory only.
    }
  }

  function removeStoredSessionId(): void {
    try {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      // A blocked store has no key to remove.
    }
  }

  return { start, stop };
}

/**
 * Checks and splits the `routes` option one time. Gives `undefined` for a
 * missing option, an empty list, and a list whose entries are all bad:
 * each of the three cases sends no `path` field (contract rule C42 gives
 * the server the same rule for a missing route pattern list). A bad entry
 * writes one console warning that names its index in the given list.
 */
function prepareRoutesOption(routes: TrackerOptions['routes']): readonly PreparedRoute[] | undefined {
  if (routes === undefined) {
    return undefined;
  }
  const { routes: prepared, warnings } = prepareRoutes(routes);
  for (const warning of warnings) {
    console.warn(warning);
  }
  if (prepared.length === 0) {
    console.warn('octometer: the routes option holds no valid pattern. The tracker sends no path.');
    return undefined;
  }
  return prepared;
}

/** A monotonic clock. It ignores a change of the system clock (MAJOR 1 of the review). */
function monotonicNow(): number {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  return Date.now();
}

function findOctoElement(path: readonly EventTarget[]): Element | null {
  for (const node of path) {
    if (node instanceof Element && node.hasAttribute('data-octo')) {
      return node;
    }
  }
  return null;
}

function isDisabled(element: Element): boolean {
  try {
    if (typeof element.matches === 'function') {
      // This also covers a control inside a disabled <fieldset>.
      return element.matches(':disabled');
    }
  } catch {
    // An old engine can throw on this selector; fall back to the IDL property.
  }
  return 'disabled' in element && (element as unknown as { disabled: boolean }).disabled === true;
}

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto === 'undefined' || typeof crypto.getRandomValues !== 'function') {
    throw new Error('The tracker needs the Web Crypto API to create a session id.');
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  // Set the UUID version (4) and variant bits (RFC 4122).
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0'));
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join(''),
  ].join('-');
}
