import { HttpClient } from '@angular/common/http';
import { DestroyRef, Signal, WritableSignal, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Observable,
  Subject,
  auditTime,
  catchError,
  distinctUntilChanged,
  exhaustMap,
  filter,
  fromEvent,
  map,
  merge,
  of,
  startWith,
  switchMap,
  take,
  timer,
} from 'rxjs';

/** The part of the health response that the store reads. */
interface HealthResponse {
  refreshSeconds: number;
}

/** The signals and the controls of one poll store. */
export interface PollStore<T> {
  /** The last good value. A failed request keeps the old value. */
  readonly data: Signal<T | undefined>;
  /** The time of the last good request. */
  readonly lastSuccessAt: Signal<Date | undefined>;
  /** The error of the last failed request, or of a failed health request. */
  readonly error: Signal<unknown>;
  /** True while the user pauses the refresh. The "Pause refresh" button writes this signal. */
  readonly paused: WritableSignal<boolean>;
  /** True until the store gets its first answer, good or bad. */
  readonly firstLoadPending: Signal<boolean>;
  /**
   * Sends one request now. A caller uses this after it changes data on the
   * server. Call `refresh()` only from a user action. A pause must stop
   * each automatic update.
   */
  refresh(): void;
}

/** The interval to use when the health response holds no valid refreshSeconds. */
const FALLBACK_INTERVAL_MS = 60_000;

/** The wait between one failed health request and the next try. */
const HEALTH_RETRY_MS = 5_000;

/** Returns true only for a finite number above 0. */
function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/** Returns true while the document focus sits inside a table body. */
function isFocusInTbody(): boolean {
  return document.activeElement?.closest('tbody') != null;
}

/**
 * Creates one poll store. Call this function inside an injection context,
 * for example a component field or a component constructor.
 *
 * The store reads `refreshSeconds` from `GET /api/health`. It tries the
 * health route again at each tick until it gets a valid interval. A failed
 * health request goes to the `error` signal.
 *
 * The store then polls `request` with `timer(0, ms)` and `exhaustMap`.
 * `exhaustMap` does not cancel a slow request. A failed request keeps the
 * old `data`, and it sets `error`.
 *
 * The store sends no data poll while the health route fails. Issue #20
 * must show that state to the user.
 *
 * The refresh stops while the tab is hidden, while the focus sits inside a
 * `<tbody>`, or while `paused` is true. As soon as one condition clears,
 * the store sends one request at once. It then goes back to the interval.
 */
export function createPollStore<T>(request: () => Observable<T>): PollStore<T> {
  const http = inject(HttpClient);
  const destroyRef = inject(DestroyRef);

  const data = signal<T | undefined>(undefined);
  const lastSuccessAt = signal<Date | undefined>(undefined);
  const error = signal<unknown>(undefined);
  const paused = signal(false);
  const firstLoadPending = signal(true);
  const manualRefresh$ = new Subject<void>();

  const canPoll = (): boolean => !paused() && !document.hidden && !isFocusInTbody();

  /** Tries GET /api/health at each tick until it gives a valid refreshSeconds. */
  const intervalMs$ = timer(0, HEALTH_RETRY_MS).pipe(
    exhaustMap(() =>
      http.get<HealthResponse>('/api/health').pipe(
        map((health) => {
          error.set(undefined);
          return isPositiveNumber(health.refreshSeconds)
            ? health.refreshSeconds * 1000
            : FALLBACK_INTERVAL_MS;
        }),
        catchError((healthError: unknown) => {
          error.set(healthError);
          return of(undefined);
        }),
      ),
    ),
    filter((ms): ms is number => ms !== undefined),
    take(1),
  );

  /**
   * Gives the current value of `canPoll()` after each event that can change
   * a stop condition: a visibility change, a focus change, or a pause
   * change. `auditTime(0)` waits one tick, so the browser moves the focus
   * first. `startWith(null)` stands before `map`, so the first value reads
   * `canPoll()` at the subscription, and not at the pipe build.
   */
  const gateOpen$ = merge(
    fromEvent(document, 'visibilitychange'),
    fromEvent(document, 'focusin'),
    fromEvent(document, 'focusout'),
    toObservable(paused),
  ).pipe(
    auditTime(0),
    startWith(null),
    map(() => canPoll()),
    distinctUntilChanged(),
  );

  /**
   * Ticks with `timer(0, ms)` while the gate is open. It stops the ticks at
   * once when the gate closes. It starts a fresh `timer(0, ms)` when the
   * gate opens again, so the store sends one request at once, and it never
   * fires a tick that a closed gate made stale.
   */
  const scheduledTicks$ = intervalMs$.pipe(
    switchMap((ms) => gateOpen$.pipe(switchMap((open) => (open ? timer(0, ms) : EMPTY)))),
  );

  merge(scheduledTicks$, manualRefresh$)
    .pipe(
      exhaustMap(() =>
        request().pipe(
          map((value) => ({ ok: true as const, value })),
          catchError((requestError: unknown) => of({ ok: false as const, requestError })),
        ),
      ),
      takeUntilDestroyed(destroyRef),
    )
    .subscribe((result) => {
      firstLoadPending.set(false);
      if (result.ok) {
        data.set(result.value);
        lastSuccessAt.set(new Date());
        error.set(undefined);
      } else {
        error.set(result.requestError);
      }
    });

  return {
    data,
    lastSuccessAt,
    error,
    paused,
    firstLoadPending,
    refresh: () => manualRefresh$.next(),
  };
}
