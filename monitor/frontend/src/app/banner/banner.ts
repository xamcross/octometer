import { Component, input } from '@angular/core';

/**
 * Shows the banner for a failed monitor API call (D30).
 * The slot keeps no reserved height; the error text overlays the page
 * below it, so no view shifts when the banner opens or closes (issue #375).
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
