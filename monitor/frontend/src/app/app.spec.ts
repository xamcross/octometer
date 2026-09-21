import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
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

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes, withComponentInputBinding())],
    }).compileComponents();

    fixture = TestBed.createComponent(App);
    router = TestBed.inject(Router);
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

    it('stays out of the tab order removal: it is not display:none nor visibility:hidden', () => {
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

    it('becomes visible on focus, through a `:focus` style rule that moves it back on screen', () => {
      fixture.detectChanges();

      const baseTop = findCssRule('.skip-link')?.style.top;
      const focusTop = findCssRule('.skip-link:focus')?.style.top;

      expect(baseTop).toBeTruthy();
      expect(focusTop).toBeTruthy();
      expect(focusTop).not.toBe(baseTop);
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

    it('does not start a router navigation that moves the focus to the h1', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps');
      await fixture.whenStable();

      const compiled = fixture.nativeElement as HTMLElement;
      const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement;
      const navigateSpy = vi.spyOn(router, 'navigateByUrl');

      skipLink.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      fixture.detectChanges();
      await fixture.whenStable();

      expect(navigateSpy).not.toHaveBeenCalled();
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
    expect(getComputedStyle(slot).minHeight).toBe('3rem');
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

    it('shows both links on the Users route, with no page marked current, and Apps visually active', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/users');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(appsLink.getAttribute('aria-current')).toBeNull();
      expect(manageLink.getAttribute('aria-current')).toBeNull();
      expect(appsLink.classList.contains('current-section')).toBe(true);
    });

    it('shows both links on the Elements route, with no page marked current, and Apps visually active', async () => {
      fixture.detectChanges();
      await router.navigateByUrl('/apps/7/elements?userId=42');
      await fixture.whenStable();

      const [appsLink, manageLink] = mainNavLinks();
      expect(appsLink.textContent?.trim()).toBe('Apps');
      expect(manageLink.textContent?.trim()).toBe('Manage apps');
      expect(appsLink.getAttribute('aria-current')).toBeNull();
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

  it('holds exactly one live region while the banner shows an error', () => {
    fixture.detectChanges();
    (
      fixture.componentInstance as unknown as { monitorApiError: { set(v: string): void } }
    ).monitorApiError.set('The monitor API did not answer.');
    fixture.detectChanges();

    const liveRegions = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[role="status"], [role="alert"], [aria-live]',
    );
    expect(liveRegions.length).toBe(1);
  });

  describe('the announcer, driven by fake timers', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('announces one time each transition of the monitor connection', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;
      const errorSignal = (
        fixture.componentInstance as unknown as { monitorApiError: { set(v: string | null): void } }
      ).monitorApiError;

      errorSignal.set('The monitor API did not answer.');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      errorSignal.set(null);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API answers again.');
    });

    it('announces one time per outage, even when the error text changes during it', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;
      const errorSignal = (
        fixture.componentInstance as unknown as { monitorApiError: { set(v: string | null): void } }
      ).monitorApiError;

      errorSignal.set('A timeout happened.');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      errorSignal.set('The server answered 500.');
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');

      errorSignal.set(null);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API answers again.');

      errorSignal.set('A new timeout happened.');
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(100);
      fixture.detectChanges();
      expect(region.textContent).toBe('The monitor API stopped answering.');
    });

    it('gives the same status text twice a fresh DOM write each time', async () => {
      fixture.detectChanges();
      const region = (fixture.nativeElement as HTMLElement).querySelector(
        '[role="status"]',
      ) as HTMLElement;
      const announcer = TestBed.inject(Announcer);

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
