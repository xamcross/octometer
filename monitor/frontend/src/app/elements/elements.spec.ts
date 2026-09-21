import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Elements } from './elements';

describe('Elements', () => {
  let fixture: ComponentFixture<Elements>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Elements],
    }).compileComponents();

    fixture = TestBed.createComponent(Elements);
    fixture.detectChanges();
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable placeholder heading', () => {
    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Elements');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });
});
