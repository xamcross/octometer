import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NavigationStart, Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { routes } from './app.routes';
import { App } from './app';
import { Announcer } from './announcer';

/**
 * Finds the first loaded CSS rule with the given selector text.
 * jsdom does not update `getComputedStyle` for a `:focus` selector, so a
 * test reads the rule from the stylesheet itself, not from a live style.
 * Angular appends its own content attribute to a selector of an emulated
 * component, so this function strips that attribute before the comparison.
 */
function findCssRule(selectorText: string): CSSStyleRule | undefined {
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList | undefined;
    try {
      rules = sheet.cssRules;
    } catch {
      continue;
    }
    for (const rule of Array.from(rules ?? [])) {
      const styleRule = rule as CSSStyleRule;
      const plainSelector = styleRule.selectorText?.replace(/\[_ngcontent-[\w-]+\]/g, '');
      if (plainSelector === selectorText) {
        return styleRule;
      }
    }
  }
  return undefined;
}

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let router: Router;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(App);
    router = TestBed.inject(Router);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('creates the app', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows the first-load state before the first route is ready', () => {
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.first-load')).toBeTruthy();
  });

  it('hides the first-load state once a route is ready', async () => {
    fixture.detectChanges();
    await router.navigateByUrl('/apps');
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.first-load')).toBeNull();
    expect(compiled.querySelector('h1')?.textContent).toContain('Apps');
  });

  describe('the focus rule', () => {
    it('keeps the focus on the first page load', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const heading = (fixture.nativeElement as HTMLElement).querySelector('h1');
      expect(document.activeElement).not.toBe(heading);
      expect(document.activeElement).toBe(document.body);
    });

    it('moves the focus after a later navigation to a different route', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      await router.navigateByUrl('/manage');
      await fixture.whenStable();

      const heading = (fixture.nativeElement as HTMLElement).querySelector('h1');
      expect(heading?.textContent).toContain('Manage');
      expect(document.activeElement).toBe(heading);
    });

    it('moves the focus after a later navigation that changes a path parameter', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/users');
      await fixture.whenStable();

      await router.navigateByUrl('/apps/9/users');
      await fixture.whenStable();

      const heading = (fixture.nativeElement as HTMLElement).querySelector('h1');
      expect(heading?.textContent).toContain('Users');
      expect(document.activeElement).toBe(heading);
    });

    it('keeps the focus after a later navigation that changes a query parameter only', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/elements?userId=42');
      await fixture.whenStable();

      const probe = document.createElement('button');
      probe.textContent = 'Probe';
      document.body.appendChild(probe);
      probe.focus();
      expect(document.activeElement).toBe(probe);

      await router.navigateByUrl('/apps/7/elements?userId=99');
      await fixture.whenStable();

      expect(document.activeElement).toBe(probe);
      probe.remove();
    });

    it('keeps the focus after a later navigation that only adds a fragment to the same path', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const probe = document.createElement('button');
      probe.textContent = 'Probe';
      document.body.appendChild(probe);
      probe.focus();
      expect(document.activeElement).toBe(probe);

      await router.navigateByUrl('/apps#main-content');
      await fixture.whenStable();

      expect(document.activeElement).toBe(probe);
      probe.remove();
    });
  });

  describe('the skip link', () => {
    it('is the first focusable element after a fresh load', () => {
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const focusable = compiled.querySelectorAll('a, button, [tabindex]');
      expect(focusable.length).toBeGreaterThan(0);
      expect(focusable[0].classList.contains('skip-link')).toBe(true);
    });

    it('links to the main content, which exists', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement;
      expect(skipLink.getAttribute('href')).toBe('#main-content');
      expect(compiled.querySelector('#main-content')).toBeTruthy();
    });

    it('keeps its place in the tab order, because it is not display:none nor visibility:hidden', () => {
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLElement;
      const style = getComputedStyle(skipLink);
      expect(style.display).not.toBe('none');
      expect(style.visibility).not.toBe('hidden');
    });

    it('can take the focus, because it is not display:none nor visibility:hidden', () => {
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLElement;

      skipLink.focus();

      expect(document.activeElement).toBe(skipLink);
    });

    it('becomes visible on focus, by translating the link back onto the screen', () => {
      fixture.detectChanges();

      const baseTransform = findCssRule('.skip-link')?.style.transform;
      const focusTransform = findCssRule('.skip-link:focus')?.style.transform;

      expect(baseTransform).toBe('translateY(-100%)');
      expect(focusTransform).toBe('translateY(0)');
    });

    it('gives the skip link a minimum target size of 24 by 24 CSS px', () => {
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLElement;
      const style = getComputedStyle(skipLink);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('moves the focus to the main content on activation, and not to the h1', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement;
      const heading = compiled.querySelector('h1') as HTMLElement;
      const main = compiled.querySelector('#main-content') as HTMLElement;

      skipLink.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      fixture.detectChanges();

      expect(document.activeElement).toBe(main);
      expect(document.activeElement).not.toBe(heading);
    });

    it('stops the default action, moves the focus to main, and starts no router navigation', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement;
      const main = compiled.querySelector('#main-content') as HTMLElement;
      const startedNavigations: NavigationStart[] = [];
      const subscription = router.events.subscribe((routerEvent) => {
        if (routerEvent instanceof NavigationStart) {
          startedNavigations.push(routerEvent);
        }
      });

      const event = new MouseEvent('click', { bubbles: true, cancelable: true });
      skipLink.dispatchEvent(event);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(main);
      expect(startedNavigations).toEqual([]);
      subscription.unsubscribe();
    });
  });

  it('renders the breadcrumb nav for the active route', async () => {
    fixture.detectChanges();
    await router.navigateByUrl('/apps');
    await fixture.whenStable();

    const current = (fixture.nativeElement as HTMLElement).querySelector('[aria-current="page"]');
    expect(current?.textContent).toContain('Apps');
  });

  it('reserves a height for the banner slot before any monitor API error', () => {
    fixture.detectChanges();
    const slot = (fixture.nativeElement as HTMLElement).querySelector(
      '.banner-slot',
    ) as HTMLElement;
    // The CSS sets min-height to 3rem. jsdom 30 resolves a computed length
    // to a pixel value, the same as a real browser, so the value here is
    // 48px (3rem at the default 16px font size).
    expect(getComputedStyle(slot).minHeight).toBe('48px');
  });

  it('keeps one permanent role="status" region, empty until a user action', () => {
    fixture.detectChanges();
    const regions = (fixture.nativeElement as HTMLElement).querySelectorAll('[role="status"]');
    expect(regions.length).toBe(1);
    expect(regions[0].textContent).toBe('');
  });

  describe('landmarks', () => {
    it('holds exactly one main landmark, with the router outlet and the heading inside it', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const mains = compiled.querySelectorAll('main');
      expect(mains.length).toBe(1);
      expect(mains[0].querySelector('h1')).toBeTruthy();
    });

    it('keeps the breadcrumb nav, the banner, and the status region outside the main landmark', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const main = compiled.querySelector('main') as HTMLElement;
      expect(main.querySelector('nav')).toBeNull();
      expect(main.querySelector('.banner-slot')).toBeNull();
      expect(main.querySelector('[role="status"]')).toBeNull();
    });

    it('shows a nav landmark labelled "Main", different from the nav labelled "Breadcrumb"', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const navs = Array.from(compiled.querySelectorAll('nav')) as HTMLElement[];
      const labels = navs.map((nav) => nav.getAttribute('aria-label'));
      expect(labels).toContain('Main');
      expect(labels).toContain('Breadcrumb');
    });
  });

  describe('the main nav on each route', () => {
    function mainNavLinks(): HTMLAnchorElement[] {
      const nav = (fixture.nativeElement as HTMLElement).querySelector(
        'nav[aria-label="Main"]',
      ) as HTMLElement;
      return Array.from(nav.querySelectorAll('a'));
    }

    it('shows the "Apps" and the "Manage apps" links on the Manage route, with Manage apps current', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/manage');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(manageLink.getAttribute('aria-current')).toBe('page');
      expect(appsLink.getAttribute('aria-current')).toBeNull();
    });

    it('shows both links on the Apps route, with Apps current', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.getAttribute('aria-current')).toBe('page');
      expect(manageLink.getAttribute('aria-current')).toBeNull();
    });

    it('shows both links on the Users route, with Apps current as a section, not as the page', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/users');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(appsLink.getAttribute('aria-current')).toBe('true');
      expect(manageLink.getAttribute('aria-current')).toBeNull();
      expect(appsLink.classList.contains('current-section')).toBe(true);
    });

    it('shows both links on the Elements route, with Apps current as a section, not as the page', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/elements?userId=42');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(appsLink.getAttribute('aria-current')).toBe('true');
      expect(manageLink.getAttribute('aria-current')).toBeNull();
      expect(appsLink.classList.contains('current-section')).toBe(true);
    });

    it('shows both links on an unknown route, with neither link current', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/no-such-route');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(appsLink.getAttribute('aria-current')).toBeNull();
      expect(manageLink.getAttribute('aria-current')).toBeNull();
    });
  });

  describe('the monitor API banner (D30), driven by fake timers', () => {
    beforeEach(() => {
      // RefreshIntervalState sends GET /api/health at once, in its
      // constructor. The fake clock must run before that happens, so this
      // block builds its own fixture, and not the one of the outer
      // `beforeEach` (built before the fake clock started).
      vi.useFakeTimers();
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [App],
        providers: [
          provideRouter(routes, withComponentInputBinding()),
          provideHttpClient(),
          provideHttpClientTesting(),
        ],
      });
      fixture = TestBed.createComponent(App);
      httpMock = TestBed.inject(HttpTestingController);
      router = TestBed.inject(Router);
    });

    afterEach(() => {
      vi.useRealTimers();
      httpMock.verify();
    });

    /** Runs the first health tick, so the request reaches HttpTestingController. */
    function startHealth(): void {
      vi.advanceTimersByTime(0);
    }

    /** Fails the pending GET /api/health request. */
    function failHealth(): void {
      httpMock
        .expectOne('/api/health')
        .flush(null, { status: 503, statusText: 'Service Unavailable' });
      fixture.detectChanges();
    }

    /** Answers the pending GET /api/health request with a good interval. */
    function succeedHealth(refreshSeconds = 10): void {
      httpMock.expectOne('/api/health').flush({ refreshSeconds });
      fixture.detectChanges();
    }

    function bannerText(): string {
      const slot = (fixture.nativeElement as HTMLElement).querySelector('.banner-slot');
      return slot?.textContent?.trim() ?? '';
    }

    it('shows the banner text for a failed GET /api/health', () => {
      fixture.detectChanges();
      startHealth();
      failHealth();

      expect(bannerText()).toContain('The monitor API did not answer.');
    });

    it('clears the banner once GET /api/health answers after a failure', () => {
      fixture.detectChanges();
      startHealth();
      failHealth();
      expect(bannerText()).toContain('The monitor API did not answer.');

      vi.advanceTimersByTime(5_000);
      succeedHealth();

      expect(bannerText()).toBe('');
    });

    it('shows the banner for a failed data poll of the active view after the first good health answer, and hides it again on a later good poll', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      fixture.detectChanges();

      startHealth();
      succeedHealth(10);
      expect(bannerText()).toBe('');

      // The apps view starts its own poll once the health interval resolves.
      vi.advanceTimersByTime(0);
      httpMock
        .expectOne('/api/apps')
        .flush(null, { status: 503, statusText: 'Service Unavailable' });
      fixture.detectChanges();

      expect(bannerText()).toContain('The monitor API did not answer.');

      vi.advanceTimersByTime(10_000);
      httpMock.expectOne('/api/apps').flush([]);
      fixture.detectChanges();

      expect(bannerText()).toBe('');
    });

    it('keeps the banner error, and the announcer silent, across a route change between two table views during an outage (MAJOR 1 of pull request #194)', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;

      await router.navigateByUrl('/apps');
      fixture.detectChanges();
      startHealth();
      succeedHealth(10);

      vi.advanceTimersByTime(0);
      httpMock
        .expectOne('/api/apps')
        .flush(null, { status: 503, statusText: 'Service Unavailable' });
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(bannerText()).toContain('The monitor API did not answer.');
      expect(region.textContent).toBe('The monitor API stopped answering.');

      // A route change to a different table view, still during the outage.
      await router.navigateByUrl('/apps/7/users');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();

      // The new view has no answer of its own yet. The banner keeps the
      // old error. The announcer stays silent: no false recovery message.
      expect(bannerText()).toContain('The monitor API did not answer.');
      expect(region.textContent).toBe('The monitor API stopped answering.');

      httpMock.expectOne('/api/apps/7/users?page=1').flush({ page: 1, pageCount: 1, rows: [] });
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();

      expect(bannerText()).toBe('');
      expect(region.textContent).toBe('The monitor API answers again.');
    });

    it('clears the banner on a navigation to /manage, a view with no data poll, after a failed data poll of a table view', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      fixture.detectChanges();

      startHealth();
      succeedHealth(10);

      vi.advanceTimersByTime(0);
      httpMock
        .expectOne('/api/apps')
        .flush(null, { status: 503, statusText: 'Service Unavailable' });
      fixture.detectChanges();
      expect(bannerText()).toContain('The monitor API did not answer.');

      await router.navigateByUrl('/manage');
      fixture.detectChanges();
      // Manage reads GET /api/apps once on load, on its own, apart from
      // the shared error path that this test covers.
      httpMock.expectOne('/api/apps').flush([]);
      fixture.detectChanges();

      expect(bannerText()).toBe('');
    });

    it('holds exactly one live region while the banner shows an error', () => {
      fixture.detectChanges();
      startHealth();
      failHealth();

      const liveRegions = (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[role="status"], [role="alert"], [aria-live]',
      );
      expect(liveRegions.length).toBe(1);
    });

    it('announces one time each transition of the monitor connection, and a recovery clears the banner', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;

      startHealth();
      failHealth();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      vi.advanceTimersByTime(5_000);
      succeedHealth();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API answers again.');
      expect(bannerText()).toBe('');
    });

    it('announces one time per outage, even while a retry keeps on failing', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;

      startHealth();
      failHealth();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      vi.advanceTimersByTime(5_000);
      failHealth();
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      vi.advanceTimersByTime(5_000);
      succeedHealth();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API answers again.');
    });

    it('gives the same status text twice a fresh DOM write each time', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;
      const announcer = TestBed.inject(Announcer);
      startHealth();
      failHealth(); // Drains the health request, so afterEach.verify() finds none pending.

      announcer.announce('The refresh is paused.');
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The refresh is paused.');

      const oldValuesOfEachRecord: string[] = [];
      const observer = new MutationObserver((records) => {
        for (const record of records) {
          oldValuesOfEachRecord.push(record.oldValue ?? '');
        }
      });
      observer.observe(region, {
        characterData: true,
        characterDataOldValue: true,
        childList: true,
        subtree: true,
      });

      announcer.announce('The refresh is paused.');
      fixture.detectChanges();
      // Let the pending mutation record of the clear step reach the observer
      // before the settle step writes the text again, so each write keeps its
      // own record.
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      // Let the pending mutation record of the settle step reach the observer
      // before the assertion reads it.
      await Promise.resolve();

      expect(region.textContent).toBe('The refresh is paused.');
      // Two DOM writes happen for one repeated message: the clear step, then the
      // settle step. Each write is its own mutation record.
      expect(oldValuesOfEachRecord).toEqual(['The refresh is paused.', '']);
      observer.disconnect();
    });
  });
});
