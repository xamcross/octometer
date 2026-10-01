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
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { AppNames } from './app-names';
import { Announcer } from './announcer';
import { Me } from './account/me';
import { Banner } from './banner/banner';
import type { BreadcrumbItem } from './breadcrumb';
import { buildBreadcrumb } from './breadcrumb';
import { BreadcrumbNav } from './breadcrumb-nav/breadcrumb-nav';
import { MainNav } from './main-nav/main-nav';
import { OctoMark } from './octo-mark/octo-mark';
import { RefreshIntervalState } from './poll/refresh-interval-state';

/**
 * Root shell of the app.
 * It holds the skip link, the band with the mark and the main nav, the
 * banner, the breadcrumb, the status region, and the router outlet.
 */
@Component({
  imports: [RouterOutlet, RouterLink, Banner, BreadcrumbNav, MainNav, OctoMark],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly hostElement: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly intervalState = inject(RefreshIntervalState);
  private readonly appNames = inject(AppNames);

  /** The status text of the one permanent status region (D33). */
  protected readonly announcer = inject(Announcer);

  /** The signed-in account of issue #301, D52. The header reads its email and its sign-out control. */
  protected readonly me = inject(Me);

  /** True once the router outlet activates its first view component. */
  protected readonly appReady = signal(false);

  /**
   * The error text of the monitor API (D30): a failed `GET /api/health`,
   * or a failed data poll of the active view (issue #164). Null while
   * both routes answer well. The banner shows this text.
   */
  protected readonly monitorApiError = computed(() =>
    this.intervalState.error() === undefined ? null : 'The monitor API did not answer.',
  );

  /** The breadcrumb trail of the active route, before the app-name resolution. */
  private readonly rawBreadcrumbItems = signal<BreadcrumbItem[]>([]);

  /**
   * The breadcrumb trail of the active route (issue #270). Each entry
   * with an `appId` gets the resolved app name as its label, through the
   * app-name store; it keeps the fallback label of the route data until
   * the name arrives.
   */
  protected readonly breadcrumbItems = computed<BreadcrumbItem[]>(() =>
    this.rawBreadcrumbItems().map((item) => this.withResolvedName(item)),
  );

  /** The path part of the previous URL. Null before the first navigation ends. */
  private previousPath: string | null = null;

  /** True when the monitor API did not answer the last request. */
  private monitorApiFailed = false;

  /** The path part of the active URL, for [isSigninRoute]. */
  private readonly currentPathState = signal(this.router.url.split(/[?#]/)[0]);

  /**
   * True on the sign-in page (issue #301). The sign-in page hides the
   * main nav and the breadcrumb; the band with the name stays (the
   * maintainer's correction).
   */
  protected readonly isSigninRoute = computed(() => this.currentPathState().startsWith('/signin'));

  constructor() {
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => this.onNavigationEnd(event));

    effect(() => this.announceMonitorApiTransition());

    // Issue #301, brief review B4: the health poll starts only after
    // one good answer of GET /api/me. me.load() sends that request;
    // the effect below opens the poll's start gate once me.ready()
    // turns true, so a visitor with no session sends no /api/health
    // request at all (a 401 there never sets ready).
    effect(() => {
      if (this.me.ready()) {
        this.intervalState.allowStart();
      }
    });
    this.me.load();
  }

  /** Runs when the router outlet activates a view component. */
  protected onOutletActivated(): void {
    this.appReady.set(true);
  }

  /** Ends the session, on a click of the header "Sign out" control (D52). */
  protected onSignOutClick(): void {
    this.me.signOut();
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
    const items = buildBreadcrumb(this.router.routerState.snapshot.root);
    this.rawBreadcrumbItems.set(items);
    // Asks the app-name store for a missing id (issue #270), for example
    // on a direct load of a level 2 or a level 3 view: that view polls
    // its own data route only, so the store holds no name yet.
    for (const item of items) {
      if (item.appId !== undefined) {
        this.appNames.ensureLoaded(item.appId);
      }
    }

    const path = event.urlAfterRedirects.split(/[?#]/)[0];
    const isFirstNavigation = this.previousPath === null;
    const pathChanged = path !== this.previousPath;
    this.previousPath = path;
    this.currentPathState.set(path);

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

  /** Resolves the label of one app crumb from the app-name store, when it holds one. */
  private withResolvedName(item: BreadcrumbItem): BreadcrumbItem {
    if (item.appId === undefined) {
      return item;
    }
    const name = this.appNames.nameFor(item.appId);
    return name === undefined ? item : { ...item, label: name };
  }

  private focusHeading(): void {
    const heading = this.hostElement.nativeElement.querySelector<HTMLElement>('h1[tabindex="-1"]');
    heading?.focus();
  }
}
