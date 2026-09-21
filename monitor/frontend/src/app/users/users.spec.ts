import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Users } from './users';

describe('Users', () => {
  let fixture: ComponentFixture<Users>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Users],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Users);
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
    expect(heading?.textContent).toContain('Users');
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
});
