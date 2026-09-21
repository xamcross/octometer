import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { routes } from './app.routes';
import { App } from './app';
import { Announcer } from './announcer';

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
