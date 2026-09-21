import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';

/**
 * Shows the main navigation of the shell, with the links "Apps" and "Manage apps".
 * The label "Main" makes this nav different from the nav "Breadcrumb" (D33).
 *
 * The Apps link keeps a visual current-section state on a user list and on an
 * element list, because each view belongs to the Apps section. `aria-current="page"`
 * marks only the exact page that a link opens, per the WAI-ARIA definition of the
 * token `page`: "the current page ... in a set of pages, such as ... a breadcrumb
 * trail" (WAI-ARIA 1.2, section 6.6, https://www.w3.org/TR/wai-aria-1.2/#aria-current).
 * A user list or an element list is a different page, so the Apps link keeps
 * `aria-current` unset there, and shows only the visual state.
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
      map((event) => event.urlAfterRedirects.split('?')[0]),
    ),
    { initialValue: this.router.url.split('?')[0] },
  );

  /** True on the Apps view, and on each view below it (a user list or an element list). */
  protected readonly appsSectionActive = computed(() => {
    const path = this.currentPath();
    return path === '/apps' || path.startsWith('/apps/');
  });
}
