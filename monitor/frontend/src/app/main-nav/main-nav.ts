import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';

/**
 * Shows the main navigation of the shell, with the links "Apps" and "Manage apps".
 * The label "Main" makes this nav different from the nav "Breadcrumb" (D33).
 *
 * The Apps link keeps a visual current-section state on a user list and on an
 * element list, because each view belongs to the Apps section. `aria-current`
 * follows the WAI-ARIA 1.2 definition (section 6.6,
 * https://www.w3.org/TR/wai-aria-1.2/#aria-current): the token `page` names
 * the exact current page, and the token `true` names the current item of a
 * set when no more specific token applies. The exact `/apps` view gets
 * `page`. A user list or an element list is a different page, so it gets
 * `true`: the visual state then reaches a screen reader too, not only a
 * sighted user.
 */
@Component({
  selector: 'app-main-nav',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './main-nav.html',
  styleUrl: './main-nav.css',
})
export class MainNav {
  private readonly router = inject(Router);

  private readonly currentPath = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects.split(/[?#]/)[0]),
    ),
    { initialValue: this.router.url.split(/[?#]/)[0] },
  );

  /** 'page' on the exact Apps view, 'true' on a view below it, null elsewhere. */
  protected readonly appsCurrent = computed<'page' | 'true' | null>(() => {
    const path = this.currentPath();
    if (path === '/apps') {
      return 'page';
    }
    return path.startsWith('/apps/') ? 'true' : null;
  });
}
