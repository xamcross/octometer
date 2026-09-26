import {
  DestroyRef,
  Signal,
  WritableSignal,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Observable,
  Subject,
  Subscription,
  auditTime,
  catchError,
  distinctUntilChanged,
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
  /**
   * The error of the last failed data request alone. Undefined before the
   * first data request, and undefined while the health request fails (a
   * caller reads `error` for that state, not `dataError`).
   */
  readonly dataError: Signal<unknown>;
  /** True while the user pauses the refresh. The "Pause refresh" button writes this signal. */
  readonly paused: WritableSignal<boolean>;
  /** True until the store gets its first answer, good or bad. */
  readonly firstLoadPending: Signal<boolean>;
  /**
   * Sends one request now. A caller uses this after it changes data on the
   * server. Call `refresh()` only from a user action. A pause must stop
   * each automatic update. A manual request cancels an earlier manual
   * request still in flight, and it does not wait for a scheduled request
   * in flight (correction round 1 of pull request #159, MAJOR 3).
   */
  refresh(): void;
  /**
   * Clears `data`, `dataError`, and `firstLoadPending`, and marks each
   * request in flight as stale. A stale request still fills its answer,
   * but the store then ignores that answer (correction round 1 of pull
   * request #159, MAJOR 2). A caller uses this before it points the store
   * at a different target, for example a different `appId` of one reused
   * component instance.
   */
  reset(): void;
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
 * The store polls `request` with `timer(0, ms)`. A scheduled tick that
 * arrives while a request is still in flight waits for the next tick, the
 * same way `exhaustMap` does: it does not cancel a slow request, and it
 * drops a tick that a slow request made stale. A failed request keeps the
 * old `data`, and it sets `error`.
 *
 * A call of `refresh()` never waits: it starts its own request at once,
 * and it cancels a request already in flight, of either kind (correction
 * round 1 of pull request #159, MAJOR 3). A caller of `reset()` also
 * raises the generation of the store, so an answer of a request that
 * started before the reset cannot reach `data` (MAJOR 2).
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
   * The generation of the current target. `reset()` raises it. A request
   * started under an older generation carries that generation with its
   * answer, so the subscriber below can tell a stale answer from a fresh
   * one, and ignore the stale one (correction round 1 of pull request
   * #159, MAJOR 2).
   */
  let generation = 0;

  /**
   * The error of the last failed request. It reads the health error
   * first, so a health failure shows before the store sends one data
   * request. It reads the data error once the health route answers.
   */
  const error = computed(() => intervalState.error() ?? dataError());

  /**
   * Cancels a clear that an old store's destroy scheduled, still
   * pending in the same task (MAJOR 1 of the review of pull request
   * #194). A route change between two table views then keeps the old
   * error, until this store has its own first answer.
   */
  intervalState.cancelDataErrorClear();

  /**
   * Routes each change of `dataError` into the shared error state of
   * `RefreshIntervalState`, once this store has its own first answer
   * (issue #164, MAJOR 1). The guard stops a fresh store from
   * overwriting the error of an old one before it answers.
   */
  effect(() => {
    if (firstLoadPending()) {
      return;
    }
    intervalState.setDataError(dataError());
  });
  destroyRef.onDestroy(() => intervalState.scheduleDataErrorClear());

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

  /**
   * The subscription of the request in flight, scheduled or manual. Null
   * while no request is in flight.
   */
  let activeRequest: Subscription | null = null;

  /**
   * Starts one request. It first cancels a request already in flight, of
   * either kind, so at most one request stays open at a time. A manual
   * action then always sends its own request at once (MAJOR 3), and it
   * also cancels an old app id's request in flight, so a late answer of
   * that app can never reach `data` (MAJOR 2).
   */
  function startRequest(): void {
    activeRequest?.unsubscribe();
    const requestGeneration = generation;
    // A synchronous request (for example `of(value)` in a test) answers
    // before `subscribe()` below returns, so the callback needs its own
    // reference to this request, ready before that call. An empty
    // `Subscription` gives it one: the callback compares `activeRequest`
    // against `wrapper`, and it clears `activeRequest` correctly either
    // way, sync or async.
    const wrapper = new Subscription();
    activeRequest = wrapper;
    wrapper.add(
      request()
        .pipe(
          map((value) => ({ ok: true as const, value })),
          catchError((requestError: unknown) => of({ ok: false as const, requestError })),
          takeUntilDestroyed(destroyRef),
        )
        .subscribe((result) => {
          if (activeRequest === wrapper) {
            activeRequest = null;
          }
          // A reset between the start and the answer of this request
          // raises the generation. The answer then belongs to a target
          // the store no longer shows, so the store drops it here
          // (MAJOR 2).
          if (requestGeneration !== generation) {
            return;
          }
          firstLoadPending.set(false);
          if (result.ok) {
            data.set(result.value);
            lastSuccessAt.set(new Date());
            dataError.set(undefined);
          } else {
            dataError.set(result.requestError);
          }
        }),
    );
  }

  merge(
    scheduledTicks$.pipe(map(() => ({ manual: false }))),
    manualRefresh$.pipe(map(() => ({ manual: true }))),
  )
    .pipe(takeUntilDestroyed(destroyRef))
    .subscribe(({ manual }) => {
      // A scheduled tick that arrives while a request is in flight waits
      // for the next tick, the same way exhaustMap does (D29). A manual
      // action never waits: it starts its own request at once, and it
      // cancels a request already in flight.
      if (activeRequest !== null && !manual) {
        return;
      }
      startRequest();
    });

  return {
    data,
    lastSuccessAt,
    error,
    dataError,
    paused,
    firstLoadPending,
    refresh: () => manualRefresh$.next(),
    reset: () => {
      generation++;
      data.set(undefined);
      dataError.set(undefined);
      firstLoadPending.set(true);
    },
  };
}
