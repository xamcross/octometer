import { Component, computed, input } from '@angular/core';

import { RefreshBar } from '../refresh-bar/refresh-bar';

/**
 * Placeholder view for the /apps/:appId/elements route.
 * A later issue adds the real element table (D28, level 3), and keeps the
 * refresh bar between the heading and the table (#92).
 * The heading names the same user as the last breadcrumb entry.
 */
@Component({
  selector: 'app-elements',
  imports: [RefreshBar],
  templateUrl: './elements.html',
})
export class Elements {
  /** The `userId` query parameter, bound by the router. Null for an anonymous user. */
  readonly userId = input<string | null>(null);

  /** The `anonymous` query parameter, bound by the router. */
  readonly anonymous = input<string | null>(null);

  /** The heading text. It names the same user as the last breadcrumb entry. */
  protected readonly heading = computed(() => {
    if (this.anonymous() === 'true') {
      return 'Elements of Anonymous';
    }
    const userId = this.userId();
    return userId ? `Elements of User ${userId}` : 'Elements';
  });
}
