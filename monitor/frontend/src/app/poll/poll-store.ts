import { DestroyRef, Signal, WritableSignal, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Observable,
  Subject,
  auditTime,
  catchError,
  distinctUntilChanged,
  exhaustMap,
  fromEvent,
  map,
  merge,
  of,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

import { RefreshIntervalState } from './refresh-interval-state';
import { RefreshPauseState } from './refresh-pause-state';

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

/** Returns true while the document focus sits inside a table body. */
function isFocusInTbody(): boolean {
  return document.activeElement?.closest('tbody') != null;
}

/**
 * Creates one poll store. Call this function inside an injection context,
 * for example a component field or a component constructor.
 *
 * The store reads the interval from the one root-level
 * `RefreshIntervalState`, in `refreshSeconds` of `GET /api/health`. That
 * service tries the health route again until it gets a valid interval,
 * then it stops. A failed health request goes to the `error` signal,
 * until a good answer arrives.
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
 *
 * `paused` reads and writes the one root-level `RefreshPauseState`. Each
 * poll store then shares one paused state, so the state stays the same
 * after a navigation that destroys one store and creates another.
 */
export function createPollStore<T>(request: () => Observable<T>): PollStore<T> {
  const destroyRef = inject(DestroyRef);
  const intervalState = inject(RefreshIntervalState);

  const data = signal<T | undefined>(undefined);
  const lastSuccessAt = signal<Date | undefined>(undefined);
  const dataError = signal<unknown>(undefined);
  const paused = inject(RefreshPauseState).paused;
  const firstLoadPending = signal(true);
  const manualRefresh$ = new Subject<void>();

  /**
   * The error of the last failed request. It reads the health error
   * first, so a health failure shows before the store sends one data
   * request. It reads the data error once the health route answers.
   */
  const error = computed(() => intervalState.error() ?? dataError());

  const canPoll = (): boolean => !paused() && !document.hidden && !isFocusInTbody();

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
  const scheduledTicks$ = intervalState.intervalMs$.pipe(
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
        dataError.set(undefined);
      } else {
        dataError.set(result.requestError);
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
