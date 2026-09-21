import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject, of, throwError } from 'rxjs';

import { createPollStore } from './poll-store';

/**
 * Builds a tbody with one focusable button, and adds it to the document.
 * The test focuses the button to put the focus inside the tbody.
 */
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
  });

  function flushHealth(refreshSeconds: number): void {
    httpMock.expectOne('/api/health').flush({ refreshSeconds });
  }

  it('reads the refresh interval from refreshSeconds of GET /api/health', () => {
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);
    expect(store.data()).toBe('v1');

    // A tick before 10 seconds must not poll again.
    vi.advanceTimersByTime(9_000);
    expect(calls).toBe(1);

    // The tick at 10 seconds must poll again.
    vi.advanceTimersByTime(1_000);
    expect(calls).toBe(2);
  });

  it('keeps the last good data after a failed request and sets error', () => {
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        if (calls === 1) {
          return of('first');
        }
        return throwError(() => new Error('boom'));
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(store.data()).toBe('first');
    expect(store.error()).toBeUndefined();

    vi.advanceTimersByTime(10_000);
    expect(store.data()).toBe('first');
    expect(store.error()).toBeInstanceOf(Error);
  });

  it('does not cancel a slow request on the next tick', () => {
    const pending = new Subject<string>();
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return calls === 1 ? pending.asObservable() : of('should-not-run');
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    pending.next('done');
    pending.complete();
    expect(store.data()).toBe('done');
  });

  it('stops the refresh while the tab is hidden and runs once when it shows again', () => {
    let calls = 0;
    TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(calls).toBe(2);
  });

  it('stops the refresh while the focus is inside a tbody, and starts again on focusout', () => {
    const button = addTbodyWithButton();
    let calls = 0;
    TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    button.focus();
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    button.blur();
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(2);
  });

  it('stops the refresh while paused is true', () => {
    let calls = 0;
    const store = TestBed.runInInjectionContext(() =>
      createPollStore(() => {
        calls++;
        return of(`v${calls}`);
      }),
    );
    flushHealth(10);
    vi.advanceTimersByTime(0);
    expect(calls).toBe(1);

    store.paused.set(true);
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(1);

    store.paused.set(false);
    vi.advanceTimersByTime(10_000);
    expect(calls).toBe(2);
  });
});
