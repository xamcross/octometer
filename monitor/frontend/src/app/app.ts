import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { Announcer } from './announcer';
import { Banner } from './banner/banner';
import type { BreadcrumbItem } from './breadcrumb';
import { buildBreadcrumb } from './breadcrumb';
import { BreadcrumbNav } from './breadcrumb-nav/breadcrumb-nav';
import { MainNav } from './main-nav/main-nav';
import { RefreshIntervalState } from './poll/refresh-interval-state';

/**
 * Root shell of the app.
 * It holds the skip link, the main nav, the breadcrumb, the banner, the status
 * region, and the router outlet.
 */
@Component({
  imports: [RouterOutlet, Banner, BreadcrumbNav, MainNav],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly hostElement: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly intervalState = inject(RefreshIntervalState);

  /** The status text of the one permanent status region (D33). */
  protected readonly announcer = inject(Announcer);

  /** True once the router outlet activates its first view component. */
  protected readonly appReady = signal(false);

  /**
   * The error text of the monitor API (D30): a failed `GET /api/health`.
   * Null while the health route answers. The banner shows this text.
   */
  protected readonly monitorApiError = computed(() =>
    this.intervalState.error() === undefined ? null : 'The app did not get the refresh interval.',
  );

  /** The breadcrumb trail of the active route. */
  protected readonly breadcrumbItems = signal<BreadcrumbItem[]>([]);

  /** The path part of the previous URL. Null before the first navigation ends. */
  private previousPath: string | null = null;

  /** True when the monitor API did not answer the last request. */
  private monitorApiFailed = false;

  constructor() {
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => this.onNavigationEnd(event));

    effect(() => this.announceMonitorApiTransition());
  }

  /** Runs when the router outlet activates a view component. */
  protected onOutletActivated(): void {
    this.appReady.set(true);
  }

  /**
   * Moves the focus to the main content on a skip-link activation.
   * The method stops the default action. `<base href="/">` makes a
   * fragment href point at the root page, not at the present page.
   */
  protected onSkipLinkActivated(event: Event): void {
    event.preventDefault();
    this.hostElement.nativeElement.querySelector<HTMLElement>('#main-content')?.focus();
  }

  /**
   * Updates the breadcrumb, and moves the focus to the `<h1>` of the new view.
   * The focus stays where it is on the first load, and on a change of a query
   * parameter alone. The focus moves on each later navigation that changes the path.
   */
  private onNavigationEnd(event: NavigationEnd): void {
    this.breadcrumbItems.set(buildBreadcrumb(this.router.routerState.snapshot.root));

    const path = event.urlAfterRedirects.split(/[?#]/)[0];
    const isFirstNavigation = this.previousPath === null;
    const pathChanged = path !== this.previousPath;
    this.previousPath = path;

    if (!isFirstNavigation && pathChanged) {
      afterNextRender(() => this.focusHeading(), { injector: this.injector });
    }
  }

  /**
   * Announces one time each transition of the monitor connection through the status region.
   * It compares the failed state, not the error text, so two different error texts of one
   * outage give one announcement.
   */
  private announceMonitorApiTransition(): void {
    const failed = this.monitorApiError() !== null;
    if (failed === this.monitorApiFailed) {
      return;
    }
    this.monitorApiFailed = failed;
    this.announcer.announce(
      failed ? 'The monitor API stopped answering.' : 'The monitor API answers again.',
    );
  }

  private focusHeading(): void {
    const heading = this.hostElement.nativeElement.querySelector<HTMLElement>('h1[tabindex="-1"]');
    heading?.focus();
  }
}
