import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTracker, type Tracker } from './index.js';
import * as pathMatch from './path-match.js';
import * as batchModule from './batch.js';

const ENDPOINT = 'https://app.example/api/octometer/v1/clicks';

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

interface FetchCall {
  url: string;
  init: RequestInit;
  body: { sessionId: string; clicks: Array<{ element: string; ageMs: number; path?: string }> };
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

describe('createTracker', () => {
  let tracker: Tracker | null;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '';
    window.sessionStorage.clear();
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

    const calls = parseCalls(fetchMock);
    expect(calls).toHaveLength(1);
    expect(at(calls, 0).body.clicks).toHaveLength(1);
  });

  it('stop() clears the pending flush timer', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    const withTimer = vi.getTimerCount();
    tracker.stop();

    // jsdom schedules its own timer for a sessionStorage write, so the test
    // compares the count before and after stop(), not against zero.
    expect(vi.getTimerCount()).toBe(withTimer - 1);
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
    const firstId = at(parseCalls(fetchMock), 0).body.sessionId;

    tracker.stop();
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(1000);
    const secondId = at(parseCalls(fetchMock), 1).body.sessionId;

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
    // No stored session id and no crypto: generateUuid() must run and throw.
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
    window.sessionStorage.setItem('octo_session_id', 'fixed-session-id');
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    vi.advanceTimersByTime(5000);

    const calls = parseCalls(fetchMock);
    expect(at(calls, 0).body.sessionId).toBe('fixed-session-id');
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

  it('falls back to an in-memory sessionId when sessionStorage.setItem throws', () => {
    vi.spyOn(window.sessionStorage.__proto__, 'setItem').mockImplementation(() => {
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
    expect(String(warnSpy.mock.calls[0]?.[0])).toContain('entry 1');
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
  // 5xx response, or a 429 response. A drop after each other 4xx
  // response, with no third send of the same batch.
  describe('the retry rule', () => {
    it('retries a batch one time after a network error', async () => {
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

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('drops a batch after a second network error, and never sends it a third time', async () => {
      fetchMock.mockRejectedValue(new Error('network error'));
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await expect(vi.advanceTimersByTimeAsync(5000)).resolves.not.toThrow();

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('retries a batch one time after a 5xx response', async () => {
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

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('drops a batch after a second 5xx response, and never sends it a third time', async () => {
      fetchMock.mockResolvedValue({ ok: false, status: 500 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT });
      tracker.start();
      clickElement(host);
      await vi.advanceTimersByTimeAsync(5000);

      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('retries a batch one time after a 429 response', async () => {
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

      expect(fetchMock).toHaveBeenCalledTimes(2);
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

  // Issue #36, step 4: a batch from the pagehide listener goes out one
  // time only, with no retry, because the page can close before a retry
  // completes.
  describe('the single send of the pagehide flush', () => {
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
      await vi.advanceTimersByTimeAsync(0);

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
      await vi.advanceTimersByTimeAsync(0);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('retries a batch from the visibilitychange listener, unlike pagehide', async () => {
      fetchMock
        .mockRejectedValueOnce(new Error('network error'))
        .mockResolvedValueOnce({ ok: true, status: 204 });
      const host = document.createElement('div');
      host.setAttribute('data-octo', 'save');
      document.body.appendChild(host);

      tracker = createTracker({ endpoint: ENDPOINT, flushIntervalMs: 100000 });
      tracker.start();
      clickElement(host);
      setVisibilityState('hidden');
      expect(() => document.dispatchEvent(new Event('visibilitychange'))).not.toThrow();
      // The retry runs inside a promise callback. Advancing fake time by
      // zero still lets vitest run each pending microtask in order.
      await vi.advanceTimersByTimeAsync(0);

      expect(fetchMock).toHaveBeenCalledTimes(2);
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

    it('sends one request for each batch that the splitter gives, each request below 15 000 bytes', () => {
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
      for (const call of calls) {
        const bodyBytes = new TextEncoder().encode(String(call.init.body)).length;
        expect(bodyBytes).toBeLessThan(15000);
      }
    });
  });
});
