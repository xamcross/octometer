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
}

/** The tracker instance. Call `start()` after consent, and `stop()` to end tracking. */
export interface Tracker {
  /** Starts the click listener. A second call has no extra effect. */
  start(): void;
  /**
   * Empties the queue, stops the pending timer, removes the click listener,
   * and removes the session id from `sessionStorage`.
   */
  stop(): void;
}

const SESSION_STORAGE_KEY = 'octo_session_id';
/** The `element` rule of the contract, rule C4: 1 to 100 characters, this pattern. */
const ELEMENT_PATTERN = /^[A-Za-z0-9_.:-]{1,100}$/;
/** The queue holds a maximum of 200 entries (design decision D24). */
const MAX_QUEUE_SIZE = 200;
/** One request holds a maximum of 50 clicks (contract rule C17). */
const MAX_BATCH_SIZE = 50;
const DEFAULT_FLUSH_INTERVAL_MS = 5000;
const DEFAULT_CREDENTIALS: RequestCredentials = 'same-origin';

interface QueueEntry {
  element: string;
  queuedAt: number;
}

/** Creates one tracker instance. The tracker holds its own queue and its own session id. */
export function createTracker(options: TrackerOptions): Tracker {
  const endpoint = options.endpoint;
  const credentials = options.credentials ?? DEFAULT_CREDENTIALS;
  const getExtraHeaders = options.headers;
  const flushIntervalMs = options.flushIntervalMs ?? DEFAULT_FLUSH_INTERVAL_MS;

  let queue: QueueEntry[] = [];
  let timerId: ReturnType<typeof setTimeout> | null = null;
  let sessionId: string | null = null;
  let started = false;
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
    removeStoredSessionId();
  }

  function handleClick(event: Event): void {
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    const target = findOctoElement(path);
    if (target === null || isDisabled(target)) {
      return;
    }
    const name = target.getAttribute('data-octo');
    if (name === null || !ELEMENT_PATTERN.test(name)) {
      return;
    }
    enqueue(name);
  }

  function enqueue(element: string): void {
    if (queue.length >= MAX_QUEUE_SIZE) {
      // The queue drops the oldest entry once it is full (design decision D24).
      queue.shift();
    }
    queue.push({ element, queuedAt: Date.now() });
    if (queue.length === 1) {
      scheduleFlush();
    }
  }

  function scheduleFlush(): void {
    timerId = setTimeout(() => {
      timerId = null;
      flush();
    }, flushIntervalMs);
  }

  function flush(): void {
    if (queue.length === 0) {
      return;
    }
    const id = getOrCreateSessionId();
    const entries = queue;
    queue = [];
    if (id === null) {
      // The tracker drops the batch when it has no session id at all.
      return;
    }
    for (let offset = 0; offset < entries.length; offset += MAX_BATCH_SIZE) {
      sendBatch(id, entries.slice(offset, offset + MAX_BATCH_SIZE));
    }
  }

  function sendBatch(id: string, batch: QueueEntry[]): void {
    const now = Date.now();
    const clicks = batch.map((entry) => ({
      element: entry.element,
      ageMs: Math.max(0, Math.round(now - entry.queuedAt)),
    }));
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (getExtraHeaders) {
      Object.assign(headers, getExtraHeaders());
    }
    void fetch(endpoint, {
      method: 'POST',
      credentials,
      headers,
      body: JSON.stringify({ sessionId: id, clicks }),
    }).catch(() => {
      // A later issue adds the retry rule for a network error, a 5xx, and a 429.
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

function findOctoElement(path: readonly EventTarget[]): Element | null {
  for (const node of path) {
    if (node instanceof Element && node.hasAttribute('data-octo')) {
      return node;
    }
  }
  return null;
}

function isDisabled(element: Element): boolean {
  return 'disabled' in element && (element as unknown as { disabled: boolean }).disabled === true;
}

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
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
