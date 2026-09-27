import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { EmptyState } from './empty-state';

describe('EmptyState', () => {
  let fixture: ComponentFixture<EmptyState>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmptyState],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(EmptyState);
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the mark at 96 CSS px', () => {
    fixture.componentRef.setInput('text', 'No app yet.');
    fixture.detectChanges();

    const mark = root().querySelector('app-octo-mark svg');
    expect(mark?.getAttribute('width')).toBe('96');
    expect(mark?.getAttribute('height')).toBe('96');
  });

  it('shows the text of a case with no link, and no anchor', () => {
    fixture.componentRef.setInput('text', 'No app yet. Add one below.');
    fixture.detectChanges();

    expect(root().textContent).toContain('No app yet. Add one below.');
    expect(root().querySelector('a')).toBeNull();
  });

  it('shows a link with its routerLink and its label, inside the sentence', () => {
    fixture.componentRef.setInput('text', 'No app yet. Add one on');
    fixture.componentRef.setInput('linkText', 'Manage apps');
    fixture.componentRef.setInput('linkPath', '/manage');
    fixture.componentRef.setInput('textAfter', '.');
    fixture.detectChanges();

    const link = root().querySelector('a') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('/manage');
    expect(link.textContent?.trim()).toBe('Manage apps');
    expect(root().querySelector('.empty-text')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'No app yet. Add one on Manage apps.',
    );
  });
});
