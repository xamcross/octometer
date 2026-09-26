import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RefreshIntervalState } from './refresh-interval-state';

describe('RefreshIntervalState', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  it('starts with refreshSeconds undefined', () => {
    const state = TestBed.inject(RefreshIntervalState);
    expect(state.refreshSeconds()).toBeUndefined();
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
  });

  it('retries the health route each 5 seconds until it gets a valid answer, then it stops', () => {
    const state = TestBed.inject(RefreshIntervalState);

    vi.advanceTimersByTime(0);
    httpMock
      .expectOne('/api/health')
      .flush('unavailable', { status: 503, statusText: 'Service Unavailable' });
    expect(state.error()).toBeDefined();
    expect(state.refreshSeconds()).toBeUndefined();

    vi.advanceTimersByTime(5_000);
    httpMock
      .expectOne('/api/health')
      .flush('unavailable', { status: 503, statusText: 'Service Unavailable' });
    expect(state.error()).toBeDefined();

    vi.advanceTimersByTime(5_000);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    expect(state.error()).toBeUndefined();
    expect(state.refreshSeconds()).toBe(10);

    // The pipe stops after the first valid answer. A later tick sends no
    // further request (this is the defect that BLOCKER 1 named).
    vi.advanceTimersByTime(30_000);
    httpMock.expectNone('/api/health');
  });

  it('rejects a refreshSeconds value that is not a number above 0, and uses the fallback', () => {
    const state = TestBed.inject(RefreshIntervalState);

    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 0 });

    expect(state.refreshSeconds()).toBe(60);
  });

  it('gives the same instance to each caller, so the app sends one health request', () => {
    const first = TestBed.inject(RefreshIntervalState);
    const second = TestBed.inject(RefreshIntervalState);

    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });

    expect(second.refreshSeconds()).toBe(10);
    expect(second).toBe(first);
  });

  it('replays the cached ms value to a late subscriber, with no new health request', () => {
    const state = TestBed.inject(RefreshIntervalState);
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });

    // A poll store built later subscribes to intervalMs$ on its own. It
    // must read the cached value at once, with no new health request.
    let lateMs: number | undefined;
    state.intervalMs$.subscribe((ms) => (lateMs = ms));

    expect(lateMs).toBe(10_000);
    httpMock.expectNone('/api/health');
  });

  it('carries a data-poll error through setDataError, and clears it again (issue #164)', () => {
    const state = TestBed.inject(RefreshIntervalState);
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    expect(state.error()).toBeUndefined();

    const dataError = new Error('boom');
    state.setDataError(dataError);
    expect(state.error()).toBe(dataError);

    state.setDataError(undefined);
    expect(state.error()).toBeUndefined();
  });

  it('shows the health error first, so a data-poll error waits for the health route to answer', () => {
    const state = TestBed.inject(RefreshIntervalState);
    vi.advanceTimersByTime(0);
    httpMock
      .expectOne('/api/health')
      .flush('unavailable', { status: 503, statusText: 'Service Unavailable' });

    state.setDataError(new Error('boom'));
    expect(state.error()).toBeDefined();

    vi.advanceTimersByTime(5_000);
    httpMock.expectOne('/api/health').flush({ refreshSeconds: 10 });
    // The data-poll error still stands: setDataError alone clears it.
    expect(state.error()).toBeInstanceOf(Error);
  });
});
