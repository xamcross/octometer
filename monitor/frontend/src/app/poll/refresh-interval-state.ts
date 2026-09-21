import { HttpClient } from '@angular/common/http';
import { Injectable, Signal, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  Observable,
  catchError,
  exhaustMap,
  filter,
  map,
  of,
  shareReplay,
  take,
  timer,
} from 'rxjs';

/** The part of the health response that the service reads. */
interface HealthResponse {
  refreshSeconds: number;
}

/** The interval to use when the health response holds no valid refreshSeconds. */
const FALLBACK_INTERVAL_MS = 60_000;

/** The wait between one failed health request and the next try. */
const HEALTH_RETRY_MS = 5_000;

/** Returns true only for a finite number above 0. */
function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
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
 */
@Injectable({ providedIn: 'root' })
export class RefreshIntervalState {
  private readonly http = inject(HttpClient);
  private readonly errorState = signal<unknown>(undefined);

  /** The error of the last failed health request. Undefined after a good answer. */
  readonly error = this.errorState.asReadonly();

  /**
   * The interval in ms. It tries the health route again each 5 seconds
   * until it gets a valid answer, then it stops. `shareReplay` gives the
   * cached answer to a later subscriber, and it sends no new request.
   */
  readonly intervalMs$: Observable<number> = timer(0, HEALTH_RETRY_MS).pipe(
    exhaustMap(() =>
      this.http.get<HealthResponse>('/api/health').pipe(
        map((health) => {
          this.errorState.set(undefined);
          return isPositiveNumber(health.refreshSeconds)
            ? health.refreshSeconds * 1000
            : FALLBACK_INTERVAL_MS;
        }),
        catchError((healthError: unknown) => {
          this.errorState.set(healthError);
          return of(undefined);
        }),
      ),
    ),
    filter((ms): ms is number => ms !== undefined),
    take(1),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  /** The interval in seconds. Undefined before the first good answer. */
  readonly refreshSeconds: Signal<number | undefined> = toSignal(
    this.intervalMs$.pipe(map((ms) => ms / 1000)),
    { initialValue: undefined },
  );
}
