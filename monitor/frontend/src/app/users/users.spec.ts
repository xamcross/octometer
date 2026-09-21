import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Users } from './users';

describe('Users', () => {
  let fixture: ComponentFixture<Users>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Users],
    }).compileComponents();

    fixture = TestBed.createComponent(Users);
    fixture.detectChanges();
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable placeholder heading', () => {
    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Users');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });
});
