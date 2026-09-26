import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import type { ElementRow, ElementsResponse } from './element-row';
import { Elements, buildElementsRequestParams } from './elements';

/** Builds one elements answer. Each test overrides only the fields it checks. */
function buildResponse(overrides: Partial<ElementsResponse> = {}): ElementsResponse {
  return {
    rows: [buildRow()],
    ...overrides,
  };
}

/** Builds one element row. Each test overrides only the fields it checks. */
function buildRow(overrides: Partial<ElementRow> = {}): ElementRow {
  return {
    element: 'buy-button',
    clicks: 1234,
    sessions: 12,
    lastInteractionAt: '2026-09-21T09:05:03.000Z',
    ...overrides,
  };
}

describe('buildElementsRequestParams', () => {
  it('reads userId when anonymous is not set', () => {
    expect(buildElementsRequestParams('42', null)).toEqual({ userId: '42' });
  });

  it('reads anonymous=true, and drops a userId next to it', () => {
    expect(buildElementsRequestParams('42', 'true')).toEqual({ anonymous: 'true' });
  });

  it('gives no parameter when neither is set', () => {
    expect(buildElementsRequestParams(null, null)).toEqual({});
  });

  it('gives no parameter for anonymous with a value other than "true"', () => {
    expect(buildElementsRequestParams(null, 'false')).toEqual({});
  });
});

