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

  function setItems(
    items: { label: string; path: string | null }[],
  ): ReturnType<typeof fixture.nativeElement.querySelectorAll> {
    fixture.componentRef.setInput('items', items);
    fixture.detectChanges();
    return fixture.nativeElement.querySelectorAll('li');
  }

  it('renders a nav landmark labelled "Breadcrumb" with an ordered list', () => {
    fixture.componentRef.setInput('items', [{ label: 'Apps', path: null }]);
    fixture.detectChanges();

    const nav = fixture.nativeElement.querySelector('nav');
    expect(nav?.getAttribute('aria-label')).toBe('Breadcrumb');
    expect(nav?.querySelector('ol')).toBeTruthy();
  });

  it('renders a link for a middle entry with a path', () => {
    const items = setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: null },
    ]);

    const links = fixture.nativeElement.querySelectorAll('a');
    expect(links.length).toBe(1);
    expect(links[0].textContent).toContain('Apps');
    expect(items[0].querySelector('[aria-current]')).toBeNull();
  });

  it('renders a middle entry with no path as plain text, not a link and not the current page', () => {
    const items = setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: null },
    ]);

    const middle = items[1];
    expect(middle.textContent).toContain('App 7');
    expect(middle.querySelector('a')).toBeNull();
    expect(middle.querySelector('[aria-current]')).toBeNull();
  });

  it('marks the last entry as the current page, and never as a link', () => {
    const items = setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
      { label: 'Users', path: null },
    ]);

    const last = items[items.length - 1];
    expect(last.querySelector('[aria-current="page"]')?.textContent).toContain('Users');
    expect(last.querySelector('a')).toBeNull();
  });

  it('marks the last entry as the current page, even when it carries a path', () => {
    const items = setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'Apps', path: '/apps' },
    ]);

    const last = items[items.length - 1];
    expect(last.querySelector('[aria-current="page"]')).toBeTruthy();
    expect(last.querySelector('a')).toBeNull();
  });

  it('gives each link a minimum target size of 24 by 24 CSS px', () => {
    setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);

    const link = fixture.nativeElement.querySelector('a') as HTMLElement;
    const style = getComputedStyle(link);
    expect(style.minWidth).toBe('24px');
    expect(style.minHeight).toBe('24px');
  });

  it('renders the list with no list numbers', () => {
    const items = setItems([
      { label: 'Apps', path: '/apps' },
      { label: 'App 7', path: null },
    ]);

    const ol = fixture.nativeElement.querySelector('ol') as HTMLElement;
    expect(getComputedStyle(ol).listStyleType).toBe('none');
    expect(items[0].textContent?.trim()).toBe('Apps');
  });

  it('wraps a long label at any character, so a long user id causes no horizontal scroll', () => {
    const longLabel = 'u'.repeat(254);
    setItems([
      { label: 'Apps', path: '/apps' },
      { label: longLabel, path: null },
    ]);

    const li = fixture.nativeElement.querySelectorAll('li')[1] as HTMLElement;
    expect(getComputedStyle(li).overflowWrap).toBe('anywhere');
  });
});
