import { HttpClient } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, exhaustMap, filter, map, of, timer } from 'rxjs';

import { Announcer } from '../announcer';
import { PauseRefreshButton } from '../poll/pause-refresh-button';
import { RefreshPauseState } from '../poll/refresh-pause-state';

/** The part of the health response that the bar reads. */
interface HealthResponse {
  refreshSeconds: number;
}

/** The wait between one failed health request and the next try. */
const HEALTH_RETRY_MS = 5_000;

/** Returns true only for a finite number above 0. */
function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/**
 * The refresh bar of a table view (D28, D33). It shows the button "Pause
 * refresh" of #25, and the interval text next to it. It stands directly
 * after the `<h1>` of the view, and before the table content.
 *
 * The text follows the paused state and the `refreshSeconds` value of
 * `GET /api/health`. Before the health answer arrives, the text says that
 * the app reads the interval. The text is not a live region.
 *
 * Each press writes one message to the one `role="status"` region of the
 * app shell, through the Announcer. `RefreshBar` adds no live region of
 * its own.
 *
 * The paused state comes from `RefreshPauseState`, one root-level service.
 * It then stays the same after a navigation between the table routes.
 */
@Component({
  selector: 'app-refresh-bar',
  imports: [PauseRefreshButton],
  templateUrl: './refresh-bar.html',
})
export class RefreshBar {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);
  protected readonly pauseState = inject(RefreshPauseState);

  /** The refreshSeconds value of GET /api/health. Undefined before the first good answer. */
  private readonly refreshSeconds = toSignal(
    timer(0, HEALTH_RETRY_MS).pipe(
      exhaustMap(() =>
        this.http.get<HealthResponse>('/api/health').pipe(
          map((health) =>
            isPositiveNumber(health.refreshSeconds) ? health.refreshSeconds : undefined,
          ),
          catchError(() => of(undefined)),
        ),
      ),
      filter((seconds): seconds is number => seconds !== undefined),
    ),
    { initialValue: undefined },
  );

  /**
   * The interval text. It shows the paused text first, then the interval
   * text, then a text that names no interval yet.
   */
  protected readonly intervalText = computed(() => {
    if (this.pauseState.paused()) {
      return 'The refresh is paused.';
    }
    const seconds = this.refreshSeconds();
    return seconds === undefined
      ? 'The app reads the refresh interval.'
      : `The table refreshes each ${seconds} seconds.`;
  });

  /** Sets the paused state, and writes one message to the status region. */
  protected onToggle(paused: boolean): void {
    this.pauseState.paused.set(paused);
    this.announcer.announce(paused ? 'Refresh paused' : 'Refresh resumed');
  }
}
