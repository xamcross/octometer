import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import type { Routes } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { MainNav } from './main-nav';

/** An empty view for a test route. It renders no content of its own. */
@Component({ selector: 'app-blank-test-view', template: '' })
class BlankTestView {}

const testRoutes: Routes = [
  { path: 'apps', component: BlankTestView },
  { path: 'manage', component: BlankTestView },
  { path: 'apps/:appId/users', component: BlankTestView },
  { path: 'apps/:appId/elements', component: BlankTestView },
];

/** Hosts the nav next to a router outlet, so a navigation can activate a route. */
@Component({
  selector: 'app-main-nav-test-host',
  imports: [MainNav, RouterOutlet],
  template: '<app-main-nav /><router-outlet />',
})
class MainNavTestHost {}

describe('MainNav', () => {
  let fixture: ComponentFixture<MainNavTestHost>;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MainNavTestHost],
      providers: [provideRouter(testRoutes)],
    }).compileComponents();

    fixture = TestBed.createComponent(MainNavTestHost);
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  function links(): HTMLAnchorElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'));
  }

  it('renders a nav landmark labelled "Main"', () => {
    const nav = (fixture.nativeElement as HTMLElement).querySelector('nav');
    expect(nav?.getAttribute('aria-label')).toBe('Main');
  });

  it('renders the link "Apps" to /apps and the link "Manage apps" to /manage', () => {
    const [appsLink, manageLink] = links();
    expect(appsLink.textContent?.trim()).toBe('Apps');
    expect(appsLink.getAttribute('href')).toBe('/apps');
    expect(manageLink.textContent?.trim()).toBe('Manage apps');
    expect(manageLink.getAttribute('href')).toBe('/manage');
  });

  it('gives each link a minimum target size of 24 by 24 CSS px', () => {
    for (const link of links()) {
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    }
  });

  it('sets aria-current="page" on the Apps link only on the exact /apps route', async () => {
    await router.navigateByUrl('/apps');
    fixture.detectChanges();

    const [appsLink, manageLink] = links();
    expect(appsLink.getAttribute('aria-current')).toBe('page');
    expect(manageLink.getAttribute('aria-current')).toBeNull();
  });

  it('sets aria-current="page" on the Manage apps link only on the /manage route', async () => {
    await router.navigateByUrl('/manage');
    fixture.detectChanges();

    const [appsLink, manageLink] = links();
    expect(manageLink.getAttribute('aria-current')).toBe('page');
    expect(appsLink.getAttribute('aria-current')).toBeNull();
  });

  it('does not set aria-current="page" on the Apps link for a user list below /apps', async () => {
    await router.navigateByUrl('/apps/7/users');
    fixture.detectChanges();

    const [appsLink] = links();
    expect(appsLink.getAttribute('aria-current')).toBeNull();
  });

  it('does not set aria-current="page" on the Apps link for an element list below /apps', async () => {
    await router.navigateByUrl('/apps/7/elements');
    fixture.detectChanges();

    const [appsLink] = links();
    expect(appsLink.getAttribute('aria-current')).toBeNull();
  });

  it('keeps a visual current-section state on the Apps link for a user list below /apps', async () => {
    await router.navigateByUrl('/apps/7/users');
    fixture.detectChanges();

    const [appsLink] = links();
    expect(appsLink.classList.contains('current-section')).toBe(true);
  });

  it('keeps a visual current-section state on the Apps link for an element list below /apps', async () => {
    await router.navigateByUrl('/apps/7/elements');
    fixture.detectChanges();

    const [appsLink] = links();
    expect(appsLink.classList.contains('current-section')).toBe(true);
  });

  it('removes the current-section state from the Apps link on the Manage route', async () => {
    await router.navigateByUrl('/manage');
    fixture.detectChanges();

    const [appsLink] = links();
    expect(appsLink.classList.contains('current-section')).toBe(false);
  });
});
