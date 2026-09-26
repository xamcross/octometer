import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTracker, type Tracker } from './index.js';
import * as pathMatch from './path-match.js';
import * as batchModule from './batch.js';

const ENDPOINT = 'https://app.example/api/octometer/v1/clicks';
const SESSION_STORAGE_KEY = 'octo_session_id';
/**
 * A stored session id for most tests. Most tests run with this id already
 * in `sessionStorage`. `start()` then finds an existing session. It sends
 * no `octo:session-start` entry (issue #107). A test of the session start
 * clears the store first, so its own `start()` call finds a new session.
 */
const EXISTING_SESSION_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

/**
 * Sets `document.visibilityState` and `document.hidden` to fixed values,
 * for one test. jsdom shares one `document` between test files, so a test
 * that calls this must restore the two properties in its own cleanup
 * (rule: restore each global that a test changes).
 */
function setVisibilityState(value: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => value,
  });
  Object.defineProperty(document, 'hidden', {
    configurable: true,
    get: () => value === 'hidden',
  });
}

function restoreVisibilityState(): void {
  delete (document as { visibilityState?: unknown }).visibilityState;
  delete (document as { hidden?: unknown }).hidden;
}

/** Sets `document.prerendering`, for one test. See `setVisibilityState`. */
function setPrerendering(value: boolean): void {
  Object.defineProperty(document, 'prerendering', {
    configurable: true,
    get: () => value,
  });
}

function restorePrerendering(): void {
  delete (document as { prerendering?: unknown }).prerendering;
}

/** Sets `navigator.webdriver`, for one test. See `setVisibilityState`. */
function setWebdriver(value: boolean): void {
  Object.defineProperty(window.navigator, 'webdriver', {
    configurable: true,
    get: () => value,
  });
}

function restoreWebdriver(): void {
  delete (window.navigator as { webdriver?: unknown }).webdriver;
}

/**
 * Sets `document.referrer`, for one test (issue #108). See
 * `setVisibilityState` for the restore rule.
 */
function setReferrer(value: string): void {
  Object.defineProperty(document, 'referrer', {
    configurable: true,
    get: () => value,
  });
}

function restoreReferrer(): void {
  delete (document as { referrer?: unknown }).referrer;
}

interface FetchCall {
  url: string;
  init: RequestInit;
  body: {
    sessionId: string;
    clicks: Array<{ element: string; ageMs: number; path?: string; referrerHost?: string }>;
  };
}

/** Reads an array entry, and throws a clear error for a missing one. */
function at<T>(array: readonly T[], index: number): T {
  const value = array[index];
  if (value === undefined) {
    throw new Error(`No entry at index ${index}.`);
  }
  return value;
}

function parseCalls(fetchMock: ReturnType<typeof vi.fn>): FetchCall[] {
  return fetchMock.mock.calls.map((call) => {
    const [url, init] = call as [string, RequestInit];
    return {
      url,
      init,
      body: JSON.parse(String(init.body)) as FetchCall['body'],
    };
  });
}

function clickElement(element: Element): void {
  element.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }));
}

/** Gives each fetch call whose body holds a click other than the session start. */
function clickBatchCalls(calls: readonly FetchCall[]): FetchCall[] {
  return calls.filter((call) => call.body.clicks[0]?.element !== 'octo:session-start');
}

