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
import { splitIntoRequestBatches, type ClickPayload } from './batch.js';

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
   * holds `path`. One bad pattern, or a value that is not an array, gives
   * the same result as a missing option: no click entry holds `path`.
   */
  routes?: readonly string[];
  /**
   * Turns off the `navigator.webdriver` filter (design decision D41). The
   * default is `false`. A browser sets `navigator.webdriver` to `true`
   * for an automated tool. Set this option to `true` only for an
   * end-to-end test of the app itself.
   */
  ignoreWebdriver?: boolean;
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
 * case. A click on a `data-octo` value with this prefix records no
 * click. This has no exception. Issue #107 sends `octo:session-start`
 * through its own call, never through a click on a page element.
 */
const RESERVED_ELEMENT_PREFIX = /^octo:/i;
/** The element value of the session start (contract rule C38, design decision D41). */
const SESSION_START_ELEMENT = 'octo:session-start';
/**
 * The queue holds a maximum of 200 entries (design decision D24).
 *
 * A full queue splits into `MAX_QUEUE_SIZE / MAX_BATCH_ENTRIES` requests
 * at most (`batch.ts`). That count, times `MAX_BODY_BYTES`, must stay
 * below the 64 KB keepalive quota of one page:
 *
 * MAX_QUEUE_SIZE / MAX_BATCH_ENTRIES * MAX_BODY_BYTES < 65536
 */
const MAX_QUEUE_SIZE = 200;
const DEFAULT_FLUSH_INTERVAL_MS = 5000;
const DEFAULT_CREDENTIALS: RequestCredentials = 'same-origin';
/**
 * The retry rule of issue #36. A batch of the timer flush goes out one
 * more time after a network error, a 5xx response, or a 429 response.
 * The timer flush runs while the page stays visible.
 */
const TIMER_RETRY_COUNT = 1;
/**
 * A batch of the pagehide flush, or of the visibilitychange flush, goes
 * out one time only, with no retry (maintainer decision of 2026-09-22).
 * A browser can freeze the page after the hidden state and drop the
 * connection. A retry there gives the largest risk of a double count.
 */
const LIFECYCLE_RETRY_COUNT = 0;
/** The wait before the first retry, after a network error or a 5xx response. */
const RETRY_DELAY_MS = 500;
/** The extra random wait, from 0 up to this value, added to `RETRY_DELAY_MS`. */
const RETRY_JITTER_MS = 500;
/**
 * The wait before the retry after a 429 response. A 429 response states a
 * rate limit of a fixed 60-second window (design decisions D20 and D43).
 * The tracker waits longer, so the retry falls in a later window.
 */
const RETRY_DELAY_429_MS = 2000;
/** The extra random wait, from 0 up to this value, added to `RETRY_DELAY_429_MS`. */
const RETRY_JITTER_429_MS = 2000;
/** The `sessionId` rule of the contract, rule C5: a canonical 36-character UUID. */
const SESSION_ID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * The fallback session id, for a blocked `sessionStorage` (issue #107,
 * step 4). It lives at the module level, outside one tracker instance.
 * A `stop()` call and a later `start()` call thus still reuse it. The
 * session start then goes out one time for the life of the document. A
 * working `sessionStorage` never reads or writes this variable.
 */
