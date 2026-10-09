import { Component, input } from '@angular/core';

/**
 * Shows the banner for a failed monitor API call (D30).
 * The slot keeps no reserved height when it is empty (issue #375). The
 * error text sits in the normal flow and pushes the content below it
 * down by its own height (the correction round of pull request #404).
 * The banner is plain text. The one status region of the shell announces the transition.
 * The shell (`app.ts`) sets `apiError` from `RefreshIntervalState`.
 */
@Component({
  selector: 'app-banner',
  templateUrl: './banner.html',
  styleUrl: './banner.css',
})
export class Banner {
  /** The error text. Null when the monitor API answers each request. */
  apiError = input<string | null>(null);
}
