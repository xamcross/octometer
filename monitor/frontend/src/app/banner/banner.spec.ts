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

  it('reserves no height when the monitor API answers each request (issue #375)', () => {
    fixture.detectChanges();

    const slot = fixture.nativeElement.querySelector('.banner-slot') as HTMLElement;
    expect(getComputedStyle(slot).minHeight).toBe('0px');
    expect(slot.querySelector('.banner-text')).toBeNull();
  });

  it('shows the error text in the normal flow, with no absolute position (issue #375)', () => {
    fixture.componentRef.setInput('apiError', 'The monitor API did not answer.');
    fixture.detectChanges();

    const slot = fixture.nativeElement.querySelector('.banner-slot') as HTMLElement;
    expect(getComputedStyle(slot).minHeight).toBe('0px');
    const text = slot.querySelector('.banner-text') as HTMLElement;
    expect(text?.textContent).toContain('The monitor API did not answer.');
    expect(getComputedStyle(text).position).toBe('static');
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
