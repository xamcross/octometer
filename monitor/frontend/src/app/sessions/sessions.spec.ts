import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { Announcer } from '../announcer';
import { Sessions } from './sessions';
import type { SessionRow, SessionsResponse } from './session-row';

/** Builds one page answer. Each test overrides only the fields it checks. */
function buildPage(overrides: Partial<SessionsResponse> = {}): SessionsResponse {
  return {
    page: 1,
    pageCount: 1,
    rows: [buildRow()],
    ...overrides,
  };
}

/** Builds one session row. Each test overrides only the fields it checks. */
function buildRow(overrides: Partial<SessionRow> = {}): SessionRow {
  return {
    sessionId: '0b0e4e0e-6a55-4c1e-9a53-0c1f6f7a2d11',
    firstPath: '/pricing',
    source: 'google.com',
    startTime: '2026-09-21T09:05:03.000Z',
    clicks: 3,
    userId: null,
    ...overrides,
  };
}

describe('Sessions', () => {
  let fixture: ComponentFixture<Sessions>;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Sessions],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(Sessions);
    fixture.componentRef.setInput('appId', '7');
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

  /** Answers the one pending `GET /api/apps/7/sessions` request, with the given query. */
  function flushSessions(page: SessionsResponse, query = 'anonymous=true&page=1'): void {
    httpMock.expectOne(`/api/apps/7/sessions?${query}`).flush(page);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('creates the component', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=1').flush(buildPage());
  });

  it('shows a focusable heading', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toContain('Anonymous sessions');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('puts the refresh bar directly after the heading, and before the table', () => {
    startStore();
    flushSessions(buildPage());

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
    expect(root().textContent).toContain('Octometer reads the anonymous session list.');
  });

  it('sends the request with anonymous=true and the page query parameter of the route', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=1').flush(buildPage());
  });

  it('reads the page input from the route, so a reload and the Back button keep it', () => {
    fixture.componentRef.setInput('page', '3');
    fixture.detectChanges();
    startStore();
    httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=3').flush(buildPage({ page: 3 }));
  });

  it('sends no firstPath parameter when the route carries none', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=1').flush(buildPage());
  });

  it('sends the firstPath parameter, and shows the active first page above the table', () => {
    fixture.componentRef.setInput('firstPath', '/pricing');
    fixture.detectChanges();
    startStore();
    httpMock
      .expectOne('/api/apps/7/sessions?anonymous=true&firstPath=/pricing&page=1')
      .flush(buildPage());
    fixture.detectChanges();

    expect(root().textContent).toContain('First page: /pricing');
  });

  it('sends firstPath=(unknown), and shows "No anonymous session yet." for the empty answer (issue #213 tracks the field change)', () => {
    fixture.componentRef.setInput('firstPath', '(unknown)');
    fixture.detectChanges();
    startStore();
    httpMock
      .expectOne('/api/apps/7/sessions?anonymous=true&firstPath=(unknown)&page=1')
      .flush(buildPage({ rows: [] }));
    fixture.detectChanges();

    expect(root().querySelector('table')).toBeNull();
    expect(root().textContent).toContain('No anonymous session yet.');
    expect(root().textContent).toContain('First page: (unknown)');
  });

  describe('once the session list answers', () => {
    it('renders a table with a caption and a scoped row header for the session id', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ sessionId: 'session-zeta' })] }));

      const table = root().querySelector('table');
      expect(table).toBeTruthy();
      expect(table?.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);

      const rowHeader = table?.querySelector('tbody th') as HTMLTableCellElement;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.querySelector('a')?.textContent?.trim()).toBe('session-zeta');
    });

    it('gives each column header a scope of "col"', () => {
      startStore();
      flushSessions(buildPage());

      const headers = Array.from(root().querySelectorAll('thead th'));
      expect(headers.length).toBe(6);
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
      expect(headers[0].textContent?.trim()).toBe('Session');
      expect(headers[1].textContent?.trim()).toBe('First page');
      expect(headers[2].textContent?.trim()).toBe('Source');
      expect(headers[4].textContent?.trim()).toBe('Clicks');
      expect(headers[5].textContent?.trim()).toBe('User');
    });

    it('names the time zone in the "Start time" column header', () => {
      startStore();
      flushSessions(buildPage());

      const expectedZone =
        new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
          .formatToParts(new Date())
          .find((part) => part.type === 'timeZoneName')?.value ?? '';

      const headers = Array.from(root().querySelectorAll('thead th'));
      const timeHeader = headers.find((h) => h.textContent?.includes('Start time'));
      expect(timeHeader?.textContent).toContain(expectedZone);
    });

    it('shows one row for each anonymous session', () => {
      startStore();
      flushSessions(
        buildPage({
          rows: [buildRow({ sessionId: 'session-a' }), buildRow({ sessionId: 'session-b' })],
        }),
      );

      const rows = root().querySelectorAll('tbody tr');
      expect(rows.length).toBe(2);
      expect(rows[0].textContent).toContain('session-a');
      expect(rows[1].textContent).toContain('session-b');
    });

    it('shows a first path with HTML characters as plain text, never as markup', () => {
      startStore();
      flushSessions(
        buildPage({ rows: [buildRow({ firstPath: '/a<img src=x onerror=alert(1)>' })] }),
      );

      const cells = root().querySelectorAll('tbody td');
      const firstPathCell = cells[0] as HTMLElement;
      expect(firstPathCell.textContent?.trim()).toBe('/a<img src=x onerror=alert(1)>');
      expect(firstPathCell.querySelector('img')).toBeNull();
    });

    it('shows a source with HTML characters as plain text, never as markup', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ source: '<img src=x onerror=alert(1)>' })] }));

      const cells = root().querySelectorAll('tbody td');
      const sourceCell = cells[1] as HTMLElement;
      expect(sourceCell.textContent?.trim()).toBe('<img src=x onerror=alert(1)>');
      expect(sourceCell.querySelector('img')).toBeNull();
    });

    it('shows the text "(unknown)" for a session with no start row, and "(direct)" for a start with no referrer (D44)', () => {
      startStore();
      flushSessions(
        buildPage({ rows: [buildRow({ firstPath: '(unknown)', source: '(direct)' })] }),
      );

      const cells = root().querySelectorAll('tbody td');
      expect(cells[0].textContent?.trim()).toBe('(unknown)');
      expect(cells[1].textContent?.trim()).toBe('(direct)');
    });

    it('shows the session link to the elements view with the sessionId value', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ sessionId: 'session-42' })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/apps/7/elements?sessionId=session-42');
    });

    it('opens the session link on a click on a different cell of the row', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ sessionId: 'session-42' })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const cell = root().querySelector('tbody td') as HTMLElement;
      cell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'elements'], {
        queryParams: { sessionId: 'session-42' },
      });
    });

    it('does not navigate on a cell click while the user has text selected inside that cell', () => {
      startStore();
      flushSessions(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const cell = root().querySelector('tbody td') as HTMLElement;
      vi.spyOn(window, 'getSelection').mockReturnValue({
        isCollapsed: false,
        toString: () => 'text the user picked',
        containsNode: (node: Node) => node === cell,
      } as unknown as Selection);

      cell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a modifier key', () => {
      startStore();
      flushSessions(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const cell = root().querySelector('tbody td') as HTMLElement;

      cell.dispatchEvent(new MouseEvent('click', { bubbles: true, ctrlKey: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a button other than the primary button', () => {
      startStore();
      flushSessions(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const cell = root().querySelector('tbody td') as HTMLElement;

      cell.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 1 }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('shows the start time with a <time> element bound through the dateTime property, in the fixed local format', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ startTime: '2026-09-21T09:05:03.000Z' })] }));

      const time = root().querySelector('tbody time') as HTMLTimeElement;
      expect(time.dateTime).toBe('2026-09-21T09:05:03.000Z');

      const date = new Date('2026-09-21T09:05:03.000Z');
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const expected = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
      expect(time.textContent?.trim()).toBe(expected);
    });

    it('formats the start time in a fixed zone against a literal string, not against a mirror of the code', () => {
      vi.stubEnv('TZ', 'UTC');
      try {
        startStore();
        flushSessions(buildPage({ rows: [buildRow({ startTime: '2026-09-21T09:05:03.000Z' })] }));

        const time = root().querySelector('tbody time') as HTMLTimeElement;
        expect(time.textContent?.trim()).toBe('2026-09-21 09:05:03');
      } finally {
        vi.unstubAllEnvs();
      }
    });

    it('formats the click count with Intl.NumberFormat, right-aligned with tabular numbers', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ clicks: 12345 })] }));

      const cell = root().querySelector('tbody td.num') as HTMLElement;
      expect(cell.textContent?.trim()).toBe(new Intl.NumberFormat().format(12345));
      const style = getComputedStyle(cell);
      expect(style.textAlign).toBe('right');
      expect(style.fontVariantNumeric).toContain('tabular-nums');
    });

    it('shows "(anonymous)" for a user id of null, and the id as text otherwise', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ userId: null })] }));

      const cells = root().querySelectorAll('tbody td');
      expect(cells[cells.length - 1].textContent?.trim()).toBe('(anonymous)');
    });

    it('shows a signed-in user id of the session as plain text', () => {
      startStore();
      flushSessions(buildPage({ rows: [buildRow({ userId: 'user-9' })] }));

      const cells = root().querySelectorAll('tbody td');
      expect(cells[cells.length - 1].textContent?.trim()).toBe('user-9');
    });

    it('adds no CSS display value on a table element', () => {
      startStore();
      flushSessions(buildPage());

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

    it('gives the session link a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushSessions(buildPage());

      const link = root().querySelector('tbody th a') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      startStore();
      flushSessions(buildPage());

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
    });

    it('gives a pointer cursor to each data cell of a row', () => {
      startStore();
      flushSessions(buildPage());

      const cell = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(cell).cursor).toBe('pointer');
    });

    it('shows "No anonymous session yet." when the row list is empty', () => {
      startStore();
      flushSessions(buildPage({ rows: [] }));

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).toContain('No anonymous session yet.');
    });

    it('tracks each row with the stable key sessionId: a second answer that drops a row and changes the order keeps the tr node of each surviving row', () => {
      startStore(10);
      flushSessions(
        buildPage({
          rows: [
            buildRow({ sessionId: 'session-alpha' }),
            buildRow({ sessionId: 'session-beta' }),
            buildRow({ sessionId: 'session-gamma' }),
          ],
        }),
      );

      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));
      const trOfSession = new Map<string, Element>([
        ['session-alpha', rowsBefore[0]],
        ['session-beta', rowsBefore[1]],
        ['session-gamma', rowsBefore[2]],
      ]);

      vi.advanceTimersByTime(10_000);
      // The second answer drops beta, and it puts gamma before alpha.
      httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=1').flush(
        buildPage({
          rows: [
            buildRow({ sessionId: 'session-gamma' }),
            buildRow({ sessionId: 'session-alpha' }),
          ],
        }),
      );
      fixture.detectChanges();

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter.length).toBe(2);
      expect(rowsAfter[0]).toBe(trOfSession.get('session-gamma'));
      expect(rowsAfter[1]).toBe(trOfSession.get('session-alpha'));
    });
  });

  describe('the pager', () => {
    it('shows "Previous", "Next", and "Page 2 of 100"', () => {
      startStore();
      flushSessions(buildPage({ page: 2, pageCount: 100 }));

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.textContent).toContain('Previous');
      expect(nav.textContent).toContain('Next');
      expect(nav.textContent).toContain('Page 2 of 100');
    });

    it('gives the pager landmark a name that says what it is', () => {
      startStore();
      flushSessions(buildPage());

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.getAttribute('aria-label')).toBe('Pages of the anonymous session list');
    });

    it('disables Previous with aria-disabled on page 1, and does not navigate on a click', () => {
      startStore();
      flushSessions(buildPage({ page: 1, pageCount: 5 }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      expect(previous.getAttribute('aria-disabled')).toBe('true');

      previous.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('disables Next with aria-disabled on the last page, and does not navigate on a click', () => {
      fixture.componentRef.setInput('page', '5');
      fixture.detectChanges();
      startStore();
      httpMock
        .expectOne('/api/apps/7/sessions?anonymous=true&page=5')
        .flush(buildPage({ page: 5, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      expect(next.getAttribute('aria-disabled')).toBe('true');

      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('navigates to the next page on a click on Next, and keeps the firstPath filter', () => {
      fixture.componentRef.setInput('firstPath', '/pricing');
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      startStore();
      httpMock
        .expectOne('/api/apps/7/sessions?anonymous=true&firstPath=/pricing&page=2')
        .flush(buildPage({ page: 2, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'sessions'], {
        queryParams: { anonymous: 'true', firstPath: '/pricing', page: 3 },
      });
    });

    it('re-sends the request after a query-parameter navigation moves the page', () => {
      startStore();
      flushSessions(buildPage({ page: 1, pageCount: 5 }));

      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();

      httpMock
        .expectOne('/api/apps/7/sessions?anonymous=true&page=2')
        .flush(buildPage({ page: 2, pageCount: 5 }));
    });

    it('announces the row count and the new page after a Next click, once the answer arrives (lesson 6 of the frontend brief)', () => {
      startStore();
      flushSessions(buildPage({ page: 1, pageCount: 5 }));
      const announcer = TestBed.inject(Announcer);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'sessions'], {
        queryParams: { anonymous: 'true', firstPath: null, page: 2 },
      });
      vi.advanceTimersByTime(200);
      // The click itself changes no route input (navigate is mocked), so
      // the region stays silent until the router echoes the new page back.
      expect(announcer.message()).toBe('');

      // Simulates the router echoing the navigated page back as the route input.
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=2').flush(
        buildPage({
          page: 2,
          pageCount: 5,
          rows: [buildRow(), buildRow({ sessionId: 'session-other' })],
        }),
      );
      fixture.detectChanges();
      vi.advanceTimersByTime(200);

      expect(announcer.message()).toBe('2 anonymous sessions. Page 2 of 5.');
    });

    it('gives the pager buttons a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushSessions(buildPage({ page: 2, pageCount: 5 }));

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      for (const button of buttons) {
        const style = getComputedStyle(button);
        expect(style.minWidth).toBe('24px');
        expect(style.minHeight).toBe('24px');
      }
    });
  });

  describe('a 404 on the app', () => {
    it('navigates to /apps with the app id, and does not render a table', () => {
      startStore();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      httpMock
        .expectOne('/api/apps/7/sessions?anonymous=true&page=1')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '7' } });
      expect(root().querySelector('table')).toBeNull();
    });
  });

  describe('a change of appId on the same component instance', () => {
    it('resets the table at once, and ignores a late answer of the old app', () => {
      startStore(10);
      flushSessions(buildPage({ rows: [buildRow({ sessionId: 'app-7-session' })] }));

      vi.advanceTimersByTime(10_000);
      const staleRequest = httpMock.expectOne('/api/apps/7/sessions?anonymous=true&page=1');

      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).not.toContain('app-7-session');
      expect(staleRequest.cancelled).toBe(true);

      const freshRequest = httpMock.expectOne('/api/apps/8/sessions?anonymous=true&page=1');
      freshRequest.flush(buildPage({ rows: [buildRow({ sessionId: 'app-8-session' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('app-8-session');
      expect(root().textContent).not.toContain('app-7-session');
    });
  });
});
