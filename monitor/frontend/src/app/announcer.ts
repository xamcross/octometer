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

  /** Sets the status text after a user action. */
  announce(message: string): void {
    this.text.set(message);
  }
}
