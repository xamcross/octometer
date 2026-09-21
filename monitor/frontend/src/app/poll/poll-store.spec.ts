import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import { PollStore, createPollStore } from './poll-store';

/** Builds a tbody with one focusable button. Adds the tbody to the document. */
function addTbodyWithButton(): HTMLButtonElement {
  const table = document.createElement('table');
  const tbody = document.createElement('tbody');
  const row = document.createElement('tr');
  const cell = document.createElement('td');
  const button = document.createElement('button');
  cell.appendChild(button);
  row.appendChild(cell);
  tbody.appendChild(row);
  table.appendChild(tbody);
  document.body.appendChild(table);
  return button;
}

/**
 * Runs a change detection cycle, so a pending Angular effect runs, for
 * example the toObservable bridge that watches the paused signal.
 */
function flushEffects(): void {
  TestBed.inject(ApplicationRef).tick();
}

/**
 * Flushes the gate: the auditTime(0) tick, and then the first tick of a
 * fresh timer(0, ms) from a gate reopen.
 * Vitest fake timers need a time advance above 0 to notice a timer that a
 * timer callback creates.
 * The second step moves the clock by 1 ms for that reason.
 */
function settleGate(): void {
  vi.advanceTimersByTime(0);
  vi.advanceTimersByTime(1);
}

describe('createPollStore', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    vi.useFakeTimers();
    document.body.innerHTML = '';
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    httpMock.verify();
    // Restores the real activeElement getter after a test overrides it.
    delete (document as unknown as Record<string, unknown>)['activeElement'];
  });

  function flushHealth(body: unknown): void {
    httpMock.expectOne('/api/health').flush(body as Record<string, unknown>);
  }

  /**
   * Creates a store, answers the first health request with refreshSeconds,
   * and runs the first data tick. Returns the store.
   */
  function startStore<T>(request: () => Observable<T>, refreshSeconds = 10): PollStore<T> {
    const store = TestBed.runInInjectionContext(() => createPollStore(request));
    vi.advanceTimersByTime(0);
    flushHealth({ refreshSeconds });
    vi.advanceTimersByTime(0);
    return store;
  }

  it('reads the refresh interval from refreshSeconds of GET /api/health', () => {
    let calls = 0;
    const store = startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);
    expect(store.data()).toBe('v1');

    vi.advanceTimersByTime(9_000);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(1_000);
    expect(calls).toBe(2);
  });

  it('tries the health route again until it gets a valid interval', () => {
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );

    vi.advanceTimersByTime(0);
    httpMock
      .expectOne('/api/health')
      .flush('unavailable', { status: 503, statusText: 'Service Unavailable' });
    expect(store.error()).toBeDefined();
    expect(calls).toBe(0);

    vi.advanceTimersByTime(5_000);
    flushHealth({ refreshSeconds: 10 });
    expect(store.error()).toBeUndefined();

    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);
  });

  it('rejects a refreshSeconds value that is not a number above 0, and uses the fallback', () => {
    let calls = 0;
    TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );

    vi.advanceTimersByTime(0);
    flushHealth({ refreshSeconds: 0 });
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(59_999);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(1);
    expect(calls).toBe(2);
  });

  it('keeps the last good data after a failed request and sets error', () => {
    let calls = 0;
    const store = startStore(() => {
      calls++;
      if (calls === 1) {
        return of('first');
      }
      return throwError(() => new Error('boom'));
    }, 10);
    expect(store.data()).toBe('first');
    expect(store.error()).toBeUndefined();

    vi.advanceTimersByTime(10_000);
    expect(store.data()).toBe('first');
    expect(store.error()).toBeInstanceOf(Error);
  });

  it('does not cancel a slow request on the next tick', () => {
    const pending = new Subject<string>();
    let calls = 0;
    const store = startStore(() => {
      calls++;
      return calls === 1 ? pending.asObservable() : of('should-not-run');
    }, 10);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    pending.next('done');
    pending.complete();
    expect(store.data()).toBe('done');
  });

  it('stops the refresh while the tab is hidden and runs once when it shows again', () => {
    let calls = 0;
    startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    settleGate();
    expect(calls).toBe(2);
  });

  it('stops the refresh while the focus is inside a tbody, and starts again on focusout', () => {
    const button = addTbodyWithButton();
    let calls = 0;
    startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);

    button.focus();
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    // The request comes at once, well before the next full interval.
    button.blur();
    settleGate();
    expect(calls).toBe(2);
  });

  it('stops the refresh while paused is true', () => {
    let calls = 0;
    const store = startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);

    store.paused.set(true);
    flushEffects();
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    store.paused.set(false);
    flushEffects();
    settleGate();
    expect(calls).toBe(2);
  });

  it('sends one request at once on an unpause, before the next tick', () => {
    let calls = 0;
    const store = startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 60);
    store.paused.set(true);
    flushEffects();
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(30_000);
    store.paused.set(false);
    flushEffects();
    settleGate();
    expect(calls).toBe(2);
  });

  it('does not send two requests when the gate reopens close to a due tick', () => {
    const button = addTbodyWithButton();
    let calls = 0;
    startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);

    // 10 ms before the tick that a plain timer(0, 10_000) would have sent.
    vi.advanceTimersByTime(9_990);
    button.focus();
    settleGate();
    button.blur();
    settleGate();
    expect(calls).toBe(2);

    // The moment of the old due tick, and a little after it, sends no extra request.
    vi.advanceTimersByTime(20);
    expect(calls).toBe(2);

    // The interval starts again from the reopen, so the next tick is 10 s later.
    vi.advanceTimersByTime(9_980);
    expect(calls).toBe(3);
  });

  it('treats a null document.activeElement as focus outside a tbody', () => {
    Object.defineProperty(document, 'activeElement', { value: null, configurable: true });
    let calls = 0;
    startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 10);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(2);
  });

  it('refresh() sends one request now, and it does not wait for a paused store', () => {
    let calls = 0;
    const store = startStore(() => {
      calls++;
      return of(`v${calls}`);
    }, 60);
    expect(calls).toBe(1);

    store.paused.set(true);
    flushEffects();
    vi.advanceTimersByTime(0);

    store.refresh();
    expect(calls).toBe(2);
  });

  it('firstLoadPending is true until the store gets its first answer', () => {
    const pending = new Subject<string>();
    const store = startStore(() => pending.asObservable(), 10);
    expect(store.firstLoadPending()).toBe(true);

    pending.next('v1');
    pending.complete();
    expect(store.firstLoadPending()).toBe(false);
  });

  it('firstLoadPending becomes false after a failed first request too', () => {
    const store = startStore(() => throwError(() => new Error('boom')), 10);
    expect(store.firstLoadPending()).toBe(false);
  });

  it('sends no request when paused becomes true while the health request is open', () => {
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    vi.advanceTimersByTime(0);

    store.paused.set(true);
    flushEffects();
    flushHealth({ refreshSeconds: 10 });
    vi.advanceTimersByTime(0);

    expect(calls).toBe(0);
  });

  it('sends no request when document.hidden becomes true while the health request is open', () => {
    let calls = 0;
    TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    vi.advanceTimersByTime(0);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    flushHealth({ refreshSeconds: 10 });
    vi.advanceTimersByTime(0);

    expect(calls).toBe(0);
  });
});
