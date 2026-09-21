import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/** The wait between the clear step and the write step of `announce()`. */
const ANNOUNCE_DELAY_MS = 100;

/**
 * Holds the text of the one permanent status region (D33).
 * A user action sets the text. One transition of the monitor connection also sets the text.
 * A navigation does not set the text.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  private readonly text = signal('');
  private pending: ReturnType<typeof setTimeout> | null = null;

  /** The current status text. Empty when no user action set it. */
  readonly message = this.text.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancelPending());
  }

  /**
   * Sets the status text after a user action or a monitor-connection transition.
   * It cancels a waiting timer of an earlier call, then clears the text, then writes
   * the new text on a later turn. This step gives a screen reader a fresh text each
   * time, even a repeated text.
   */
  announce(message: string): void {
    this.cancelPending();
    this.text.set('');
    this.pending = setTimeout(() => {
      this.pending = null;
      this.text.set(message);
    }, ANNOUNCE_DELAY_MS);
  }

  private cancelPending(): void {
    if (this.pending !== null) {
      clearTimeout(this.pending);
      this.pending = null;
    }
  }
}
