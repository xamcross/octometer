import { Injectable, WritableSignal, signal } from '@angular/core';

/**
 * Holds the one paused state of the table refresh (D29).
 * The service lives at the root, so each poll store reads and writes the
 * same signal. The paused state stays the same after a navigation between
 * the table routes. A new poll store still reads this one signal, not a
 * fresh signal of its own.
 */
@Injectable({ providedIn: 'root' })
export class RefreshPauseState {
  /** True while the user pauses the refresh. */
  readonly paused: WritableSignal<boolean> = signal(false);
}