let moduleFallbackSessionId: string | null = null;

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
  // The tracker checks the routes option one time here, not at each click.
  // One bad entry stops the whole list, and the tracker then sends no
  // path field. Rule C42 of the contract gives the server the same rule.
  const preparedRoutes = prepareRoutesOption(options.routes);
  const ignoreWebdriver = options.ignoreWebdriver === true;

  let queue: QueueEntry[] = [];
  let timerId: ReturnType<typeof setTimeout> | null = null;
  let sessionId: string | null = null;
  let started = false;
  let everStarted = false;
  let droppedOctoPrefix = false;
  let clickListener: ((event: Event) => void) | null = null;
  let pageHideListener: (() => void) | null = null;
  let visibilityChangeListener: (() => void) | null = null;
  /** The prerender-end listener, held for the session start (issue #107). */
  let pendingPrerenderingListener: (() => void) | null = null;
  /** The visibility listener, held for the session start (issue #107). */
  let pendingVisibilityStartListener: (() => void) | null = null;
  /**
   * The run token of the tracker (MAJOR 1 of the TypeScript review). Each
   * `start()` call and each `stop()` call raises this number by one. A
   * pending retry checks its own token against this number, so a retry
   * never fires after a later `stop()`.
   */
  let runId = 0;

  function start(): void {
    if (started) {
      return;
    }
    if (typeof document === 'undefined') {
      // The tracker takes no action during server-side rendering.
      return;
    }
    if (isBlockedByWebdriver()) {
      // Design decision D41: the tracker sends nothing, no click and no
      // session start, while navigator.webdriver is true. The option
      // ignoreWebdriver turns this filter off for an end-to-end test.
      return;
    }
    started = true;
    runId += 1;
    everStarted = true;
    // Issue #107, step 1: the tracker resolves the session id here, in
    // start(), not at the first flush. This early resolve tells a new
    // session from an existing one. Only a new session sends the session
    // start, at once, before any click.
    let isNewSession = false;
    try {
      isNewSession = resolveSessionId().isNew;
    } catch {
      // generateUuid() found no Web Crypto API. sessionId stays null.
      // This start() call sends no session start. A later flush() retries
      // through getOrCreateSessionId().
    }
    clickListener = (event: Event) => handleClick(event);
    document.addEventListener('click', clickListener, true);
    // Issue #36, steps 1 and 2: the tracker sends the queue before the
    // browser hides or unloads the page.
    pageHideListener = () => safeFlush({ keepalive: true, retryCount: LIFECYCLE_RETRY_COUNT });
    visibilityChangeListener = () => {
      if (document.visibilityState === 'hidden') {
        safeFlush({ keepalive: true, retryCount: LIFECYCLE_RETRY_COUNT });
      }
    };
    if (typeof window !== 'undefined') {
      window.addEventListener('pagehide', pageHideListener);
    }
    document.addEventListener('visibilitychange', visibilityChangeListener);
    if (isNewSession) {
      trySendSessionStart(runId);
    }
  }

  function stop(): void {
    started = false;
    runId += 1;
    queue = [];
    if (timerId !== null) {
      clearTimeout(timerId);
      timerId = null;
    }
    if (clickListener !== null && typeof document !== 'undefined') {
      document.removeEventListener('click', clickListener, true);
    }
    clickListener = null;
    if (pageHideListener !== null && typeof window !== 'undefined') {
      window.removeEventListener('pagehide', pageHideListener);
    }
    pageHideListener = null;
    if (visibilityChangeListener !== null && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', visibilityChangeListener);
    }
    if (pendingPrerenderingListener !== null && typeof document !== 'undefined') {
      document.removeEventListener('prerenderingchange', pendingPrerenderingListener);
      pendingPrerenderingListener = null;
    }
    if (pendingVisibilityStartListener !== null && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', pendingVisibilityStartListener);
      pendingVisibilityStartListener = null;
    }
    visibilityChangeListener = null;
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
    // page at the time of the later flush. An app can navigate between
    // the two, because the tracker holds the click in its queue.
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

  /**
   * Checks `navigator.webdriver` (design decision D41). A browser sets
   * this property to `true` for an automated tool. The option
   * ignoreWebdriver turns this filter off, for an end-to-end test of the
   * app itself.
   */
  function isBlockedByWebdriver(): boolean {
    return !ignoreWebdriver && typeof navigator !== 'undefined' && navigator.webdriver === true;
  }

  /**
   * Sends the session start at once, for a visible, non-prerendering
   * document (issue #107, step 5). For a prerendering or a hidden
   * document, it waits for the right event, one time. `token` is the run
   * token of the `start()` call that began this wait. A later `stop()`
   * call raises `runId`. This function then sends nothing, also from a
   * pending event.
   */
  function trySendSessionStart(token: number): void {
    if (token !== runId) {
      return;
    }
    if (typeof document !== 'undefined' && isDocumentPrerendering()) {
      const listener = (): void => {
        pendingPrerenderingListener = null;
        trySendSessionStart(token);
      };
      pendingPrerenderingListener = listener;
      document.addEventListener('prerenderingchange', listener, { once: true });
      return;
    }
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
      const listener = (): void => {
        pendingVisibilityStartListener = null;
        trySendSessionStart(token);
      };
      pendingVisibilityStartListener = listener;
      document.addEventListener('visibilitychange', listener, { once: true });
      return;
    }
    sendSessionStart(token);
  }

  /**
   * Sends the one entry `octo:session-start`, in its own request, with
   * `ageMs: 0` and the `path` of the page (contract rule C38, issue
   * #107). The request uses `keepalive: true`, so a visitor who leaves
   * the page at once still delivers it. It follows the retry rule of the
   * timer flush, not the lifecycle rule (issue #36): one retry, after a
   * wait.
   */
  function sendSessionStart(token: number): void {
    if (token !== runId || sessionId === null) {
      // A later stop() ran first, or generateUuid() found no session id.
      return;
    }
    const path = computePath(preparedRoutes);
    const click: { element: string; ageMs: number; path?: string } = {
      element: SESSION_START_ELEMENT,
      ageMs: 0,
    };
    if (path !== undefined) {
      click.path = path;
    }
    try {
      void sendBatch(sessionId, [click], true, TIMER_RETRY_COUNT, token);
    } catch {
      // The tracker gives no error to the page. It drops the entry instead.
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
    const token = runId;
    timerId = setTimeout(() => {
      timerId = null;
      if (token !== runId) {
        // MAJOR 1 of the TypeScript review: stop() ran first. Send nothing.
        return;
      }
      safeFlush({ keepalive: false, retryCount: TIMER_RETRY_COUNT });
    }, flushIntervalMs);
  }

  interface FlushOptions {
    /** True on the pagehide flush and the visibilitychange flush (design decision D24). */
    keepalive: boolean;
    /** The count of extra sends that the retry rule of issue #36 allows for this flush. */
    retryCount: number;
  }

  /** Runs `flush()` inside a try/catch, so a throw of it never reaches the page. */
  function safeFlush(options: FlushOptions): void {
    try {
      flush(options);
    } catch {
      // The tracker gives no error to the page. It drops the batch instead.
    }
  }

  function flush(options: FlushOptions): void {
    if (queue.length === 0) {
      return;
    }
    // The queue empties before the session id call, so a throw there drops
    // only this one batch. A later click still starts a new timer.
    const entries = queue;
    queue = [];
    if (timerId !== null) {
      // A lifecycle flush can run while a timer is still pending (MINOR 2
      // of the reliability review). Clear it, so a later click starts its
      // own timer instead of an early, empty-queue flush.
      clearTimeout(timerId);
      timerId = null;
    }
    const id = getOrCreateSessionId();
    const now = monotonicNow();
    const clicks = entries.map((entry) => toClickPayload(entry, now));
    // Issue #36, step 6: the tracker measures the encoded body as UTF-8
    // bytes. It splits a batch whose body passes 15 000 bytes, also below
    // 50 entries (contract rule C17).
    const batches = splitIntoRequestBatches(id, clicks);
    const token = runId;
    for (const batch of batches) {
      try {
        // MINOR 4 of the reliability review: one batch must not stop the
        // send of a later batch, also when fetch throws at the call.
        void sendBatch(id, batch, options.keepalive, options.retryCount, token);
      } catch {
        // The tracker gives no error to the page. It drops this batch only.
      }
    }
  }

  function toClickPayload(entry: QueueEntry, now: number): ClickPayload {
    const click: { element: string; ageMs: number; path?: string } = {
      element: entry.element,
      ageMs: Math.max(0, Math.round(now - entry.queuedAt)),
    };
    if (entry.path !== undefined) {
      click.path = entry.path;
    }
    return click;
  }

  /**
   * Sends one request. The retry rule of issue #36: it sends the same
   * batch again, up to `retriesLeft` more times. It waits first
   * (`retryDelayMs`), after a network error, a 5xx response, or a 429
   * response. It drops the batch after each other 4xx response.
   *
   * `token` is the run token of the flush that built this batch
   * (MAJOR 1 of the TypeScript review). A retry runs only when `token`
   * still equals the current run token, so no retry fires after a later
   * `stop()`.
   *
   * A maintainer comment on issue #36 states a limit of this rule. The
   * MongoDB store of the app writes a batch without a transaction. A
   * retry after a mid-batch failure can write part of a batch two times.
   * See the README section "The retry rule" for the detail.
   */
  function sendBatch(
    id: string,
    batch: ClickPayload[],
    keepalive: boolean,
    retriesLeft: number,
    token: number,
  ): Promise<void> {
    return fetch(endpoint, {
      method: 'POST',
      credentials,
      headers: buildHeaders(),
      referrerPolicy: 'no-referrer',
      keepalive,
      body: JSON.stringify({ sessionId: id, clicks: batch }),
    })
      .then((response) => {
        if (response.ok) {
          return;
        }
        const isRetryableStatus = response.status === 429 || response.status >= 500;
        if (retriesLeft > 0 && isRetryableStatus && token === runId) {
          return waitThenRetry(id, batch, keepalive, retriesLeft, token, response.status);
        }
        // Each other 4xx response drops the batch. No error reaches the page.
      })
      .catch(() => {
        if (retriesLeft > 0 && token === runId) {
          return waitThenRetry(id, batch, keepalive, retriesLeft, token, undefined).catch(() => {
            // A second network error drops the batch too.
          });
        }
        // The tracker gives no error to the page. It drops the batch instead.
      });
  }

  /**
   * Waits the retry delay (`retryDelayMs`), then sends `batch` again.
   *
   * It checks the run token a second time, after the wait, because
   * `stop()` can run during the wait (MAJOR 1 of the TypeScript review).
   */
  function waitThenRetry(
    id: string,
    batch: ClickPayload[],
    keepalive: boolean,
    retriesLeft: number,
    token: number,
    status: number | undefined,
  ): Promise<void> {
    return new Promise((resolve) => {
      setTimeout(() => {
        if (token !== runId) {
          resolve();
          return;
        }
        resolve(sendBatch(id, batch, keepalive, retriesLeft - 1, token));
      }, retryDelayMs(status));
    });
  }

  function buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = {};
    if (getExtraHeaders) {
      try {
        Object.assign(headers, getExtraHeaders());
      } catch {
        // The tracker sends the batch with the default headers only.
      }
    }
    // The tracker owns the content type. It drops each spelling of the
    // header name first. A header object then cannot carry the name two
    // times, with a different case (contract rule C12).
    for (const name of Object.keys(headers)) {
      if (name.toLowerCase() === 'content-type') {
        delete headers[name];
      }
    }
    headers['Content-Type'] = 'application/json';
    return headers;
  }

  function getOrCreateSessionId(): string {
    if (sessionId !== null) {
      return sessionId;
    }
    return resolveSessionId().id;
  }

  /**
   * Reads the stored session id, or creates a new one (issue #107). It
   * sets the closure session id either way. A stored value that is
   * absent, or that fails the UUID rule of rule C5, counts as new. The
   * caller then sends the session start. `start()` calls this function
   * once, to send the session start at once. `getOrCreateSessionId()`
   * calls it again only when `start()` found no session id, for example
   * with no Web Crypto API yet.
   */
  function resolveSessionId(): { id: string; isNew: boolean } {
    const stored = readStoredSessionId();
    // MINOR 5 of the TypeScript review: a stored value must pass the C5
    // rule of the contract, a UUID, before use. A wrong value gets a new
    // id, so the ingest route never rejects the whole batch for it.
    const isValidStored = stored !== null && SESSION_ID_PATTERN.test(stored);
    const id = isValidStored ? (stored as string) : generateUuid();
    sessionId = id;
    if (!isValidStored) {
      // A reused, valid id needs no rewrite: the value does not change.
      writeStoredSessionId(id);
    }
    return { id, isNew: !isValidStored };
  }

  function readStoredSessionId(): string | null {
    try {
      return window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    } catch {
      // A private window or a blocked store keeps the id in a module
      // variable, for the life of the document (issue #107, step 4).
      return moduleFallbackSessionId;
    }
  }

  function writeStoredSessionId(id: string): void {
    try {
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, id);
    } catch {
      // A private window or a blocked store keeps the id in a module
      // variable, for the life of the document (issue #107, step 4).
      moduleFallbackSessionId = id;
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
 * Checks and splits the `routes` option one time. It fails closed: one
 * bad entry counts as no route list. A dropped entry would move a later
 * pattern into its place, and change the match order. Contract rule C42
 * gives the server the same rule.
 *
 * This function gives `undefined` in four cases. The option is missing.
 * The option is not an array. The option is an empty array. The option
 * holds one bad entry or more. Each case sends no `path` field. A value
 * that is not an array writes one console warning. A list with one bad
 * entry or more writes exactly one console warning, and it names the
 * index of each bad entry. No warning holds the text of a pattern.
 */
function prepareRoutesOption(routes: unknown): readonly PreparedRoute[] | undefined {
  if (routes === undefined) {
    return undefined;
  }
  if (!Array.isArray(routes)) {
    console.warn('octometer: the routes option must be an array. The tracker sends no path.');
    return undefined;
  }
  const { routes: prepared, invalidIndexes } = prepareRoutes(routes);
  if (invalidIndexes.length > 0) {
    // One warning for the whole list, not one warning for each bad entry
    // (issue #140). One bad entry stops the whole list, so the match
    // order stays fixed.
    console.warn(
      `octometer: the routes option holds an invalid entry at ${formatIndexList(invalidIndexes)}. ` +
        'The tracker sends no path field.',
    );
    return undefined;
  }
  if (prepared.length === 0) {
    console.warn('octometer: the routes option holds no valid pattern. The tracker sends no path.');
    return undefined;
  }
  return prepared;
}

/**
 * Builds the index list text of the routes warning, for example
 * `index 0`, `index 0 and index 2`, or `index 0, index 2, and index 4`.
 * The text never holds a pattern, only a plain index number.
 */
function formatIndexList(indexes: readonly number[]): string {
  const labels = indexes.map((index) => `index ${index}`);
  if (labels.length === 1) {
    return labels[0] as string;
  }
  if (labels.length === 2) {
    return `${labels[0]} and ${labels[1]}`;
  }
  const lastLabel = labels[labels.length - 1];
  return `${labels.slice(0, -1).join(', ')}, and ${lastLabel}`;
}

/**
 * The wait, in milliseconds, before a retry. `status` is the response
 * status, or `undefined` for a network error. The wait is longer after a
 * 429 response: the rate limit uses a fixed 60-second window (design
 * decisions D20 and D43, reliability review MAJOR 1). The random part
 * spreads the retries of a group of tabs across the window.
 */
function retryDelayMs(status: number | undefined): number {
  if (status === 429) {
    return RETRY_DELAY_429_MS + Math.random() * RETRY_JITTER_429_MS;
  }
  return RETRY_DELAY_MS + Math.random() * RETRY_JITTER_MS;
}

/**
 * Reads `document.prerendering` (design decision D41). The TypeScript DOM
 * library has no type for this property yet. This function reads it
 * through a narrow cast. It gives `false` for a browser with no such
 * property. Call this only after a check of `typeof document`.
 */
function isDocumentPrerendering(): boolean {
  return (document as unknown as { prerendering?: boolean }).prerendering === true;
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