describe('createTracker', () => {
  let tracker: Tracker | null;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '';
    window.sessionStorage.clear();
    // Most tests run with an existing, valid session id already stored.
    // start() then finds no new session. It sends no octo:session-start
    // entry (issue #107). A test of the session start clears the store
    // again.
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, EXISTING_SESSION_ID);
    fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 });
    vi.stubGlobal('fetch', fetchMock);
    tracker = null;
  });

  afterEach(() => {
    tracker?.stop();
    vi.unstubAllGlobals();
    vi.useRealTimers();
    vi.restoreAllMocks();
    restoreVisibilityState();
    restorePrerendering();
    restoreWebdriver();
    restoreReferrer();
  });

  it('records a click on a child of a data-octo element', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    const child = document.createElement('button');
    host.appendChild(child);
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(child);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(calls).toHaveLength(1);
    expect(at(calls, 0).body.clicks).toEqual([{ element: 'save', ageMs: 5000 }]);
  });

  it('records no entry for a click without the data-octo attribute', () => {
    const plain = document.createElement('button');
    document.body.appendChild(plain);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(plain);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('records no click before start()', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    clickElement(host);
    tracker.start();
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('takes no action during server-side rendering, where document is absent', () => {
    const realDocument = document;
    vi.stubGlobal('document', undefined);

    expect(() => {
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
    }).not.toThrow();

    vi.stubGlobal('document', realDocument);
  });

  it('finds the data-octo element through an open shadow root', () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    const shadowRoot = host.attachShadow({ mode: 'open' });
    const inner = document.createElement('span');
    inner.setAttribute('data-octo', 'shadow.save');
    shadowRoot.appendChild(inner);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(inner);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(at(calls, 0).body.clicks).toEqual([{ element: 'shadow.save', ageMs: 5000 }]);
  });

  it('captures the click before a bubble-phase listener can stop its propagation', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);
    host.addEventListener('click', (event) => event.stopPropagation());

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(parseCalls(fetchMock)).toHaveLength(1);
  });

  it('skips a disabled element', () => {
    const button = document.createElement('button');
    button.setAttribute('data-octo', 'save');
    button.disabled = true;
    document.body.appendChild(button);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(button);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('skips a control inside a disabled fieldset', () => {
    const fieldset = document.createElement('fieldset');
    fieldset.disabled = true;
    const button = document.createElement('button');
    button.setAttribute('data-octo', 'save');
    fieldset.appendChild(button);
    document.body.appendChild(fieldset);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(button);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('skips a data-octo value that breaks the element pattern of the contract', () => {
    const badPattern = document.createElement('div');
    badPattern.setAttribute('data-octo', 'checkout save!');
    document.body.appendChild(badPattern);
    const badLength = document.createElement('div');
    badLength.setAttribute('data-octo', 'a'.repeat(101));
    document.body.appendChild(badLength);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(badPattern);
    clickElement(badLength);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('stop() empties the queue, so a restart sends only the new click', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    tracker.stop();
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    // stop() removes the stored session id. The restart then begins a new
    // session. It sends one extra octo:session-start entry (issue #107).
    // The click queue itself still holds only the new click.
    const calls = clickBatchCalls(parseCalls(fetchMock));
    expect(calls).toHaveLength(1);
    expect(at(calls, 0).body.clicks).toHaveLength(1);
  });

  it('stop() clears the pending flush timer, so it never fires', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    fetchMock.mockClear();
    tracker.stop();

    // Reliability review MINOR 3 and TypeScript review MINOR 1: a direct
    // check of the timer count pins the clearTimeout() call of stop().
    // Without it, jsdom still holds one pending timer here.
    expect(vi.getTimerCount()).toBe(0);

    // stop() also removes the stored session id (a real write). A raw
    // jsdom timer count then no longer isolates the flush timer alone
    // (issue #107). This test also checks the flush timer through its
    // effect. It never fires after stop().
    vi.advanceTimersByTime(5000);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('stop() removes the sessionStorage key that the flush wrote', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);
    expect(window.sessionStorage.getItem('octo_session_id')).not.toBeNull();

    tracker.stop();
    expect(window.sessionStorage.getItem('octo_session_id')).toBeNull();
  });

  it('does not throw when stop() runs before start()', () => {
    tracker = createTracker({ endpoint: ENDPOINT });
    expect(() => tracker?.stop()).not.toThrow();
  });

  it('does not throw when stop() runs two times in a row', () => {
    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    tracker.stop();
    expect(() => tracker?.stop()).not.toThrow();
  });

  it('does not touch sessionStorage when stop() runs before start()', () => {
    const removeItemSpy = vi.spyOn(window.sessionStorage.__proto__, 'removeItem');
    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.stop();

    expect(removeItemSpy).not.toHaveBeenCalled();
  });

  it('stop() removes a session id of an earlier page load, also before a flush', () => {
    window.sessionStorage.setItem('octo_session_id', 'an-older-id');
    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    tracker.stop();

    expect(window.sessionStorage.getItem('octo_session_id')).toBeNull();
  });

  it('creates a new sessionId after stop() and a later start()', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(1000);
    const firstId = at(clickBatchCalls(parseCalls(fetchMock)), 0).body.sessionId;

    tracker.stop();
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(1000);
    // stop() begins a new session at the next start() (issue #107), thus
    // one extra call carries the octo:session-start entry of that session.
    const secondId = at(clickBatchCalls(parseCalls(fetchMock)), 1).body.sessionId;

    expect(secondId).not.toBe(firstId);
  });

  it('removes the click listener on stop(), so a later click records no entry', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    tracker.stop();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('gives requests with a maximum of 50 clicks each for 120 clicks', () => {
    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    for (let i = 0; i < 120; i += 1) {
      const host = document.createElement('div');
      host.setAttribute('data-octo', `e${i}`);
      document.body.appendChild(host);
      clickElement(host);
    }
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(calls).toHaveLength(3);
    expect(at(calls, 0).body.clicks).toHaveLength(50);
    expect(at(calls, 1).body.clicks).toHaveLength(50);
    expect(at(calls, 2).body.clicks).toHaveLength(20);
  });

  it('drops the oldest entry when the queue holds 200 entries', () => {
    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
    tracker.start();
    for (let i = 0; i < 201; i += 1) {
      const host = document.createElement('div');
      host.setAttribute('data-octo', `e${i}`);
      document.body.appendChild(host);
      clickElement(host);
    }
    vi.advanceTimersByTime(100000);

    const calls = parseCalls(fetchMock);
    const allElements = calls.flatMap((call) => call.body.clicks.map((click) => click.element));
    expect(allElements).toHaveLength(200);
    expect(allElements).not.toContain('e0');
    expect(allElements).toContain('e1');
    expect(allElements).toContain('e200');
  });

  it('sends a body that holds only sessionId and clicks, each click only element and ageMs', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    const body = at(calls, 0).body;
    expect(Object.keys(body).sort()).toEqual(['clicks', 'sessionId']);
    expect(Object.keys(at(body.clicks, 0)).sort()).toEqual(['ageMs', 'element']);
    expect(typeof body.sessionId).toBe('string');
    expect(body.sessionId.length).toBeGreaterThan(0);
  });

  it('never sends a userId field, also when the caller passes one as an option header', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(at(calls, 0).body).not.toHaveProperty('userId');
  });

  it('sends the Content-Type header, the credentials option, and the caller headers', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      credentials: 'include',
      headers: () => ({ 'X-Octo-App': 'demo' }),
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const call = at(parseCalls(fetchMock), 0);
    expect(call.init.method).toBe('POST');
    expect(call.init.credentials).toBe('include');
    expect((call.init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
    expect((call.init.headers as Record<string, string>)['X-Octo-App']).toBe('demo');
  });

  it('does not let the caller headers() replace the Content-Type header', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      headers: () => ({ 'Content-Type': 'text/plain' }),
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const call = at(parseCalls(fetchMock), 0);
    expect((call.init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
  });

  it('drops each spelling of the content-type header name before it sets its own', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      headers: () => ({ 'content-type': 'text/plain' }),
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const call = at(parseCalls(fetchMock), 0);
    // A real Headers object merges two spellings of one name into one field.
    const headers = new Headers(call.init.headers as Record<string, string>);
    const names = Array.from(headers.keys()).filter((name) => name.toLowerCase() === 'content-type');
    expect(names).toHaveLength(1);
    expect(headers.get('content-type')).toBe('application/json');
  });

  it('sends the batch with the default headers when the headers() callback throws', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      headers: () => {
        throw new Error('a broken CSRF read');
      },
    });
    tracker.start();
    clickElement(host);

    expect(() => vi.advanceTimersByTime(5000)).not.toThrow();
    const call = at(parseCalls(fetchMock), 0);
    expect((call.init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
  });

  it('uses the default credentials mode of same-origin', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(at(parseCalls(fetchMock), 0).init.credentials).toBe('same-origin');
  });

  it('sets referrerPolicy to no-referrer, so the browser sends no Referer header', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(at(parseCalls(fetchMock), 0).init.referrerPolicy).toBe('no-referrer');
  });

  it('does not throw when fetch rejects, and the next flush still sends its batch', () => {
    fetchMock.mockRejectedValueOnce(new Error('network error'));
    const first = document.createElement('div');
    first.setAttribute('data-octo', 'first');
    document.body.appendChild(first);
    const second = document.createElement('div');
    second.setAttribute('data-octo', 'second');
    document.body.appendChild(second);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(first);
    expect(() => vi.advanceTimersByTime(1000)).not.toThrow();

    clickElement(second);
    vi.advanceTimersByTime(1000);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not throw and does not stall the queue when getOrCreateSessionId() throws', () => {
    const realCrypto = globalThis.crypto;
    // No stored session id and no crypto: generateUuid() must run and
    // throw, also inside start() itself (issue #107).
    window.sessionStorage.clear();
    vi.stubGlobal('crypto', undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(host);
    expect(() => vi.advanceTimersByTime(1000)).not.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();

    // The failed flush drops its batch. A later click still starts a timer.
    vi.stubGlobal('crypto', realCrypto);
    clickElement(host);
    vi.advanceTimersByTime(1000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(at(parseCalls(fetchMock), 0).body.clicks).toHaveLength(1);
  });

  it('uses the default flushIntervalMs of 5000 ms', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(4999);
    expect(fetchMock).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps a correct, non-negative ageMs when the system clock jumps backward', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(host);

    // A backward jump of the wall clock, for example an NTP correction.
    vi.setSystemTime(Date.now() - 30 * 60 * 1000);
    vi.advanceTimersByTime(1000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).ageMs).toBe(1000);
  });

  it('keeps a correct ageMs when the system clock jumps forward', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(host);

    vi.setSystemTime(Date.now() + 24 * 60 * 60 * 1000);
    vi.advanceTimersByTime(1000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).ageMs).toBe(1000);
  });

  it('reuses one sessionId across two flushes of the same tracker instance', () => {
    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();

    const first = document.createElement('div');
    first.setAttribute('data-octo', 'first');
    document.body.appendChild(first);
    clickElement(first);
    vi.advanceTimersByTime(1000);

    const second = document.createElement('div');
    second.setAttribute('data-octo', 'second');
    document.body.appendChild(second);
    clickElement(second);
    vi.advanceTimersByTime(1000);

    const calls = parseCalls(fetchMock);
    expect(calls).toHaveLength(2);
    expect(at(calls, 0).body.sessionId).toBe(at(calls, 1).body.sessionId);
  });

  it('keeps a sessionId already stored under the session storage key', () => {
    window.sessionStorage.setItem('octo_session_id', '3fa85f64-5717-4562-b3fc-2c963f66afa6');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(at(calls, 0).body.sessionId).toBe('3fa85f64-5717-4562-b3fc-2c963f66afa6');
  });

  // MINOR 5 of the TypeScript review: a stored sessionId must pass the
  // C5 rule of the contract, a UUID, before the tracker sends it.
  it('creates a new sessionId when the stored value breaks the UUID rule of rule C5', () => {
    window.sessionStorage.setItem('octo_session_id', 'not-a-uuid');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const sentId = at(parseCalls(fetchMock), 0).body.sessionId;
    expect(sentId).not.toBe('not-a-uuid');
    expect(sentId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    expect(window.sessionStorage.getItem('octo_session_id')).toBe(sentId);
  });

  it('falls back to an in-memory sessionId when sessionStorage.getItem throws', () => {
    vi.spyOn(window.sessionStorage.__proto__, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    const body = at(calls, 0).body;
    expect(typeof body.sessionId).toBe('string');
    expect(body.sessionId.length).toBeGreaterThan(0);
  });

  it('falls back to an in-memory sessionId when sessionStorage.setItem throws', async () => {
    // TypeScript review MINOR 2: window.sessionStorage holds a valid
    // stored id from the outer beforeEach hook, so a test with no clear()
    // here never reaches the catch block that this test names: the id
    // needs no rewrite, and setItem never runs. The clear() call below
    // forces a new session, so the write, and its catch block, both run.
    //
    // This write also sets the module-level fallback id
    // (moduleFallbackSessionId), because setItem always throws here. A
    // fresh module instance keeps that write out of every later test in
    // this file (see "a blocked sessionStorage" below, the same
    // pattern).
    vi.resetModules();
    const { createTracker: freshCreateTracker } = await import('./index.js');
    window.sessionStorage.clear();
    vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = freshCreateTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    const sessionStartCall = calls.find(
      (call) => call.body.clicks[0]?.element === 'octo:session-start',
    );
    expect(sessionStartCall).toBeDefined();
    const sentId = (sessionStartCall as FetchCall).body.sessionId;
    expect(sentId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    // setItem always throws, thus the id never reaches the real store.
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();

    const clickCall = clickBatchCalls(calls)[0];
    expect(clickCall?.body.sessionId).toBe(sentId);
  });

  it('does not throw when sessionStorage.removeItem throws during stop()', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 1000 });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(1000);

    vi.spyOn(window.sessionStorage.__proto__, 'removeItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });

    expect(() => tracker?.stop()).not.toThrow();
  });

  it('a second start() adds no second listener', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(calls).toHaveLength(1);
    expect(at(calls, 0).body.clicks).toHaveLength(1);
  });

  it('throws a TypeError when the caller gives no endpoint', () => {
    expect(() => createTracker({ endpoint: '' })).toThrow(TypeError);
  });

  it('adds no path field to a click entry without the routes option', () => {
    window.history.pushState({}, '', '/history/42');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
  });

  it('adds the matched path field to a click entry with the routes option', () => {
    window.history.pushState({}, '', '/history/42');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      routes: ['/', '/articles', '/articles/*', '/history/:id', '/ovdp/rates'],
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).path).toBe('/history/:id');
  });

  it('sends /other for a path without a matching pattern', () => {
    window.history.pushState({}, '', '/tokens/abc');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: ['/articles/*'] });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).path).toBe('/other');
  });

  it('reads location.pathname at the time of the click, not at the time of the flush', () => {
    window.history.pushState({}, '', '/articles/first');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: ['/articles/*'] });
    tracker.start();
    clickElement(host);
    window.history.pushState({}, '', '/articles/second');
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).path).toBe('/articles/first');
  });

  it('reads location.pathname only, never the query string or the fragment', () => {
    // A `*` segment rejects the character `?`. A tracker that also reads
    // `location.search` would send `/other` here in place of the match.
    window.history.pushState({}, '', '/articles/42?token=a-secret-token#a-secret-fragment');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: ['/articles/*'] });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).path).toBe('/articles/42');
  });

  it('drops a click on a data-octo value that starts with octo: in each letter case', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const upper = document.createElement('div');
    upper.setAttribute('data-octo', 'OCTO:foo');
    document.body.appendChild(upper);
    const lower = document.createElement('div');
    lower.setAttribute('data-octo', 'octo:anything');
    document.body.appendChild(lower);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(upper);
    clickElement(lower);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('drops a click on the exact text octo:session-start too (rule C38, before issue #107)', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'octo:session-start');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('writes one console warning for the first dropped octo: value, and no more for a second one', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const first = document.createElement('div');
    first.setAttribute('data-octo', 'OCTO:foo');
    document.body.appendChild(first);
    const second = document.createElement('div');
    second.setAttribute('data-octo', 'octo:bar');
    document.body.appendChild(second);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(first);
    clickElement(second);
    vi.advanceTimersByTime(5000);

    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('stop() resets the octo: warning, so a later start() warns again', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'octo:foo');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    tracker.stop();
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(warnSpy).toHaveBeenCalledTimes(2);
  });

  it('still records a normal click when a routes list holds no octo: value', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends no path field for the whole list when one entry is bad, with one console warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    window.history.pushState({}, '', '/history/42');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      routes: ['/history/:id', 42 as unknown as string],
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.mock.calls[0]?.[0])).toContain('index 1');
  });

  it('writes exactly one console warning for a list with three invalid entries, and it names each index', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      routes: [
        42 as unknown as string,
        '/articles',
        'no-leading-slash',
        '/history/:id',
        { bad: true } as unknown as string,
      ],
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalledTimes(1);
    const message = String(warnSpy.mock.calls[0]?.[0]);
    expect(message).toContain('index 0');
    expect(message).toContain('index 2');
    expect(message).toContain('index 4');
    expect(message).toContain('path');
  });

  it('holds no text of a pattern, a path, or a data-octo value in the routes warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    window.history.pushState({}, '', '/history/42');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'a-secret-element-name');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      routes: ['/history/:id', 'a-secret-bad-pattern'],
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    expect(warnSpy).toHaveBeenCalledTimes(1);
    const message = String(warnSpy.mock.calls[0]?.[0]);
    expect(message).not.toContain('a-secret-bad-pattern');
    expect(message).not.toContain('a-secret-element-name');
    expect(message).not.toContain('/history/42');
  });

  it('accepts an unknown routes value at the type level, and sends no path field for a non-array value', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    const routesValue: unknown = 12345;
    expect(() => {
      tracker = createTracker({ endpoint: ENDPOINT, routes: routesValue as string[] });
    }).not.toThrow();
    tracker?.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('sends no path field when a missing leading slash reorders the match (MAJOR 1, privacy review)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    window.history.pushState({}, '', '/reset-password/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({
      endpoint: ENDPOINT,
      // The first entry holds no leading slash, thus it is a bad pattern.
      // A dropped entry here would let the second pattern uncover the token.
      routes: ['reset-password/:token', '/:lang/*'],
    });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalled();
  });

  it('sends no path field and throws nothing when the routes option is not an array', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    expect(() => {
      tracker = createTracker({ endpoint: ENDPOINT, routes: '/articles' as unknown as string[] });
    }).not.toThrow();
    tracker?.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalled();
  });

  it('sends no path field when every entry of the routes option is bad, with one console warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: ['not-a-route'] });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalled();
  });

  it('sends no path field for an empty routes list, with one console warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: [] });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0)).not.toHaveProperty('path');
    expect(warnSpy).toHaveBeenCalled();
  });

  it('falls back to /other and throws nothing when the matcher itself throws', () => {
    const matchSpy = vi.spyOn(pathMatch, 'matchPreparedPath').mockImplementation(() => {
      throw new Error('a broken matcher');
    });
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT, routes: ['/articles/*'] });
    tracker.start();
    expect(() => clickElement(host)).not.toThrow();
    expect(() => vi.advanceTimersByTime(5000)).not.toThrow();

    const clicks = at(parseCalls(fetchMock), 0).body.clicks;
    expect(at(clicks, 0).path).toBe('/other');
    matchSpy.mockRestore();
  });

  // Issue #36, step 1: a pagehide listener flushes the queue with fetch
  // and keepalive true, without a wait for the flush timer.
  describe('the pagehide flush', () => {
    it('sends the queue on pagehide, with fetch and keepalive true', () => {
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      window.dispatchEvent(new Event('pagehide'));

      const calls = parseCalls(fetchMock);
      expect(calls).toHaveLength(1);
      expect(at(calls, 0).init.keepalive).toBe(true);
      expect(at(calls, 0).body.clicks).toEqual([{ element: 'save', ageMs: 0 }]);
    });

    it('sends nothing on pagehide with an empty queue', () => {
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      window.dispatchEvent(new Event('pagehide'));

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('adds only one pagehide listener, also with a second start() call', () => {
      const addSpy = vi.spyOn(window, 'addEventListener');
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      tracker.start();

      const pagehideCalls = addSpy.mock.calls.filter((call) => call[0] === 'pagehide');
      expect(pagehideCalls).toHaveLength(1);
    });

    it('removes the pagehide listener on stop()', () => {
      const removeSpy = vi.spyOn(window, 'removeEventListener');
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      tracker.stop();

      expect(removeSpy.mock.calls.some((call) => call[0] === 'pagehide')).toBe(true);
    });
  });

  // Issue #36, step 2: a visibilitychange listener flushes the queue in
  // the same way, only when the document becomes hidden.
  describe('the visibilitychange flush', () => {
    it('sends the queue on visibilitychange to hidden, with fetch and keepalive true', () => {
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      setVisibilityState('hidden');
      document.dispatchEvent(new Event('visibilitychange'));

      const calls = parseCalls(fetchMock);
      expect(calls).toHaveLength(1);
      expect(at(calls, 0).init.keepalive).toBe(true);
    });

    it('does not flush on visibilitychange to visible', () => {
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      setVisibilityState('visible');
      document.dispatchEvent(new Event('visibilitychange'));

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('adds only one visibilitychange listener, also with a second start() call', () => {
      const addSpy = vi.spyOn(document, 'addEventListener');
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      tracker.start();

      const calls = addSpy.mock.calls.filter((call) => call[0] === 'visibilitychange');
      expect(calls).toHaveLength(1);
    });

    it('removes the visibilitychange listener on stop()', () => {
      const removeSpy = vi.spyOn(document, 'removeEventListener');
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      tracker.stop();

      expect(removeSpy.mock.calls.some((call) => call[0] === 'visibilitychange')).toBe(true);
    });
  });

  // Issue #36, step 3: the retry rule. One retry after a network error, a
  // 5xx response, or a 429 response, after a wait. A drop after each
  // other 4xx response, with no third send of the same batch.
  describe('the retry rule', () => {
    it('retries a batch one time after a network error, after a wait', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockRejectedValueOnce(new Error('network error'))
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await expect(vi.advanceTimersByTimeAsync(5000)).resolves.not.toThrow();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await expect(vi.advanceTimersByTimeAsync(500)).resolves.not.toThrow();

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('drops a batch after a second network error, and never sends it a third time', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock.mockRejectedValue(new Error('network error'));
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await expect(vi.advanceTimersByTimeAsync(5500)).resolves.not.toThrow();
      expect(fetchMock).toHaveBeenCalledTimes(2);

      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('retries a batch one time after a 5xx response, after a wait', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockResolvedValueOnce({ ok: false, status: 503 })
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(500);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('drops a batch after a second 5xx response, and never sends it a third time', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock.mockResolvedValue({ ok: false, status: 500 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5500);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('retries a batch one time after a 429 response, after a longer wait', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockResolvedValueOnce({ ok: false, status: 429 })
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(2000);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    // Reliability review MAJOR 1: the second send must wait, and not fire
    // at once. This test proves the exact boundary, with no jitter.
    it('waits the full delay before it sends a batch again after a network error', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockRejectedValueOnce(new Error('network error'))
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(499);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(1);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('waits the full delay before it sends a batch again after a 429 response', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockResolvedValueOnce({ ok: false, status: 429 })
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(1999);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(1);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    // TypeScript review MAJOR 1: a run token stops a retry after stop().
    it('sends no retry after stop(), also when the first request settles after stop()', async () => {
      let rejectFetch: (reason: unknown) => void = () => {
        throw new Error('rejectFetch was not set');
      };
      fetchMock.mockImplementationOnce(
        () =>
          new Promise((_resolve, reject) => {
            rejectFetch = reject;
          }),
      );
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      tracker.stop();
      rejectFetch(new Error('network error'));
      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    // The run token check must also hold during the retry wait, not only
    // at the failed response. stop() runs here between the failure and
    // the delayed second send.
    it('sends no retry after stop() runs during the wait before the retry', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock.mockRejectedValueOnce(new Error('network error'));
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      tracker.stop();
      await vi.advanceTimersByTimeAsync(1000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('drops a batch after one other 4xx response, with no retry', async () => {
      fetchMock.mockResolvedValueOnce({ ok: false, status: 400 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
  });

  // Issue #36, step 4 (maintainer decision of 2026-09-22, reliability
  // review MAJOR 3): a batch from the pagehide listener, or from the
  // visibilitychange listener, goes out one time only, with no retry. A
  // browser can freeze the page after the hidden state and drop the
  // connection, so a retry there gives the largest risk of a double
  // count. Only the timer flush sends a batch a second time.
  describe('the single send of the lifecycle flush', () => {
    it('sends a batch only one time from pagehide, also after a network error', async () => {
      fetchMock.mockRejectedValueOnce(new Error('network error'));
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      window.dispatchEvent(new Event('pagehide'));
      // A wrong retry count would send a second request from a promise
      // callback. Flush pending microtasks before the count check.
      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('sends a batch only one time from pagehide, also after a 503 response', async () => {
      fetchMock.mockResolvedValueOnce({ ok: false, status: 503 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      window.dispatchEvent(new Event('pagehide'));
      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('sends a batch only one time from visibilitychange too, also after a network error', async () => {
      fetchMock.mockRejectedValueOnce(new Error('network error'));
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      setVisibilityState('hidden');
      expect(() => document.dispatchEvent(new Event('visibilitychange'))).not.toThrow();
      await vi.advanceTimersByTimeAsync(3000);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('keeps the retry of the timer flush, unlike the lifecycle flush', async () => {
      vi.spyOn(Math, 'random').mockReturnValue(0);
      fetchMock
        .mockRejectedValueOnce(new Error('network error'))
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      // No lifecycle event runs here. The queue waits for the timer flush.
      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 5000 });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(1000);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });

  // Reliability review MINOR 2: a lifecycle flush must clear the pending
  // timer, so a later click gets its own full flushIntervalMs wait.
  describe('the pending timer of a lifecycle flush', () => {
    it('clears the pending timer, so a later click waits its own flushIntervalMs', () => {
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 5000 });
      tracker.start();
      clickElement(host); // t = 0, the timer would fire at t = 5000.
      vi.advanceTimersByTime(1000); // t = 1000.
      window.dispatchEvent(new Event('pagehide')); // Sends the batch, clears the timer.
      expect(fetchMock).toHaveBeenCalledTimes(1);

      clickElement(host); // t = 1000, a fresh timer must fire at t = 6000.
      vi.advanceTimersByTime(4000); // t = 5000: the old, stale deadline.
      expect(fetchMock).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(1000); // t = 6000: the fresh deadline.
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });

  // Reliability review MINOR 4: a synchronous throw of fetch, for one
  // split batch, must not stop the send of a later batch.
  describe('a synchronous throw of one split batch', () => {
    it('still sends a later batch when an earlier batch throws at the fetch call', () => {
      const clickA = { element: 'a', ageMs: 0 };
      const clickB = { element: 'b', ageMs: 0 };
      vi.spyOn(batchModule, 'splitIntoRequestBatches').mockReturnValue([[clickA], [clickB]]);
      fetchMock.mockImplementationOnce(() => {
        throw new Error('fetch is not defined');
      });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      expect(() => clickElement(host)).not.toThrow();
      expect(() => vi.advanceTimersByTime(5000)).not.toThrow();

      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(at(parseCalls(fetchMock), 1).body.clicks).toEqual([clickB]);
    });
  });

  // Issue #36, step 6: the tracker measures the encoded body as UTF-8
  // bytes, and it splits a batch whose body passes 15 000 bytes.
  describe('the byte-based request split', () => {
    it('keeps 50 entries with a 150-byte path each in one request, below the 16 KB limit of C18', () => {
      const longSegment = 'a'.repeat(149);
      window.history.pushState({}, '', `/${longSegment}`);
      tracker = createTracker({
        endpoint: ENDPOINT,
        routes: [`/${longSegment}`],
        flushIntervalMs: 100000,
      });
      tracker.start();
      for (let i = 0; i < 50; i += 1) {
        const host = document.createElement('div');
        host.setAttribute('data-octo', `e${i}`);
        document.body.appendChild(host);
        clickElement(host);
      }
      vi.advanceTimersByTime(100000);

      const calls = parseCalls(fetchMock);
      expect(calls).toHaveLength(1);
      expect(at(calls, 0).body.clicks).toHaveLength(50);
      const bodyBytes = new TextEncoder().encode(String(at(calls, 0).init.body)).length;
      expect(bodyBytes).toBeLessThan(16 * 1024);
      expect(bodyBytes).toBeLessThan(64 * 1024);
    });

    it('gives the session id and the computed clicks to splitIntoRequestBatches', () => {
      const splitSpy = vi.spyOn(batchModule, 'splitIntoRequestBatches');
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      vi.advanceTimersByTime(5000);

      expect(splitSpy).toHaveBeenCalledTimes(1);
      const [sessionIdArg, clicksArg] = splitSpy.mock.calls[0] as [string, unknown[]];
      expect(typeof sessionIdArg).toBe('string');
      expect(clicksArg).toEqual([{ element: 'save', ageMs: 5000 }]);
    });

    it('sends one request for each batch that the splitter gives', () => {
      // This test proves the loop only: it mocks the splitter, so its
      // batches hold no real byte count. See the next test for the byte
      // limit, with no mock and 200 entries at the contract maximum
      // (TypeScript review MAJOR 2, reliability review MAJOR 2).
      const clickA = { element: 'a', ageMs: 0 };
      const clickB = { element: 'b', ageMs: 0 };
      vi.spyOn(batchModule, 'splitIntoRequestBatches').mockReturnValue([[clickA], [clickB]]);
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      vi.advanceTimersByTime(5000);

      const calls = parseCalls(fetchMock);
      expect(calls).toHaveLength(2);
      expect(at(calls, 0).body.clicks).toEqual([clickA]);
      expect(at(calls, 1).body.clicks).toEqual([clickB]);
    });

    // TypeScript review MAJOR 2 and reliability review MAJOR 2: a full
    // queue of 200 entries, at the contract maximum sizes, through one
    // real pagehide flush, with no spy on the splitter.
    it('holds each request and the whole keepalive flush below the limits, with 200 entries at the contract maximum', () => {
      const longSegment = 'a'.repeat(149);
      window.history.pushState({}, '', `/${longSegment}`);
      const element = 'b'.repeat(100);
      tracker = createTracker({
        endpoint: ENDPOINT,
        routes: [`/${longSegment}`],
        flushIntervalMs: 1000000,
      });
      tracker.start();
      for (let i = 0; i < 200; i += 1) {
        const host = document.createElement('div');
        host.setAttribute('data-octo', element);
        document.body.appendChild(host);
        clickElement(host);
      }
      // The largest possible ageMs before the server clamp of rule C14.
      vi.advanceTimersByTime(600000);
      window.dispatchEvent(new Event('pagehide'));

      const calls = parseCalls(fetchMock);
      const totalClicks = calls.reduce((sum, call) => sum + call.body.clicks.length, 0);
      expect(totalClicks).toBe(200);

      const sizes = calls.map((call) => new TextEncoder().encode(String(call.init.body)).length);
      for (const size of sizes) {
        expect(size).toBeLessThan(15000);
      }
      const totalBytes = sizes.reduce((sum, size) => sum + size, 0);
      expect(totalBytes).toBeLessThan(64 * 1024);
      for (const call of calls) {
        expect(call.init.keepalive).toBe(true);
      }
    });
  });

  // Issue #107: start() sends the entry octo:session-start at once, in
  // its own request, for a new session id (contract rule C38, design
  // decision D41).
  describe('the session start', () => {
    it('sends one request with one octo:session-start entry, before any click and with no wait', () => {
      window.sessionStorage.clear();

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();

      // No click, and no vi.advanceTimersByTime call: the request already
      // went out inside the start() call itself.
      const calls = parseCalls(fetchMock);
      expect(calls).toHaveLength(1);
      expect(at(calls, 0).body.clicks).toEqual([{ element: 'octo:session-start', ageMs: 0 }]);
    });

    it('holds the sessionId of the new session', () => {
      window.sessionStorage.clear();

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();

      const call = at(parseCalls(fetchMock), 0);
      expect(call.body.sessionId).toBe(window.sessionStorage.getItem(SESSION_STORAGE_KEY));
    });

    it('adds the path field of rule C39 when the routes option gives one', () => {
      window.sessionStorage.clear();
      window.history.pushState({}, '', '/history/42');

      tracker = createTracker({
        endpoint: ENDPOINT,
        routes: ['/', '/history/:id'],
      });
      tracker.start();

      const clicks = at(parseCalls(fetchMock), 0).body.clicks;
      expect(at(clicks, 0).path).toBe('/history/:id');
    });

    it('adds no path field without the routes option', () => {
      window.sessionStorage.clear();

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();

      const clicks = at(parseCalls(fetchMock), 0).body.clicks;
      expect(at(clicks, 0)).not.toHaveProperty('path');
    });

    it('sends the request with keepalive true, so a fast page exit does not cancel it', () => {
      window.sessionStorage.clear();

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();

      expect(at(parseCalls(fetchMock), 0).init.keepalive).toBe(true);
    });

    it('sends no session start when a second start() reads the same stored id (a page reload)', () => {
      window.sessionStorage.clear();

      const firstLoad = createTracker({ endpoint: ENDPOINT });
      firstLoad.start();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      const storedId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
      // firstLoad.stop() removes its own listeners, so it touches no later
      // test. A real page reload never calls stop(). It keeps
      // sessionStorage, thus the test restores the id right after.
      firstLoad.stop();
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, storedId as string);
      fetchMock.mockClear();

      // A second createTracker() call simulates the fresh module state of
      // a reloaded page. The browser keeps sessionStorage across a reload.
      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('records a click on a data-octo="octo:session-start" element as no click, also right after the automatic send', () => {
      vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      window.sessionStorage.clear();
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'octo:session-start');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      clickElement(host);
      vi.advanceTimersByTime(5000);

      // Only the own call of the tracker sent the one request above (rule
      // C38). The page click added no second one.
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('writes no console line that holds the session id or the path', () => {
      window.sessionStorage.clear();
      window.history.pushState({}, '', '/history/42');
      const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

      tracker = createTracker({ endpoint: ENDPOINT, routes: ['/history/:id'] });
      tracker.start();

      const id = window.sessionStorage.getItem(SESSION_STORAGE_KEY) as string;
      for (const spy of [logSpy, warnSpy, errorSpy]) {
        for (const call of spy.mock.calls) {
          const text = call.map((part) => String(part)).join(' ');
          expect(text).not.toContain(id);
          expect(text).not.toContain('/history/42');
        }
      }
    });

    // The referrerHost field of the session start (contract rule C40,
    // design decision D42, issue #108). A click entry never holds this
    // field; the field sits on the session start entry only.
    describe('the referrerHost field of the session start', () => {
      it('sends referrerHost google.com for a referrer https://www.google.co.uk/search?q=x', () => {
        setReferrer('https://www.google.co.uk/search?q=x');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0).referrerHost).toBe('google.com');
      });

      it('sends referrerHost bing.com for a referrer https://www.bing.com/', () => {
        setReferrer('https://www.bing.com/');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0).referrerHost).toBe('bing.com');
      });

      it('sends the literal other for a referrer https://example.org/page', () => {
        setReferrer('https://example.org/page');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0).referrerHost).toBe('other');
      });

      it('matches an upper-case referrer host too, in lower case', () => {
        setReferrer('https://WWW.GOOGLE.COM/x');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0).referrerHost).toBe('google.com');
      });

      it('sends no referrerHost for an empty referrer', () => {
        setReferrer('');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0)).not.toHaveProperty('referrerHost');
      });

      it('sends no referrerHost for the origin of the app', () => {
        setReferrer(`${location.origin}/dashboard`);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0)).not.toHaveProperty('referrerHost');
      });

      it('sends no referrerHost for an IP literal', () => {
        setReferrer('http://192.168.1.1/');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0)).not.toHaveProperty('referrerHost');
      });

      it('sends no referrerHost for a host without a dot', () => {
        setReferrer('https://intranet/page');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0)).not.toHaveProperty('referrerHost');
      });

      it('sends no referrerHost for a referrer with a scheme other than http or https', () => {
        setReferrer('ftp://files.example/report');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        const clicks = at(parseCalls(fetchMock), 0).body.clicks;
        expect(at(clicks, 0)).not.toHaveProperty('referrerHost');
      });

      it('sends no referrerHost on a click entry, only on the session start', () => {
        setReferrer('https://www.google.com/');
        window.sessionStorage.clear();
        const host = document.createElement('div');
        host.setAttribute('data-octo', 'save');
        document.body.appendChild(host);

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        clickElement(host);
        vi.advanceTimersByTime(5000);

        const clickCalls = clickBatchCalls(parseCalls(fetchMock));
        expect(at(clickCalls, 0).body.clicks[0]).not.toHaveProperty('referrerHost');
      });

      it('keeps the session start as the first entry, with referrerHost, ahead of a click (rule C38)', () => {
        setReferrer('https://www.google.com/');
        window.sessionStorage.clear();
        const host = document.createElement('div');
        host.setAttribute('data-octo', 'save');
        document.body.appendChild(host);

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        clickElement(host);
        vi.advanceTimersByTime(5000);

        const firstCall = at(parseCalls(fetchMock), 0);
        expect(firstCall.body.clicks[0]?.element).toBe('octo:session-start');
        expect(firstCall.body.clicks[0]?.referrerHost).toBe('google.com');
      });
    });

    // The retry rule of the session start follows the timer flush (issue
    // #107), not the lifecycle rule: one retry, after a wait.
    describe('the retry rule of the session start', () => {
      it('retries one time after a 500 response, after a wait', async () => {
        vi.spyOn(Math, 'random').mockReturnValue(0);
        fetchMock
          .mockResolvedValueOnce({ ok: false, status: 500 })
          .mockResolvedValueOnce({ ok: true, status: 204 });
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        expect(() => tracker?.start()).not.toThrow();
        expect(fetchMock).toHaveBeenCalledTimes(1);

        await expect(vi.advanceTimersByTimeAsync(500)).resolves.not.toThrow();

        expect(fetchMock).toHaveBeenCalledTimes(2);
      });

      it('drops the entry after a second 500 response, and throws no error into the page', async () => {
        vi.spyOn(Math, 'random').mockReturnValue(0);
        fetchMock.mockResolvedValue({ ok: false, status: 500 });
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        expect(() => tracker?.start()).not.toThrow();
        expect(fetchMock).toHaveBeenCalledTimes(1);

        await expect(vi.advanceTimersByTimeAsync(500)).resolves.not.toThrow();
        expect(fetchMock).toHaveBeenCalledTimes(2);

        await expect(vi.advanceTimersByTimeAsync(5000)).resolves.not.toThrow();
        // No third attempt: the timer retry rule allows one retry only.
        expect(fetchMock).toHaveBeenCalledTimes(2);
      });
    });

    describe('the wait for a visible, non-prerendering document', () => {
      it('sends nothing while document.prerendering is true, then sends one time after prerenderingchange', () => {
        setPrerendering(true);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(fetchMock).not.toHaveBeenCalled();

        setPrerendering(false);
        document.dispatchEvent(new Event('prerenderingchange'));
        expect(fetchMock).toHaveBeenCalledTimes(1);

        // A second event finds no pending wait: it must not send a second time.
        document.dispatchEvent(new Event('prerenderingchange'));
        expect(fetchMock).toHaveBeenCalledTimes(1);
      });

      it('waits for a hidden document, then sends one time after visibilitychange to visible, and no more after a later hide-show cycle', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(fetchMock).not.toHaveBeenCalled();

        setVisibilityState('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        expect(fetchMock).toHaveBeenCalledTimes(1);

        // TypeScript review MINOR 3: the { once: true } listener option
        // must matter here. A later hide-show cycle sends no second entry.
        setVisibilityState('hidden');
        document.dispatchEvent(new Event('visibilitychange'));
        setVisibilityState('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        expect(fetchMock).toHaveBeenCalledTimes(1);
      });

      it('stop() cancels the pending session start while it waits for visibilitychange', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        tracker.stop();

        setVisibilityState('visible');
        expect(() => document.dispatchEvent(new Event('visibilitychange'))).not.toThrow();

        expect(fetchMock).not.toHaveBeenCalled();
      });

      it('stop() cancels the pending session start while it waits for prerenderingchange', () => {
        setPrerendering(true);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        tracker.stop();

        setPrerendering(false);
        expect(() => document.dispatchEvent(new Event('prerenderingchange'))).not.toThrow();

        expect(fetchMock).not.toHaveBeenCalled();
      });

      // Reliability review MINOR 2: no test held the run token and the
      // listener removal of stop() together, for a session that begins a
      // second wait right after the first one is cancelled.
      it('sends exactly one session start when start() runs again after stop(), for a document that stays hidden through the first wait', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        tracker.stop();
        tracker.start();

        setVisibilityState('visible');
        document.dispatchEvent(new Event('visibilitychange'));

        expect(fetchMock).toHaveBeenCalledTimes(1);
      });

      it('sends exactly one session start when start() runs again after stop(), for a document that stays in a prerender through the first wait', () => {
        setPrerendering(true);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        tracker.stop();
        tracker.start();

        setPrerendering(false);
        document.dispatchEvent(new Event('prerenderingchange'));

        expect(fetchMock).toHaveBeenCalledTimes(1);
      });

      it('sends at once when the document is already visible and not prerendering', () => {
        setVisibilityState('visible');
        setPrerendering(false);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        expect(fetchMock).toHaveBeenCalledTimes(1);
      });
    });

    // Maintainer decision of 2026-09-22 (MAJOR 1 of the reliability
    // review): a new id enters sessionStorage only at the moment the
    // session start request goes out, after the wait for the visible
    // state and the end of a prerender. A page that never becomes
    // visible stores nothing, thus the next document of the same tab
    // starts a fresh session.
    describe('the storage write timing (MAJOR 1)', () => {
      it('leaves no stored id when a hidden document ends before it becomes visible (a pagehide while hidden)', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(fetchMock).not.toHaveBeenCalled();

        window.dispatchEvent(new Event('pagehide'));

        expect(fetchMock).not.toHaveBeenCalled();
        expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
      });

      it('stores the id at the moment the session start goes out, when the document becomes visible', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();

        setVisibilityState('visible');
        document.dispatchEvent(new Event('visibilitychange'));

        const sentId = at(parseCalls(fetchMock), 0).body.sessionId;
        expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(sentId);
      });

      it('sends no second session start on a reload after the id enters storage at the send, for a document that started hidden', () => {
        setVisibilityState('hidden');
        window.sessionStorage.clear();

        const firstLoad = createTracker({ endpoint: ENDPOINT });
        firstLoad.start();
        setVisibilityState('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const storedId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);

        // A real page reload never calls stop(). This call only removes
        // the listeners of firstLoad, so it touches no later test.
        firstLoad.stop();
        window.sessionStorage.setItem(SESSION_STORAGE_KEY, storedId as string);
        fetchMock.mockClear();

        // A second createTracker() call simulates the fresh module state
        // of a reloaded page.
        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        expect(fetchMock).not.toHaveBeenCalled();
      });
    });

    describe('the navigator.webdriver filter', () => {
      it('sends nothing, also for a later click, when navigator.webdriver is true', () => {
        setWebdriver(true);
        window.sessionStorage.clear();
        const host = document.createElement('div');
        host.setAttribute('data-octo', 'save');
        document.body.appendChild(host);

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();
        clickElement(host);
        vi.advanceTimersByTime(5000);

        expect(fetchMock).not.toHaveBeenCalled();
      });

      it('sends the session start entry when ignoreWebdriver is true, also with navigator.webdriver true', () => {
        setWebdriver(true);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT, ignoreWebdriver: true });
        tracker.start();

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(at(parseCalls(fetchMock), 0).body.clicks[0]?.element).toBe('octo:session-start');
      });

      it('records a click when ignoreWebdriver is true, also with navigator.webdriver true', () => {
        setWebdriver(true);
        window.sessionStorage.clear();
        const host = document.createElement('div');
        host.setAttribute('data-octo', 'save');
        document.body.appendChild(host);

        tracker = createTracker({ endpoint: ENDPOINT, ignoreWebdriver: true });
        tracker.start();
        fetchMock.mockClear();
        clickElement(host);
        vi.advanceTimersByTime(5000);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(at(parseCalls(fetchMock), 0).body.clicks[0]?.element).toBe('save');
      });

      it('sends the entry with the default false value of ignoreWebdriver: navigator.webdriver false sends it', () => {
        setWebdriver(false);
        window.sessionStorage.clear();

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        expect(fetchMock).toHaveBeenCalledTimes(1);
      });
    });

    describe('a session id already stored (not a new session)', () => {
      it('sends no session start for a valid stored id', () => {
        // The outer beforeEach hook already stores a valid id.
        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        expect(fetchMock).not.toHaveBeenCalled();
      });

      it('sends the session start for a stored id that fails the UUID rule of rule C5', () => {
        window.sessionStorage.setItem(SESSION_STORAGE_KEY, 'not-a-uuid');

        tracker = createTracker({ endpoint: ENDPOINT });
        tracker.start();

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(at(parseCalls(fetchMock), 0).body.clicks[0]?.element).toBe('octo:session-start');
      });
    });

    // A blocked sessionStorage keeps the fallback id in a module variable
    // (issue #107, step 4), thus each test here needs a fresh module
    // instance. Without this, an earlier test in this file could leave a
    // fallback id behind. This describe would then read it by mistake.
    describe('a blocked sessionStorage (the module-level fallback id)', () => {
      let freshCreateTracker: typeof createTracker;

      beforeEach(async () => {
        vi.resetModules();
        ({ createTracker: freshCreateTracker } = await import('./index.js'));
      });

      it('sends exactly one session start for the life of the document, also across a stop() and a later start()', () => {
        vi.spyOn(window.sessionStorage.__proto__, 'getItem').mockImplementation(() => {
          throw new DOMException('blocked');
        });
        vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
          throw new DOMException('blocked');
        });

        tracker = freshCreateTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(fetchMock).toHaveBeenCalledTimes(1);

        tracker.stop();
        tracker.start();

        // Still one: the fallback id lives at the module level, not inside
        // the tracker instance that stop() tore down.
        expect(fetchMock).toHaveBeenCalledTimes(1);
      });

      it('still records a click normally with a blocked sessionStorage', () => {
        vi.spyOn(window.sessionStorage.__proto__, 'getItem').mockImplementation(() => {
          throw new DOMException('blocked');
        });
        vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
          throw new DOMException('blocked');
        });
        const host = document.createElement('div');
        host.setAttribute('data-octo', 'save');
        document.body.appendChild(host);

        tracker = freshCreateTracker({ endpoint: ENDPOINT });
        tracker.start();
        fetchMock.mockClear();
        clickElement(host);
        vi.advanceTimersByTime(5000);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(at(parseCalls(fetchMock), 0).body.clicks[0]?.element).toBe('save');
      });

      // Reliability review MINOR 1: a store that reads null (no throw)
      // but fails each write is a different case from a full block. The
      // old readStoredSessionId() returned the real null here, thus a
      // second start() made a second, different id.
      it('gives one session start with one id, not two, when sessionStorage reads succeed but each write fails', () => {
        vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
          throw new DOMException('blocked');
        });
        window.sessionStorage.clear();

        tracker = freshCreateTracker({ endpoint: ENDPOINT });
        tracker.start();
        expect(fetchMock).toHaveBeenCalledTimes(1);
        const firstId = at(parseCalls(fetchMock), 0).body.sessionId;

        tracker.stop();
        tracker.start();

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const secondId = at(parseCalls(fetchMock), 0).body.sessionId;
        expect(secondId).toBe(firstId);
      });
    });
  });
});
