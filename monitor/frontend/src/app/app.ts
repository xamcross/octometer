import { Component, DOCUMENT, Injector, afterNextRender, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { Announcer } from './announcer';
import { Banner } from './banner/banner';
import type { BreadcrumbItem } from './breadcrumb';
import { buildBreadcrumb } from './breadcrumb';
import { BreadcrumbNav } from './breadcrumb-nav/breadcrumb-nav';

/**
 * Root shell of the app.
 * It holds the breadcrumb, the banner, the status region, and the router outlet.
 */
@Component({
  imports: [RouterOutlet, Banner, BreadcrumbNav],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  /** The status text of the one permanent status region (D33). */
  protected readonly announcer = inject(Announcer);

  /** True once the router outlet activates its first view component. */
  protected readonly appReady = signal(false);

  /** The error text of the monitor API. A later issue reads this from the poll store. */
  protected readonly monitorApiError = signal<string | null>(null);

  /** The breadcrumb trail of the active route. */
  protected readonly breadcrumbItems = signal<BreadcrumbItem[]>([]);

  constructor() {
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe(() => {
        this.breadcrumbItems.set(buildBreadcrumb(this.router.routerState.snapshot.root));
      });
  }

  /** Runs when the router outlet activates a view component. */
  protected onOutletActivated(): void {
    this.appReady.set(true);
    afterNextRender(() => this.focusHeading(), { injector: this.injector });
  }

  private focusHeading(): void {
    const heading = this.document.querySelector<HTMLElement>('h1[tabindex="-1"]');
    heading?.focus();
  }
}
