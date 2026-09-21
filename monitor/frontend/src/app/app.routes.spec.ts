import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { buildBreadcrumb } from './breadcrumb';
import { routes } from './app.routes';

describe('routes', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes)],
    });
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
  });

  function heading(): string | null | undefined {
    return harness.routeNativeElement?.querySelector('h1')?.textContent;
  }

  it('sends the root path to /apps', async () => {
    await harness.navigateByUrl('/');
    expect(router.url).toBe('/apps');
  });

  it('opens the Manage placeholder for /manage', async () => {
    await harness.navigateByUrl('/manage');
    expect(heading()).toContain('Manage');
    expect(TestBed.inject(Title).getTitle()).toBe('Manage — Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Manage', path: null },
    ]);
  });

  it('opens the Apps placeholder for /apps', async () => {
    await harness.navigateByUrl('/apps');
    expect(heading()).toContain('Apps');
    expect(TestBed.inject(Title).getTitle()).toBe('Apps — Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: null },
    ]);
  });

  it('opens the Users placeholder for /apps/:appId/users', async () => {
    await harness.navigateByUrl('/apps/7/users');
    expect(heading()).toContain('Users');
    expect(TestBed.inject(Title).getTitle()).toBe('Users — Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);
  });

  it('opens the Elements placeholder for /apps/:appId/elements with a user id', async () => {
    await harness.navigateByUrl('/apps/7/elements?userId=42');
    expect(heading()).toContain('Elements');
    expect(TestBed.inject(Title).getTitle()).toBe('Elements — Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: '/apps/7/users' },
      { label: 'User 42', path: null },
    ]);
  });

  it('opens the Elements placeholder for an anonymous user', async () => {
    await harness.navigateByUrl('/apps/7/elements?anonymous=true');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: '/apps/7/users' },
      { label: 'Anonymous', path: null },
    ]);
  });
});
