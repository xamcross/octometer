import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { buildBreadcrumb } from './breadcrumb';
import { routes } from './app.routes';

describe('routes', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes, withComponentInputBinding())],
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
    expect(TestBed.inject(Title).getTitle()).toBe('Manage - Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Manage', path: null },
    ]);
  });

  it('opens the Apps placeholder for /apps', async () => {
    await harness.navigateByUrl('/apps');
    expect(heading()).toContain('Apps');
    expect(TestBed.inject(Title).getTitle()).toBe('Apps - Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: null },
    ]);
  });

  it('opens the Users placeholder for /apps/:appId/users', async () => {
    await harness.navigateByUrl('/apps/7/users');
    expect(heading()).toContain('Users');
    expect(TestBed.inject(Title).getTitle()).toBe('Users of App 7 - Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: null },
    ]);
  });

  it('gives a different title for a different app, so two tabs read apart', async () => {
    await harness.navigateByUrl('/apps/9/users');
    expect(TestBed.inject(Title).getTitle()).toBe('Users of App 9 - Octometer');
  });

  it('opens the First pages placeholder for /apps/:appId/first-pages', async () => {
    await harness.navigateByUrl('/apps/7/first-pages');
    expect(heading()).toContain('First pages');
    expect(TestBed.inject(Title).getTitle()).toBe('First pages of App 7 - Octometer');
    const breadcrumb = buildBreadcrumb(router.routerState.snapshot.root);
    // The last entry names the current view. `breadcrumb-nav.spec.ts`
    // proves that the component marks this exact entry with
    // aria-current="page", because the path field of the last entry does
    // not change that mark.
    expect(breadcrumb).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'First pages', path: null },
    ]);
    expect(heading()).toContain(breadcrumb[breadcrumb.length - 1].label);
  });

  it('opens the Sessions placeholder for /apps/:appId/sessions with anonymous=true', async () => {
    await harness.navigateByUrl('/apps/7/sessions?anonymous=true');
    expect(heading()).toContain('Anonymous sessions');
    expect(TestBed.inject(Title).getTitle()).toBe('Anonymous sessions of App 7 - Octometer');
    const breadcrumb = buildBreadcrumb(router.routerState.snapshot.root);
    expect(breadcrumb).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Sessions', path: null },
    ]);
  });

  it('sends /apps/:appId/sessions with no anonymous=true to the user list (issue #115)', async () => {
    await harness.navigateByUrl('/apps/7/sessions');
    expect(router.url).toBe('/apps/7/users');
  });

  it('redirects to the user list when a later navigation on the sessions route drops anonymous=true', async () => {
    await harness.navigateByUrl('/apps/7/sessions?anonymous=true');
    expect(router.url).toBe('/apps/7/sessions?anonymous=true');

    await harness.navigateByUrl('/apps/7/sessions');
    expect(router.url).toBe('/apps/7/users');
  });

  it('opens the Elements placeholder for /apps/:appId/elements with a user id', async () => {
    await harness.navigateByUrl('/apps/7/elements?userId=42');
    expect(heading()).toBe('Elements of User 42');
    expect(TestBed.inject(Title).getTitle()).toBe('User 42 of App 7 - Octometer');
    const breadcrumb = buildBreadcrumb(router.routerState.snapshot.root);
    expect(breadcrumb).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: '/apps/7/users' },
      { label: 'User 42', path: null },
    ]);
    // The heading and the last breadcrumb entry name the same view.
    expect(heading()).toContain(breadcrumb[breadcrumb.length - 1].label);
  });

  it('opens the Elements placeholder for an anonymous user', async () => {
    await harness.navigateByUrl('/apps/7/elements?anonymous=true');
    expect(heading()).toBe('Elements of Anonymous');
    expect(TestBed.inject(Title).getTitle()).toBe('Anonymous of App 7 - Octometer');
    const breadcrumb = buildBreadcrumb(router.routerState.snapshot.root);
    expect(breadcrumb).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: '/apps/7/users' },
      { label: 'Anonymous', path: null },
    ]);
    expect(heading()).toContain(breadcrumb[breadcrumb.length - 1].label);
  });

  it('opens the Elements placeholder for a session id (issue #115)', async () => {
    await harness.navigateByUrl('/apps/7/elements?sessionId=0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11');
    expect(heading()).toBe('Elements of Session 0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11');
    expect(TestBed.inject(Title).getTitle()).toBe('Session 0b0e4e0e of App 7 - Octometer');
    const breadcrumb = buildBreadcrumb(router.routerState.snapshot.root);
    expect(breadcrumb).toEqual([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Sessions', path: null },
      { label: 'Session 0b0e4e0e', path: null },
    ]);
  });

  it('sends /apps/:appId/elements with no user id, no anonymous flag, and no session id to the user list', async () => {
    await harness.navigateByUrl('/apps/7/elements');
    expect(router.url).toBe('/apps/7/users');
  });

  it('gives a new title after a change of only the userId query parameter', async () => {
    await harness.navigateByUrl('/apps/7/elements?userId=42');
    expect(TestBed.inject(Title).getTitle()).toBe('User 42 of App 7 - Octometer');

    await harness.navigateByUrl('/apps/7/elements?userId=99');
    expect(TestBed.inject(Title).getTitle()).toBe('User 99 of App 7 - Octometer');
  });

  it('redirects to the user list when a later navigation on the same route drops the filter', async () => {
    await harness.navigateByUrl('/apps/7/elements?userId=42');
    expect(router.url).toBe('/apps/7/elements?userId=42');

    await harness.navigateByUrl('/apps/7/elements');
    expect(router.url).toBe('/apps/7/users');
  });

  it('opens the not-found view for an unknown URL', async () => {
    await harness.navigateByUrl('/nope');
    expect(heading()).toContain('Page not found');
    expect(TestBed.inject(Title).getTitle()).toBe('Page not found - Octometer');
    expect(buildBreadcrumb(router.routerState.snapshot.root)).toEqual([
      { label: 'Page not found', path: null },
    ]);
  });
});
