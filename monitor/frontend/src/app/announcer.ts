import { Injectable, signal } from '@angular/core';

/**
 * Holds the text of the one permanent status region (D33).
 * A user action sets the text. A poll or a navigation does not.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  private readonly text = signal('');

  /** The current status text. Empty when no user action set it. */
  readonly message = this.text.asReadonly();

  /**
   * Sets the status text after a user action.
   * It clears the text first, then writes the text on the next turn.
   * This step gives a screen reader a fresh text each time, even a repeated text.
   */
  announce(message: string): void {
    this.text.set('');
    setTimeout(() => this.text.set(message), 100);
  }

  /** Clears the status text. */
  clear(): void {
    this.text.set('');
  }
}
