import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Manage } from './manage';

describe('Manage', () => {
  let fixture: ComponentFixture<Manage>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Manage],
    }).compileComponents();

    fixture = TestBed.createComponent(Manage);
    fixture.detectChanges();
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable placeholder heading', () => {
    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Manage');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('shows no refresh bar (#92)', () => {
    expect(fixture.nativeElement.querySelector('app-refresh-bar')).toBeNull();
  });
});
