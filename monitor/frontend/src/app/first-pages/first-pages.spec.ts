import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { Announcer } from '../announcer';
import { FirstPages } from './first-pages';
import type { FirstPageRow, FirstPagesResponse } from './first-page-row';

/** Builds one page answer. Each test overrides only the fields it checks. */
function buildPage(overrides: Partial<FirstPagesResponse> = {}): FirstPagesResponse {
  return {
    page: 1,
    pageCount: 1,
    rows: [buildRow()],
    ...overrides,
  };
}

/** Builds one first-page row. Each test overrides only the fields it checks. */
function buildRow(overrides: Partial<FirstPageRow> = {}): FirstPageRow {
  return {
    path: '/pricing',
    sessions: 42,
    ...overrides,
  };
}

describe('FirstPages', () => {
  let fixture: ComponentFixture<FirstPages>;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FirstPages],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(FirstPages);
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

  /** Answers the one pending `GET /api/apps/7/first-pages` request, with the given query. */
  function flushFirstPages(page: FirstPagesResponse, query = 'page=1'): void {
    httpMock.expectOne(`/api/apps/7/first-pages?${query}`).flush(page);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('creates the component', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/first-pages?page=1').flush(buildPage());
  });

  it('shows a focusable heading', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toContain('First pages');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('puts the refresh bar directly after the heading, and before the table', () => {
    startStore();
    flushFirstPages(buildPage());

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
    expect(root().textContent).toContain('Octometer reads the first page list.');
  });

  it('sends the request with the page query parameter of the route', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/first-pages?page=1').flush(buildPage());
  });

  it('reads the page input from the route, so a reload and the Back button keep it', () => {
    fixture.componentRef.setInput('page', '3');
    fixture.detectChanges();
    startStore();
    httpMock.expectOne('/api/apps/7/first-pages?page=3').flush(buildPage({ page: 3 }));
  });

  describe('once the first-page list answers', () => {
    it('renders a table with a caption and a scoped row header for the path', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [buildRow({ path: '/zeta' })] }));

      const table = root().querySelector('table');
      expect(table).toBeTruthy();
      expect(table?.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);

      const rowHeader = table?.querySelector('tbody th') as HTMLTableCellElement;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.querySelector('a')?.textContent?.trim()).toBe('/zeta');
    });

    it('gives each column header a scope of "col"', () => {
      startStore();
      flushFirstPages(buildPage());

      const headers = Array.from(root().querySelectorAll('thead th'));
      expect(headers.length).toBe(2);
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
      expect(headers[0].textContent?.trim()).toBe('First page');
      expect(headers[1].textContent?.trim()).toBe('Sessions');
    });

    it('shows one row for each first page, with its session count', () => {
      startStore();
      flushFirstPages(
        buildPage({
          rows: [buildRow({ path: '/a', sessions: 3 }), buildRow({ path: '/b', sessions: 9 })],
        }),
      );

      const rows = root().querySelectorAll('tbody tr');
      expect(rows.length).toBe(2);
      expect(rows[0].textContent).toContain('/a');
      expect(rows[1].textContent).toContain('/b');
    });

    it('shows a path with HTML characters as plain text, never as markup (D5, security review of #210)', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [buildRow({ path: '/a<img src=x onerror=alert(1)>' })] }));

      const rowHeader = root().querySelector('tbody th') as HTMLElement;
      expect(rowHeader.textContent?.trim()).toBe('/a<img src=x onerror=alert(1)>');
      expect(rowHeader.querySelector('img')).toBeNull();
    });

    it('formats the session count with Intl.NumberFormat, right-aligned with tabular numbers', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [buildRow({ sessions: 12345 })] }));

      const cell = root().querySelector('tbody td.num') as HTMLElement;
      expect(cell.textContent?.trim()).toBe(new Intl.NumberFormat().format(12345));
      const style = getComputedStyle(cell);
      expect(style.textAlign).toBe('right');
      expect(style.fontVariantNumeric).toContain('tabular-nums');
    });

    it('links the path cell to the sessions view with anonymous=true and the firstPath value', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [buildRow({ path: '/pricing' })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe(
        '/apps/7/sessions?anonymous=true&firstPath=%2Fpricing',
      );
    });

    it('opens the row link on a click on a different cell of the row', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [buildRow({ path: '/pricing' })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const numCell = root().querySelector('td.num') as HTMLElement;
      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'sessions'], {
        queryParams: { anonymous: 'true', firstPath: '/pricing' },
      });
    });

    it('does not navigate on a cell click while the user has text selected inside that cell', () => {
      startStore();
      flushFirstPages(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;
      vi.spyOn(window, 'getSelection').mockReturnValue({
        isCollapsed: false,
        toString: () => 'a number the user picked',
        containsNode: (node: Node) => node === numCell,
      } as unknown as Selection);

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a modifier key', () => {
      startStore();
      flushFirstPages(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, ctrlKey: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a button other than the primary button', () => {
      startStore();
      flushFirstPages(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 1 }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('adds no CSS display value on a table element', () => {
      startStore();
      flushFirstPages(buildPage());

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

    it('gives the path link a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushFirstPages(buildPage());

      const link = root().querySelector('tbody th a') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      startStore();
      flushFirstPages(buildPage());

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
    });

    it('renders two rows that share the text "(unknown)" (issue #213 tracks the present behaviour)', () => {
      startStore();

      // The API of #112 maps every NULL path to the same text "(unknown)"
      // (D44), so `track row.path` gives Angular a duplicate key across
      // these two rows. Both rows still render with the right count.
      // Issue #213 later changes the field to `path: string | null`, so
      // Angular can tell the two rows apart by identity instead.
      flushFirstPages(
        buildPage({
          rows: [
            buildRow({ path: '(unknown)', sessions: 5 }),
            buildRow({ path: '(unknown)', sessions: 2 }),
          ],
        }),
      );

      const rows = root().querySelectorAll('tbody tr');
      expect(rows.length).toBe(2);
      expect(rows[0].textContent).toContain(new Intl.NumberFormat().format(5));
      expect(rows[1].textContent).toContain(new Intl.NumberFormat().format(2));
    });

    it('tracks each row with the stable key path: a second answer that drops a row and changes the order keeps the tr node of each surviving row', () => {
      startStore(10);
      flushFirstPages(
        buildPage({
          rows: [
            buildRow({ path: '/alpha' }),
            buildRow({ path: '/beta' }),
            buildRow({ path: '/gamma' }),
          ],
        }),
      );

      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));
      const trOfPath = new Map<string, Element>([
        ['/alpha', rowsBefore[0]],
        ['/beta', rowsBefore[1]],
        ['/gamma', rowsBefore[2]],
      ]);

      vi.advanceTimersByTime(10_000);
      // The second answer drops beta, and it puts gamma before alpha.
      httpMock
        .expectOne('/api/apps/7/first-pages?page=1')
        .flush(buildPage({ rows: [buildRow({ path: '/gamma' }), buildRow({ path: '/alpha' })] }));
      fixture.detectChanges();

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter.length).toBe(2);
      expect(rowsAfter[0]).toBe(trOfPath.get('/gamma'));
      expect(rowsAfter[1]).toBe(trOfPath.get('/alpha'));
    });

    it('gives a pointer cursor to each data cell of a row', () => {
      startStore();
      flushFirstPages(buildPage());

      const cell = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(cell).cursor).toBe('pointer');
    });

    it('shows "No first page yet." when the row list is empty', () => {
      startStore();
      flushFirstPages(buildPage({ rows: [] }));

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).toContain('No first page yet.');
    });
  });

  describe('the pager', () => {
    it('shows "Previous", "Next", and "Page X of Y" at page 1', () => {
      startStore();
      flushFirstPages(buildPage({ page: 1, pageCount: 5 }));

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.textContent).toContain('Previous');
      expect(nav.textContent).toContain('Next');
      expect(nav.textContent).toContain('Page 1 of 5');
    });

    it('gives the pager landmark a name that says what it is', () => {
      startStore();
      flushFirstPages(buildPage());

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.getAttribute('aria-label')).toBe('Pages of the first page list');
    });

    it('disables Previous with aria-disabled on page 1, and does not navigate on a click', () => {
      startStore();
      flushFirstPages(buildPage({ page: 1, pageCount: 5 }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      expect(previous.getAttribute('aria-disabled')).toBe('true');

      previous.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('shows neither button disabled in the middle of the page range, and navigates on either click', () => {
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      startStore();
      httpMock
        .expectOne('/api/apps/7/first-pages?page=2')
        .flush(buildPage({ page: 2, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      expect(previous.hasAttribute('aria-disabled')).toBe(false);
      expect(next.hasAttribute('aria-disabled')).toBe(false);

      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'first-pages'], {
        queryParams: { page: 3 },
      });

      previous.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'first-pages'], {
        queryParams: { page: 1 },
      });
    });

    it('disables Next with aria-disabled on the last page, and does not navigate on a click', () => {
      fixture.componentRef.setInput('page', '5');
      fixture.detectChanges();
      startStore();
      httpMock
        .expectOne('/api/apps/7/first-pages?page=5')
        .flush(buildPage({ page: 5, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      expect(next.getAttribute('aria-disabled')).toBe('true');

      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('re-sends the request after a query-parameter navigation moves the page', () => {
      startStore();
      flushFirstPages(buildPage({ page: 1, pageCount: 5 }));

      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();

      httpMock
        .expectOne('/api/apps/7/first-pages?page=2')
        .flush(buildPage({ page: 2, pageCount: 5 }));
    });

    it('announces the row count and the new page after a Next click, once the answer arrives (MAJOR 1 of the review of #216)', () => {
      startStore();
      flushFirstPages(buildPage({ page: 1, pageCount: 5 }));
      const announcer = TestBed.inject(Announcer);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'first-pages'], {
        queryParams: { page: 2 },
      });
      vi.advanceTimersByTime(200);
      // The click itself changes no route input (navigate is mocked), so
      // the region stays silent until the router echoes the new page back.
      expect(announcer.message()).toBe('');

      // Simulates the router echoing the navigated page back as the route input.
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      httpMock.expectOne('/api/apps/7/first-pages?page=2').flush(
        buildPage({
          page: 2,
          pageCount: 5,
          rows: [buildRow(), buildRow({ path: '/other' })],
        }),
      );
      fixture.detectChanges();
      vi.advanceTimersByTime(200);

      expect(announcer.message()).toBe('2 first pages. Page 2 of 5.');
    });

    it('gives the pager buttons a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushFirstPages(buildPage({ page: 2, pageCount: 5 }));

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
        .expectOne('/api/apps/7/first-pages?page=1')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '7' } });
      expect(root().querySelector('table')).toBeNull();
    });
  });

  describe('a change of appId on the same component instance', () => {
    it('resets the table at once, and ignores a late answer of the old app', () => {
      startStore(10);
      flushFirstPages(buildPage({ rows: [buildRow({ path: '/app-7-path' })] }));

      vi.advanceTimersByTime(10_000);
      const staleRequest = httpMock.expectOne('/api/apps/7/first-pages?page=1');

      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).not.toContain('app-7-path');
      expect(staleRequest.cancelled).toBe(true);

      const freshRequest = httpMock.expectOne('/api/apps/8/first-pages?page=1');
      freshRequest.flush(buildPage({ rows: [buildRow({ path: '/app-8-path' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('app-8-path');
      expect(root().textContent).not.toContain('app-7-path');
    });
  });
});
