import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
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
  private readonly injector = inject(Injector);
  private readonly hostElement: ElementRef<HTMLElement> = inject(ElementRef);

  /** The status text of the one permanent status region (D33). */
  protected readonly announcer = inject(Announcer);

  /** True once the router outlet activates its first view component. */
  protected readonly appReady = signal(false);

  /** The error text of the monitor API. A later issue reads this from the poll store. */
  protected readonly monitorApiError = signal<string | null>(null);

  /** The breadcrumb trail of the active route. */
  protected readonly breadcrumbItems = signal<BreadcrumbItem[]>([]);

  /** The path part of the previous URL. Null before the first navigation ends. */
  private previousPath: string | null = null;

  /** The previous value of `monitorApiError`, so an effect finds only a real transition. */
  private previousMonitorApiError: string | null = null;

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
   * Updates the breadcrumb, and moves the focus to the `<h1>` of the new view.
   * The focus stays where it is on the first load, and on a change of a query
   * parameter alone. The focus moves on each later navigation that changes the path.
   */
  private onNavigationEnd(event: NavigationEnd): void {
    this.breadcrumbItems.set(buildBreadcrumb(this.router.routerState.snapshot.root));

    const path = event.urlAfterRedirects.split('?')[0];
    const isFirstNavigation = this.previousPath === null;
    const pathChanged = path !== this.previousPath;
    this.previousPath = path;

    if (!isFirstNavigation && pathChanged) {
      afterNextRender(() => this.focusHeading(), { injector: this.injector });
    }
  }

  /** Announces one time each transition of the monitor connection through the status region. */
  private announceMonitorApiTransition(): void {
    const current = this.monitorApiError();
    if (current === this.previousMonitorApiError) {
      return;
    }
    if (current) {
      this.announcer.announce('The monitor API stopped answering.');
    } else if (this.previousMonitorApiError) {
      this.announcer.announce('The monitor API answers again.');
    }
    this.previousMonitorApiError = current;
  }

  private focusHeading(): void {
    const heading = this.hostElement.nativeElement.querySelector<HTMLElement>('h1[tabindex="-1"]');
    heading?.focus();
  }
}
