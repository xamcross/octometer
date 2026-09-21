import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { BreadcrumbItem } from '../breadcrumb';

/** Shows the breadcrumb trail of the active route (D28). */
@Component({
  selector: 'app-breadcrumb-nav',
  imports: [RouterLink],
  templateUrl: './breadcrumb-nav.html',
})
export class BreadcrumbNav {
  /** The breadcrumb trail, root first. The last entry is the current page. */
  items = input<BreadcrumbItem[]>([]);
}
