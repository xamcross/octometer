import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { BreadcrumbNav } from './breadcrumb-nav';

describe('BreadcrumbNav', () => {
  let fixture: ComponentFixture<BreadcrumbNav>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BreadcrumbNav],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(BreadcrumbNav);
  });

  it('renders a nav landmark labelled "Breadcrumb" with an ordered list', () => {
    fixture.componentRef.setInput('items', [{ label: 'Apps', path: null }]);
    fixture.detectChanges();

    const nav = fixture.nativeElement.querySelector('nav');
    expect(nav?.getAttribute('aria-label')).toBe('Breadcrumb');
    expect(nav?.querySelector('ol')).toBeTruthy();
  });

  it('renders a link for each entry with a path', () => {
    fixture.componentRef.setInput('items', [
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);
    fixture.detectChanges();

    const links = fixture.nativeElement.querySelectorAll('a');
    expect(links.length).toBe(1);
    expect(links[0].textContent).toContain('Apps');
  });

  it('marks the entry with no path as the current page', () => {
    fixture.componentRef.setInput('items', [
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);
    fixture.detectChanges();

    const items = fixture.nativeElement.querySelectorAll('li');
    const current = items[items.length - 1];
    expect(current.querySelector('[aria-current="page"]')?.textContent).toContain('App 7');
    expect(current.querySelector('a')).toBeNull();
  });

  it('gives each link a minimum target size of 24 by 24 CSS px', () => {
    fixture.componentRef.setInput('items', [
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a') as HTMLElement;
    expect(link.style.minWidth).toBe('24px');
    expect(link.style.minHeight).toBe('24px');
  });
});
