import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { App } from './app';

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
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

  it('hides the first-load state and moves focus to the h1 once a route is ready', async () => {
    fixture.detectChanges();
    await router.navigateByUrl('/apps');
    await fixture.whenStable();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.first-load')).toBeNull();
    const heading = compiled.querySelector('h1');
    expect(heading?.textContent).toContain('Apps');
    expect(document.activeElement).toBe(heading);
  });

  it('moves focus to the new h1 after a later navigation', async () => {
    fixture.detectChanges();
    await router.navigateByUrl('/apps');
    await fixture.whenStable();

    await router.navigateByUrl('/manage');
    await fixture.whenStable();

    const heading = (fixture.nativeElement as HTMLElement).querySelector('h1');
    expect(heading?.textContent).toContain('Manage');
    expect(document.activeElement).toBe(heading);
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
    expect(slot.style.minHeight).toBe('48px');
  });

  it('keeps one permanent role="status" region, empty until a user action', () => {
    fixture.detectChanges();
    const regions = (fixture.nativeElement as HTMLElement).querySelectorAll('[role="status"]');
    expect(regions.length).toBe(1);
    expect(regions[0].textContent).toBe('');
  });
});
