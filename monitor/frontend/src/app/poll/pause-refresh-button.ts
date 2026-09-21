import { Component, model } from '@angular/core';

/**
 * The "Pause refresh" button (WCAG 2.2.2). Bind `pressed` to a poll store
 * `paused` signal with `[(pressed)]="store.paused"`.
 */
@Component({
  selector: 'app-pause-refresh-button',
  templateUrl: './pause-refresh-button.html',
})
export class PauseRefreshButton {
  /** True while the refresh is paused. */
  readonly pressed = model(false);

  /** Toggles the paused state. */
  toggle(): void {
    this.pressed.set(!this.pressed());
  }
}
