import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Elements } from './elements';

describe('Elements', () => {
  let fixture: ComponentFixture<Elements>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Elements],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Elements);
    fixture.detectChanges();
  });

  afterEach(() => {
    for (const request of httpMock.match('/api/health')) {
      request.flush({ refreshSeconds: 10 });
    }
    httpMock.verify();
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable placeholder heading', () => {
    const heading = fixture.nativeElement.querySelector('h1');
    expect(heading?.textContent).toContain('Elements');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('puts the refresh bar directly after the heading, and before the table content', () => {
    const root: HTMLElement = fixture.nativeElement;
    const children = Array.from(root.children);
    const headingIndex = children.findIndex((el) => el.tagName === 'H1');
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');
    const contentIndex = children.findIndex((el) => el.tagName === 'P');

    expect(headingIndex).toBeGreaterThanOrEqual(0);
    expect(refreshBarIndex).toBeGreaterThan(headingIndex);
    expect(contentIndex).toBeGreaterThan(refreshBarIndex);
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
