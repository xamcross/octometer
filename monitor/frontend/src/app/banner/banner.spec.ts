import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Banner } from './banner';

describe('Banner', () => {
  let fixture: ComponentFixture<Banner>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Banner],
    }).compileComponents();

    fixture = TestBed.createComponent(Banner);
  });

  it('reserves a height when the monitor API answers each request', () => {
    fixture.detectChanges();

    const slot = fixture.nativeElement.querySelector('.banner-slot') as HTMLElement;
    // 3rem equals 48px at the default font size, and it grows at a text zoom.
    expect(getComputedStyle(slot).minHeight).toBe('3rem');
    expect(slot.querySelector('.banner-text')).toBeNull();
  });

  it('keeps the same reserved height and shows the error text on a failed monitor API', () => {
    fixture.componentRef.setInput('apiError', 'The monitor API did not answer.');
    fixture.detectChanges();

    const slot = fixture.nativeElement.querySelector('.banner-slot') as HTMLElement;
    expect(getComputedStyle(slot).minHeight).toBe('3rem');
    const text = slot.querySelector('.banner-text');
    expect(text?.textContent).toContain('The monitor API did not answer.');
  });

  it('carries no live-region role, because the announcer holds the one status region', () => {
    fixture.componentRef.setInput('apiError', 'The monitor API did not answer.');
    fixture.detectChanges();

    const liveRegions = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[role="alert"], [role="status"], [aria-live]',
    );
    expect(liveRegions.length).toBe(0);
  });

  it('shows an icon before the error text, hidden from a screen reader', () => {
    fixture.componentRef.setInput('apiError', 'The monitor API did not answer.');
    fixture.detectChanges();

    const icon = fixture.nativeElement.querySelector('.banner-icon');
    expect(icon?.getAttribute('aria-hidden')).toBe('true');
  });
});
