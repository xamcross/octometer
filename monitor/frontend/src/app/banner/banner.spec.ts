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
    expect(slot.style.minHeight).toBe('48px');
    expect(slot.querySelector('[role="alert"]')).toBeNull();
  });

  it('keeps the same reserved height and shows the error text on a failed monitor API', () => {
    fixture.componentRef.setInput('apiError', 'The monitor API did not answer.');
    fixture.detectChanges();

    const slot = fixture.nativeElement.querySelector('.banner-slot') as HTMLElement;
    expect(slot.style.minHeight).toBe('48px');
    const alert = slot.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('The monitor API did not answer.');
  });
});
