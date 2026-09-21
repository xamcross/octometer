import { Component, computed, inject } from '@angular/core';

import { Announcer } from '../announcer';
import { PauseRefreshButton } from '../poll/pause-refresh-button';
import { RefreshIntervalState } from '../poll/refresh-interval-state';
import { RefreshPauseState } from '../poll/refresh-pause-state';

/**
 * The refresh bar of a table view (D28, D33). It shows the button "Pause
 * refresh" of #25, and the interval text next to it. It stands directly
 * after the `<h1>` of the view, and before the table content.
 *
 * The text follows the paused state and the `refreshSeconds` value of the
 * one root-level `RefreshIntervalState`. Before the health answer
 * arrives, the text says that the app reads the interval. The text is
 * not a live region.
 *
 * Each press writes one message to the one `role="status"` region of the
 * app shell, through the Announcer. `RefreshBar` adds no live region of
 * its own.
 *
 * The paused state comes from `RefreshPauseState`, one root-level
 * service. It then stays the same after a navigation between the table
 * routes.
 */
@Component({
  selector: 'app-refresh-bar',
  imports: [PauseRefreshButton],
  styleUrl: './refresh-bar.css',
  templateUrl: './refresh-bar.html',
})
export class RefreshBar {
  private readonly announcer = inject(Announcer);
  protected readonly pauseState = inject(RefreshPauseState);
  private readonly intervalState = inject(RefreshIntervalState);

  /**
   * The interval text. It shows the paused text first, then the interval
   * text, then a text that names no interval yet.
   */
  protected readonly intervalText = computed(() => {
    if (this.pauseState.paused()) {
      return 'The refresh is paused.';
    }
    const seconds = this.intervalState.refreshSeconds();
    if (seconds === undefined) {
      return 'The app reads the refresh interval.';
    }
    return seconds === 1
      ? 'The table refreshes each 1 second.'
      : `The table refreshes each ${seconds} seconds.`;
  });

  /** Sets the paused state, and writes one message to the status region. */
  protected onToggle(paused: boolean): void {
    this.pauseState.paused.set(paused);
    this.announcer.announce(paused ? 'Refresh paused' : 'Refresh resumed');
  }
}
