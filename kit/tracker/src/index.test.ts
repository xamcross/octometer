import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTracker, type Tracker } from './index.js';

const ENDPOINT = 'https://app.example/api/octometer/v1/clicks';

function parseCalls(fetchMock: ReturnType<typeof vi.fn>): {
  url: string;
  init: RequestInit;
  body: { sessionId: string; clicks: Array<{ element: string; ageMs: number }> };
}[] {
  return fetchMock.mock.calls.map(([url, init]: [string, RequestInit]) => ({
    url,
    init,
    body: JSON.parse(String(init.body)),
  }));
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
    expect(calls[0].body.clicks).toEqual([{ element: 'save', ageMs: 5000 }]);
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
    expect(calls[0].body.clicks).toEqual([{ element: 'shadow.save', ageMs: 5000 }]);
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

  it('stop() empties the queue, stops the timer, and removes the sessionStorage key', () => {
    const host = document.createElement('div');
    host.setAttribute('data-octo', 'save');
    document.body.appendChild(host);

    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();
    clickElement(host);
    // The tracker creates the session id on the first flush, so read it before stop().
    tracker.stop();

    vi.advanceTimersByTime(60000);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('octo_session_id')).toBeNull();
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
    expect(calls[0].body.clicks).toHaveLength(50);
    expect(calls[1].body.clicks).toHaveLength(50);
    expect(calls[2].body.clicks).toHaveLength(20);
  });

  it('drops the oldest entry once the queue holds 200 entries', () => {
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
    expect(Object.keys(calls[0].body).sort()).toEqual(['clicks', 'sessionId']);
    expect(Object.keys(calls[0].body.clicks[0]).sort()).toEqual(['ageMs', 'element']);
    expect(typeof calls[0].body.sessionId).toBe('string');
    expect(calls[0].body.sessionId.length).toBeGreaterThan(0);
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
    expect(calls[0].body).not.toHaveProperty('userId');
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

    const calls = parseCalls(fetchMock);
    expect(calls[0].init.method).toBe('POST');
    expect(calls[0].init.credentials).toBe('include');
    expect((calls[0].init.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
    expect((calls[0].init.headers as Record<string, string>)['X-Octo-App']).toBe('demo');
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
    expect(calls[0].body.sessionId).toBe(calls[1].body.sessionId);
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
    expect(calls[0].body.sessionId).toBe('fixed-session-id');
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
    expect(typeof calls[0].body.sessionId).toBe('string');
    expect(calls[0].body.sessionId.length).toBeGreaterThan(0);
    vi.restoreAllMocks();
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
    expect(typeof calls[0].body.sessionId).toBe('string');
    expect(calls[0].body.sessionId.length).toBeGreaterThan(0);
    vi.restoreAllMocks();
  });

  it('does not throw when sessionStorage.removeItem throws during stop()', () => {
    vi.spyOn(window.sessionStorage.__proto__, 'removeItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });
    tracker = createTracker({ endpoint: ENDPOINT });
    tracker.start();

    expect(() => tracker?.stop()).not.toThrow();
    vi.restoreAllMocks();
  });

  it('does not call start() twice, so a second start() adds no second listener', () => {
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
    expect(calls[0].body.clicks).toHaveLength(1);
  });
});
