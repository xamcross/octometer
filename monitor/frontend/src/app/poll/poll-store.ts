import { HttpClient } from '@angular/common/http';
import { DestroyRef, Signal, WritableSignal, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Observable,
  catchError,
  exhaustMap,
  filter,
  fromEvent,
  map,
  merge,
  of,
  switchMap,
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
  /** The error of the last failed request. A good request clears it. */
  readonly error: Signal<unknown>;
  /** True while a caller pauses the refresh. The "Pause refresh" button writes this signal. */
  readonly paused: WritableSignal<boolean>;
}

/** The interval to use when the health request fails. */
const FALLBACK_INTERVAL_MS = 60_000;

/** Returns true while the document focus is inside a table body. */
function isFocusInTbody(): boolean {
  return document.activeElement?.closest('tbody') !== null;
}

/**
 * Creates one poll store. Call this function inside an injection context,
 * for example a component field or a component constructor.
 *
 * The store polls with `request` on a `timer(0, ms)`, where `ms` comes from
 * `refreshSeconds` of `GET /api/health`. `exhaustMap` keeps a slow request
 * alive; it drops a tick that arrives before the answer. A failed request
 * keeps the old `data` and sets `error`. The refresh stops while the tab is
 * hidden, while the focus is inside a `<tbody>`, or while `paused` is true.
 * The refresh runs one time as soon as the tab shows again.
 */
export function createPollStore<T>(request: () => Observable<T>): PollStore<T> {
  const http = inject(HttpClient);
  const destroyRef = inject(DestroyRef);

  const data = signal<T | undefined>(undefined);
  const lastSuccessAt = signal<Date | undefined>(undefined);
  const error = signal<unknown>(undefined);
  const paused = signal(false);

  const tabShowsAgain$ = fromEvent(document, 'visibilitychange').pipe(
    filter(() => !document.hidden),
  );

  const canPoll = (): boolean => !paused() && !document.hidden && !isFocusInTbody();

  const intervalMs$ = http.get<HealthResponse>('/api/health').pipe(
    map((health) => health.refreshSeconds * 1000),
    catchError(() => of(FALLBACK_INTERVAL_MS)),
  );

  intervalMs$
    .pipe(
      switchMap((ms) => merge(timer(0, ms), tabShowsAgain$)),
      filter(() => canPoll()),
      exhaustMap(() =>
        request().pipe(
          map((value) => ({ ok: true as const, value })),
          catchError((requestError: unknown) => of({ ok: false as const, requestError })),
        ),
      ),
      takeUntilDestroyed(destroyRef),
    )
    .subscribe((result) => {
      if (result.ok) {
        data.set(result.value);
        lastSuccessAt.set(new Date());
        error.set(undefined);
      } else {
        error.set(result.requestError);
      }
    });

  return { data, lastSuccessAt, error, paused };
}
