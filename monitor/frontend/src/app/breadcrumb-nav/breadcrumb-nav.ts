import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { BreadcrumbItem } from '../breadcrumb';

/**
 * Shows the breadcrumb trail of the active route (D28). The nav stays out
 * of the DOM when the trail holds one item with no path: a top-level page
 * where the breadcrumb would only repeat the heading (issue #346).
 */
@Component({
  selector: 'app-breadcrumb-nav',
  imports: [RouterLink],
  templateUrl: './breadcrumb-nav.html',
  styleUrl: './breadcrumb-nav.css',
})
export class BreadcrumbNav {
  /** The breadcrumb trail, root first. The last entry is the current page. */
  items = input<BreadcrumbItem[]>([]);

  /** False for the one top-level entry that repeats the page title. */
  protected readonly showNav = computed(() => {
    const list = this.items();
    return !(list.length === 1 && list[0].path === null);
  });
}
