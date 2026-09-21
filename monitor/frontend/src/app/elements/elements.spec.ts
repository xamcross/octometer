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

  it('names the user in the heading, the same as the last breadcrumb entry', () => {
    fixture.componentRef.setInput('userId', '42');
    fixture.detectChanges();

    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('User 42');
  });

  it('names the anonymous user in the heading, the same as the last breadcrumb entry', () => {
    fixture.componentRef.setInput('anonymous', 'true');
    fixture.detectChanges();

    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Anonymous');
  });
});
