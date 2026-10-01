import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  BehaviorSubject,
  Observable,
  catchError,
  exhaustMap,
  filter,
  map,
  of,
  shareReplay,
  switchMap,
  take,
  timer,
} from 'rxjs';

/** The part of the health response that the service reads. */
interface HealthResponse {
  refreshSeconds: number;
  retentionDays: number;
}

/** The interval to use when the health response holds no valid refreshSeconds. */
const FALLBACK_INTERVAL_MS = 60_000;

/** The wait between one failed health request and the next try. */
const HEALTH_RETRY_MS = 5_000;

/** Returns true only for a finite number above 0. */
function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/** Returns true only for a positive integer. */
function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/**
 * Holds the one refresh interval of `GET /api/health` (D29). The service
 * lives at the root, so each poll store and the refresh bar read one
 * answer. The app then sends one health request for each session, and
 * not one health request for each view.
 *
 * The service tries the health route again each 5 seconds until it gets
 * a valid answer. It then stops, and it gives its cached answer to a
 * later caller, with no new request.
 *
 * `error` also carries a later failed data poll of the active view
 * (issue #164). A poll store calls `setDataError` once it has its own
 * first answer, good or bad. `scheduleDataErrorClear` and
 * `cancelDataErrorClear` stop a route change between two table views
 * from showing a false recovery (MAJOR 1 of pull request #194).
 */
@Injectable({ providedIn: 'root' })
export class RefreshIntervalState {
  private readonly http = inject(HttpClient);
  private readonly errorState = signal<unknown>(undefined);
  private readonly dataErrorState = signal<unknown>(undefined);
  private readonly retentionDaysState = signal<number | null>(null);

  /**
   * The start gate of the health poll (issue #301, brief review B4). It
   * stays false until [allowStart] runs. The root `App` component calls
   * [allowStart] once `Me.ready` turns true, so the first `GET
   * /api/health` waits for one good answer of `GET /api/me`. A test that
   * builds a poll store or a view component with no `App` shell calls
   * [allowStart] itself, in its own `beforeEach`.
   *
   * A plain `BehaviorSubject`, not a signal: [allowStart] then reaches
   * [intervalMs$] at once, with no Angular change-detection tick between
   * the call and the request. A signal needs that tick (through
   * `toObservable`), and a service-only test builds no component and
   * runs no tick of its own.
   */
  private readonly startGate$ = new BehaviorSubject(false);

  /**
   * The retention length in days from `GET /api/health` (D13, issue #66).
   * Null until the first good answer arrives, and null when the field is
   * absent or not a positive integer. The service reads this from the one
   * health request of the session (D29); it sends no second request.
   */
  readonly retentionDays: Signal<number | null> = this.retentionDaysState.asReadonly();

  /**
   * The error of the last failed health request, or of the last failed
   * data poll of the active view. Undefined once both answer well.
   */
  readonly error = computed(() => this.errorState() ?? this.dataErrorState());

  /**
   * The interval in ms. It tries the health route again each 5 seconds
   * until it gets a valid answer, then it stops. `shareReplay` gives the
   * cached answer to a later subscriber, and it sends no new request.
   *
   * Issue #301, brief review B4: `/api/health` needs a session, and the
   * poll of this observable sends no request before [allowStart] runs
   * (the [startGate] signal). A visitor that has not signed in yet
   * then sends no `/api/health` request at all, on `/signin` or on any
   * other route.
   */
  readonly intervalMs$: Observable<number> = this.startGate$.pipe(
    filter((started) => started),
    take(1),
    switchMap(() =>
      timer(0, HEALTH_RETRY_MS).pipe(
        exhaustMap(() =>
          this.http.get<HealthResponse>('/api/health').pipe(
            map((health) => {
              this.errorState.set(undefined);
              this.retentionDaysState.set(
                isPositiveInteger(health.retentionDays) ? health.retentionDays : null,
              );
              return isPositiveNumber(health.refreshSeconds)
                ? health.refreshSeconds * 1000
                : FALLBACK_INTERVAL_MS;
            }),
            catchError((healthError: unknown) => {
              if (!(healthError instanceof HttpErrorResponse && healthError.status === 401)) {
                this.errorState.set(healthError);
              }
              return of(undefined);
            }),
          ),
        ),
        filter((ms): ms is number => ms !== undefined),
        take(1),
      ),
    ),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  /**
   * Opens the [startGate$]. The first call starts the health poll of
   * [intervalMs$]; a later call does nothing (`take(1)` above has taken
   * its one value already).
   */
  allowStart(): void {
    this.startGate$.next(true);
  }

  /** The interval in seconds. Undefined before the first good answer. */
  readonly refreshSeconds: Signal<number | undefined> = toSignal(
    this.intervalMs$.pipe(map((ms) => ms / 1000)),
    { initialValue: undefined },
  );

  /** The token of the pending clear that `scheduleDataErrorClear` queued last, or 0. */
  private dataErrorClearToken = 0;

  /**
   * Sets the shared error from the data poll of the active view (issue
   * #164). A poll store calls this once it has its own first answer,
   * good or bad. A fresh store then never overwrites the error of an
   * old one before it answers (MAJOR 1 of pull request #194).
   */
  setDataError(error: unknown): void {
    this.dataErrorClearToken++;
    this.dataErrorState.set(error);
  }

  /**
   * Schedules the clear of the data-poll error, one microtask after a
   * poll store's own destroy. `cancelDataErrorClear` can cancel a clear
   * still pending. A route change between two table views then keeps
   * the old error, until the new store has its own first answer (MAJOR
   * 1).
   */
  scheduleDataErrorClear(): void {
    const token = ++this.dataErrorClearToken;
    queueMicrotask(() => {
      if (this.dataErrorClearToken === token) {
        this.dataErrorState.set(undefined);
      }
    });
  }

  /** Cancels a clear that `scheduleDataErrorClear` queued, still pending. */
  cancelDataErrorClear(): void {
    this.dataErrorClearToken++;
  }
}