describe('Elements', () => {
  let fixture: ComponentFixture<Elements>;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Elements],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(Elements);
    fixture.componentRef.setInput('appId', '7');
    fixture.componentRef.setInput('userId', '42');
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    httpMock.verify();
  });

  /** Answers the one health request of the page, and runs the first data tick. */
  function startStore(refreshSeconds = 10): void {
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds });
    vi.advanceTimersByTime(0);
  }

  /** Answers the one pending `GET /api/apps/7/elements` request, with the given query. */
  function flushElements(page: ElementsResponse, query = 'userId=42'): void {
    httpMock.expectOne(`/api/apps/7/elements?${query}`).flush(page);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('creates the component', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/elements?userId=42').flush(buildResponse());
  });

  it('shows a focusable heading naming the user', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toBe('Elements of User 42');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('names the anonymous user in the heading', () => {
    fixture.componentRef.setInput('userId', null);
    fixture.componentRef.setInput('anonymous', 'true');
    fixture.detectChanges();
    startStore();
    httpMock.expectOne('/api/apps/7/elements?anonymous=true').flush(buildResponse());

    expect(root().querySelector('h1')?.textContent).toBe('Elements of Anonymous');
  });

  it('puts the refresh bar directly after the heading, and before the table content', () => {
    startStore();
    flushElements(buildResponse());

    const children = Array.from(root().children);
    const headingIndex = children.findIndex((el) => el.tagName === 'H1');
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');
    const tableWrapperIndex = children.findIndex((el) => el.classList.contains('table-scroll'));

    expect(headingIndex).toBe(0);
    expect(refreshBarIndex).toBe(1);
    expect(tableWrapperIndex).toBeGreaterThan(refreshBarIndex);
  });

  it('shows a loading text before the first answer arrives, and no table', () => {
    expect(root().querySelector('table')).toBeNull();
    expect(root().textContent).toContain('Octometer reads the element list.');
  });

  it('sends the request with the userId query parameter of the route', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/elements?userId=42').flush(buildResponse());
  });

  it('sends the request with anonymous=true when the route carries that parameter', () => {
    fixture.componentRef.setInput('userId', null);
    fixture.componentRef.setInput('anonymous', 'true');
    fixture.detectChanges();
    startStore();
    httpMock.expectOne('/api/apps/7/elements?anonymous=true').flush(buildResponse());
  });

  describe('once the element list answers', () => {
    it('renders a table with a caption and a scoped row header for the element name', () => {
      startStore();
      flushElements(buildResponse({ rows: [buildRow({ element: 'zeta-widget' })] }));

      const table = root().querySelector('table');
      expect(table).toBeTruthy();
      expect(table?.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);

      const rowHeader = table?.querySelector('tbody th') as HTMLTableCellElement;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.textContent?.trim()).toBe('zeta-widget');
    });

    it('gives each column header a scope of "col"', () => {
      startStore();
      flushElements(buildResponse());

      const headers = Array.from(root().querySelectorAll('thead th'));
      expect(headers.length).toBeGreaterThan(0);
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
    });

    it('shows an element name with HTML characters as plain text, never as markup', () => {
      startStore();
      flushElements(buildResponse({ rows: [buildRow({ element: '<b>x</b>' })] }));

      const rowHeader = root().querySelector('tbody th') as HTMLElement;
      expect(rowHeader.textContent?.trim()).toBe('<b>x</b>');
      expect(rowHeader.querySelector('b')).toBeNull();
    });

    it('formats each count with Intl.NumberFormat, right-aligned with tabular numbers', () => {
      startStore();
      flushElements(buildResponse({ rows: [buildRow({ clicks: 12345 })] }));

      const cell = root().querySelector('tbody td.num') as HTMLElement;
      expect(cell.textContent?.trim()).toBe(new Intl.NumberFormat().format(12345));
      const style = getComputedStyle(cell);
      expect(style.textAlign).toBe('right');
      expect(style.fontVariantNumeric).toContain('tabular-nums');
    });

    it('shows the last-interaction time with a <time> element in the fixed local format', () => {
      startStore();
      flushElements(
        buildResponse({ rows: [buildRow({ lastInteractionAt: '2026-09-21T09:05:03.000Z' })] }),
      );

      const time = root().querySelector('tbody time') as HTMLTimeElement;
      expect(time.getAttribute('datetime')).toBe('2026-09-21T09:05:03.000Z');

      const date = new Date('2026-09-21T09:05:03.000Z');
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const expected = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
      expect(time.textContent?.trim()).toBe(expected);
    });

    it('formats the last-interaction time in a fixed zone against a literal string, not against a mirror of the code', () => {
      vi.stubEnv('TZ', 'UTC');
      try {
        startStore();
        flushElements(
          buildResponse({ rows: [buildRow({ lastInteractionAt: '2026-09-21T09:05:03.000Z' })] }),
        );

        const time = root().querySelector('tbody time') as HTMLTimeElement;
        expect(time.textContent?.trim()).toBe('2026-09-21 09:05:03');
      } finally {
        vi.unstubAllEnvs();
      }
    });

    it('names the time zone in the "Last interaction" column header', () => {
      startStore();
      flushElements(buildResponse());

      const expectedZone =
        new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
          .formatToParts(new Date())
          .find((part) => part.type === 'timeZoneName')?.value ?? '';

      const headers = Array.from(root().querySelectorAll('thead th'));
      const timeHeader = headers.find((h) => h.textContent?.includes('Last interaction'));
      expect(timeHeader?.textContent).toContain(expectedZone);
    });

    it('adds no CSS display value on a table element', () => {
      startStore();
      flushElements(buildResponse());

      const table = root().querySelector('table') as HTMLElement;
      expect(getComputedStyle(table).display).toBe('table');
      const tbody = root().querySelector('tbody') as HTMLElement;
      expect(getComputedStyle(tbody).display).toBe('table-row-group');
      const tr = root().querySelector('tbody tr') as HTMLElement;
      expect(getComputedStyle(tr).display).toBe('table-row');
      const td = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(td).display).toBe('table-cell');
      const th = root().querySelector('tbody th') as HTMLElement;
      expect(getComputedStyle(th).display).toBe('table-cell');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      startStore();
      flushElements(buildResponse());

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
    });

    it('wraps a long element name with overflow-wrap anywhere, and no ellipsis', () => {
      startStore();
      flushElements(buildResponse({ rows: [buildRow({ element: 'x'.repeat(300) })] }));

      const rowHeader = root().querySelector('tbody th') as HTMLElement;
      const style = getComputedStyle(rowHeader);
      expect(style.overflowWrap).toBe('anywhere');
      expect(style.textOverflow).not.toBe('ellipsis');
    });

    it('gives a number column white-space nowrap, so it does not break word by word', () => {
      startStore();
      flushElements(buildResponse());

      const numCell = root().querySelector('tbody td.num') as HTMLElement;
      expect(getComputedStyle(numCell).whiteSpace).toBe('nowrap');
    });

    it('gives the time column white-space nowrap, so it does not break word by word', () => {
      startStore();
      flushElements(buildResponse());

      const timeCell = root().querySelector('tbody td.time') as HTMLElement;
      expect(getComputedStyle(timeCell).whiteSpace).toBe('nowrap');
    });

    it('shows "No events yet." when the row list is empty', () => {
      startStore();
      flushElements(buildResponse({ rows: [] }));

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).toContain('No events yet.');
    });

    it('tracks each row with the stable key element: a second answer that drops a row and changes the order keeps the tr node of each surviving row', () => {
      startStore(10);
      flushElements(
        buildResponse({
          rows: [
            buildRow({ element: 'alpha' }),
            buildRow({ element: 'beta' }),
            buildRow({ element: 'gamma' }),
          ],
        }),
      );

      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));
      const trOfElement = new Map<string, Element>([
        ['alpha', rowsBefore[0]],
        ['beta', rowsBefore[1]],
        ['gamma', rowsBefore[2]],
      ]);

      vi.advanceTimersByTime(10_000);
      // The second answer drops beta, and it puts gamma before alpha.
      httpMock.expectOne('/api/apps/7/elements?userId=42').flush(
        buildResponse({
          rows: [buildRow({ element: 'gamma' }), buildRow({ element: 'alpha' })],
        }),
      );
      fixture.detectChanges();

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter.length).toBe(2);
      expect(rowsAfter[0]).toBe(trOfElement.get('gamma'));
      expect(rowsAfter[1]).toBe(trOfElement.get('alpha'));
    });
  });

  describe('a 404 on the app', () => {
    it('navigates to /apps with the app id, and does not render a table', () => {
      startStore();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      httpMock
        .expectOne('/api/apps/7/elements?userId=42')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '7' } });
      expect(root().querySelector('table')).toBeNull();
    });
  });

  describe('a change of the filter on the same component instance', () => {
    it('resets the table at once on a change of appId, and ignores a late answer of the old app', () => {
      startStore(10);
      flushElements(buildResponse({ rows: [buildRow({ element: 'app-7-element' })] }));

      // A poll tick starts a new app-7 request that stays in flight.
      vi.advanceTimersByTime(10_000);
      const staleRequest = httpMock.expectOne('/api/apps/7/elements?userId=42');

      // The route moves to app 8, on the same component instance.
      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).not.toContain('app-7-element');
      expect(staleRequest.cancelled).toBe(true);

      const freshRequest = httpMock.expectOne('/api/apps/8/elements?userId=42');
      freshRequest.flush(buildResponse({ rows: [buildRow({ element: 'app-8-element' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('app-8-element');
      expect(root().textContent).not.toContain('app-7-element');
    });

    it('sends a fresh request when userId changes on the same component instance, without a store reset', () => {
      startStore(10);
      flushElements(buildResponse({ rows: [buildRow({ element: 'user-42-element' })] }));

      fixture.componentRef.setInput('userId', '99');
      fixture.detectChanges();

      httpMock
        .expectOne('/api/apps/7/elements?userId=99')
        .flush(buildResponse({ rows: [buildRow({ element: 'user-99-element' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('user-99-element');
    });

    it('redirects to /apps when a 404 answer arrives for the new app', () => {
      startStore(10);
      flushElements(buildResponse({ rows: [buildRow({ element: 'app-7-element' })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      httpMock
        .expectOne('/api/apps/8/elements?userId=42')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '8' } });
    });
  });
});
