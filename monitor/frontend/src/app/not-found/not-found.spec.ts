import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { NotFound } from './not-found';

describe('NotFound', () => {
  let fixture: ComponentFixture<NotFound>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NotFound],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(NotFound);
    fixture.detectChanges();
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable "Page not found" heading', () => {
    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Page not found');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('shows a link "Go to the apps" back to /apps', () => {
    const link = fixture.nativeElement.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/apps');
    expect(link?.textContent?.trim()).toBe('Go to the apps');
  });

  it('shows the mark and the sentence of the empty state', () => {
    expect(fixture.nativeElement.querySelector('app-octo-mark svg')).toBeTruthy();
    expect(fixture.nativeElement.textContent).toContain('This page does not exist.');
  });

  it('puts the empty state inside a panel', () => {
    const panel = fixture.nativeElement.querySelector('section.panel') as HTMLElement;
    expect(panel).toBeTruthy();
    expect(panel.querySelector('app-empty-state')).toBeTruthy();
  });

  it('gives the link a minimum target size of 24 by 24 CSS px', () => {
    const link = fixture.nativeElement.querySelector('a') as HTMLElement;
    const style = getComputedStyle(link);
    expect(style.minWidth).toBe('24px');
    expect(style.minHeight).toBe('24px');
  });

  it('shows no refresh bar (#92)', () => {
    expect(fixture.nativeElement.querySelector('app-refresh-bar')).toBeNull();
  });
});
