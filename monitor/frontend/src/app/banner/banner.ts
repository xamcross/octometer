import { Component, input } from '@angular/core';

/**
 * Shows the banner for a failed monitor API call (D30).
 * The slot keeps a reserved height, so the error text causes no layout shift.
 * A later issue sets `apiError` from the poll store.
 */
@Component({
  selector: 'app-banner',
  templateUrl: './banner.html',
  styleUrl: './banner.css',
})
export class Banner {
  /** The reserved height of the banner slot, in CSS pixels. */
  protected readonly reservedHeight = 48;

  /** The error text. Empty when the monitor API answers each request. */
  apiError = input<string | null>(null);
}
