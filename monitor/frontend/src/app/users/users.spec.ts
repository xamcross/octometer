import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { Announcer } from '../announcer';
import type { UserRow, UserTotalsResponse } from './user-row';
import { Users } from './users';

/** Builds one page answer. Each test overrides only the fields it checks. */
function buildPage(overrides: Partial<UserTotalsResponse> = {}): UserTotalsResponse {
  return {
    page: 1,
    pageCount: 1,
    rows: [buildRow()],
    ...overrides,
  };
}

/** Builds one user row. Each test overrides only the fields it checks. */
function buildRow(overrides: Partial<UserRow> = {}): UserRow {
  return {
    userId: 'user-42',
    clicks: 1234,
    sessions: 12,
    uniqueElements: 7,
    ...overrides,
  };
}

describe('Users', () => {
  let fixture: ComponentFixture<Users>;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Users],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(Users);
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

  /** Answers the one pending `GET /api/apps/7/users` request, with the given query. */
  function flushUsers(page: UserTotalsResponse, query = 'page=1'): void {
    httpMock.expectOne(`/api/apps/7/users?${query}`).flush(page);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('creates the component', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/users?page=1').flush(buildPage());
  });

  it('shows a focusable heading', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toContain('Users');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('puts the refresh bar directly after the heading', () => {
    startStore();
    flushUsers(buildPage());

    const children = Array.from(root().children);
    const headingIndex = children.findIndex((el) => el.tagName === 'H1');
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');

    expect(headingIndex).toBe(0);
    expect(refreshBarIndex).toBe(1);
  });

  it('keeps the refresh bar before the table', () => {
    startStore();
    flushUsers(buildPage());

    const children = Array.from(root().children);
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');
    const tableWrapperIndex = children.findIndex((el) => el.classList.contains('table-scroll'));

    expect(tableWrapperIndex).toBeGreaterThan(refreshBarIndex);
  });

  it('shows a loading text before the first answer arrives, and no table', () => {
    expect(root().querySelector('table')).toBeNull();
    expect(root().textContent).toContain('Octometer reads the user list.');
  });

  it('sends the request with the page query parameter of the route', () => {
    startStore();
    httpMock.expectOne('/api/apps/7/users?page=1').flush(buildPage());
  });

  it('reads the page and q input from the route, so a reload keeps them', () => {
    fixture.componentRef.setInput('page', '3');
    fixture.componentRef.setInput('q', 'bob');
    fixture.detectChanges();
    startStore();
    httpMock.expectOne('/api/apps/7/users?page=3&q=bob').flush(buildPage({ page: 3 }));
  });

  describe('once the user list answers', () => {
    it('renders a table with a caption and a scoped row header for the user id', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: 'zeta-1' })] }));

      const table = root().querySelector('table');
      expect(table).toBeTruthy();
      expect(table?.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);

      const rowHeader = table?.querySelector('tbody th') as HTMLTableCellElement;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.querySelector('a')?.textContent?.trim()).toBe('zeta-1');
    });

    it('gives each column header a scope of "col"', () => {
      startStore();
      flushUsers(buildPage());

      const headers = Array.from(root().querySelectorAll('thead th'));
      expect(headers.length).toBeGreaterThan(0);
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
    });

    it('shows "(anonymous)" for a row with userId null, as plain text', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: null })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.textContent?.trim()).toBe('(anonymous)');
    });

    it('links the anonymous row to the elements view with anonymous=true', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: null })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/apps/7/elements?anonymous=true');
    });

    it('links a user row to the elements view with the URL-encoded userId', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: 'a b/c' })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/apps/7/elements?userId=a%20b%2Fc');
    });

    it('shows a user id with HTML characters as plain text, never as markup', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: '<b>x</b>' })] }));

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.textContent?.trim()).toBe('<b>x</b>');
      expect(link.querySelector('b')).toBeNull();
    });

    it('formats each count with Intl.NumberFormat, right-aligned with tabular numbers', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ clicks: 12345 })] }));

      const cell = root().querySelector('tbody td.num') as HTMLElement;
      expect(cell.textContent?.trim()).toBe(new Intl.NumberFormat().format(12345));
      const style = getComputedStyle(cell);
      expect(style.textAlign).toBe('right');
      expect(style.fontVariantNumeric).toContain('tabular-nums');
    });

    it('opens the row link on a click on a different cell of the row', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: 'row-user' })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const numCell = root().querySelector('td.num') as HTMLElement;
      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'elements'], {
        queryParams: { userId: 'row-user' },
      });
    });

    it('opens the anonymous row link on a click on a different cell of the row', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: null })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const numCell = root().querySelector('td.num') as HTMLElement;
      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'elements'], {
        queryParams: { anonymous: 'true' },
      });
    });

    it('does not navigate on a cell click while the user has text selected inside that cell', () => {
      startStore();
      flushUsers(buildPage());
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
      flushUsers(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, ctrlKey: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a button other than the primary button', () => {
      startStore();
      flushUsers(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 1 }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('adds no CSS display value on a table element', () => {
      startStore();
      flushUsers(buildPage());

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

    it('gives the row link a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushUsers(buildPage());

      const link = root().querySelector('tbody th a') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      startStore();
      flushUsers(buildPage());

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
    });

    it('wraps a long user id with overflow-wrap anywhere, and no ellipsis', () => {
      startStore();
      flushUsers(buildPage({ rows: [buildRow({ userId: 'x'.repeat(300) })] }));

      const rowHeader = root().querySelector('tbody th') as HTMLElement;
      const style = getComputedStyle(rowHeader);
      expect(style.overflowWrap).toBe('anywhere');
      expect(style.textOverflow).not.toBe('ellipsis');
    });

    it('gives a pointer cursor to each data cell of a row', () => {
      startStore();
      flushUsers(buildPage());

      const cell = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(cell).cursor).toBe('pointer');
    });

    it('gives a number column white-space nowrap, so it does not break word by word', () => {
      startStore();
      flushUsers(buildPage());

      const numCell = root().querySelector('tbody td.num') as HTMLElement;
      expect(getComputedStyle(numCell).whiteSpace).toBe('nowrap');
    });

    it('shows "No app user has clicked yet." when the row list is empty and q is not set', () => {
      startStore();
      flushUsers(buildPage({ rows: [] }));

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).toContain('No app user has clicked yet.');
    });

    it('shows a filter-specific message when the row list is empty and q is set', () => {
      fixture.componentRef.setInput('q', 'zz');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=1&q=zz').flush(buildPage({ rows: [] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('No user id matches "zz".');
    });

    it('tracks each row with the stable key userId: a second answer that drops a row and changes the order keeps the tr node of each surviving row', () => {
      startStore(10);
      flushUsers(
        buildPage({
          rows: [
            buildRow({ userId: 'alpha' }),
            buildRow({ userId: 'beta' }),
            buildRow({ userId: 'gamma' }),
          ],
        }),
      );

      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));
      const trOfUserId = new Map<string, Element>([
        ['alpha', rowsBefore[0]],
        ['beta', rowsBefore[1]],
        ['gamma', rowsBefore[2]],
      ]);

      vi.advanceTimersByTime(10_000);
      // The second answer drops beta, and it puts gamma before alpha.
      httpMock
        .expectOne('/api/apps/7/users?page=1')
        .flush(buildPage({ rows: [buildRow({ userId: 'gamma' }), buildRow({ userId: 'alpha' })] }));
      fixture.detectChanges();

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter.length).toBe(2);
      expect(rowsAfter[0]).toBe(trOfUserId.get('gamma'));
      expect(rowsAfter[1]).toBe(trOfUserId.get('alpha'));
    });

    it('keeps a focus outside tbody across a refresh', () => {
      startStore(10);
      flushUsers(buildPage({ rows: [buildRow({ userId: 'alpha', clicks: 1 })] }));

      const filterInput = root().querySelector('#user-filter') as HTMLElement;
      filterInput.focus();

      vi.advanceTimersByTime(10_000);
      httpMock
        .expectOne('/api/apps/7/users?page=1')
        .flush(buildPage({ rows: [buildRow({ userId: 'alpha', clicks: 2 })] }));
      fixture.detectChanges();

      expect(document.activeElement).toBe(filterInput);
    });
  });

  describe('the filter field', () => {
    it('has a label', () => {
      const input = root().querySelector('#user-filter') as HTMLInputElement;
      const label = root().querySelector('label[for="user-filter"]');
      expect(input).toBeTruthy();
      expect(label?.textContent?.trim().length).toBeGreaterThan(0);
    });

    it('shows the q query parameter as the initial value', () => {
      fixture.componentRef.setInput('q', 'carol');
      fixture.detectChanges();

      const input = root().querySelector('#user-filter') as HTMLInputElement;
      expect(input.value).toBe('carol');

      // A q change also triggers a request, the same way a route change does.
      httpMock.expectOne('/api/apps/7/users?page=1&q=carol').flush(buildPage());
    });

    it('sends the request after a debounce, with the page reset to 1', () => {
      startStore();
      flushUsers(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const input = root().querySelector('#user-filter') as HTMLInputElement;
      input.value = 'dan';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(navigateSpy).not.toHaveBeenCalled();
      vi.advanceTimersByTime(399);
      expect(navigateSpy).not.toHaveBeenCalled();
      vi.advanceTimersByTime(1);

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: null, q: 'dan' },
      });
    });

    it('sends the request at once on a submit of the filter form, with no debounce wait', () => {
      startStore();
      flushUsers(buildPage());
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const input = root().querySelector('#user-filter') as HTMLInputElement;
      input.value = 'erin';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      const form = root().querySelector('form') as HTMLFormElement;
      form.dispatchEvent(new Event('submit', { cancelable: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: null, q: 'erin' },
      });

      // Drains the debounce timer that the same key stroke also started.
      vi.advanceTimersByTime(500);
    });

    it('clears q on a submit with an empty filter value', () => {
      fixture.componentRef.setInput('q', 'carol');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=1&q=carol').flush(buildPage());
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const input = root().querySelector('#user-filter') as HTMLInputElement;
      input.value = '';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      const form = root().querySelector('form') as HTMLFormElement;
      form.dispatchEvent(new Event('submit', { cancelable: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: null, q: null },
      });

      // Drains the debounce timer that the same key stroke also started.
      vi.advanceTimersByTime(500);
    });

    it('gives the filter button a minimum target size of 24 by 24 CSS px', () => {
      const button = root().querySelector('.filter-form button') as HTMLElement;
      const style = getComputedStyle(button);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('gives the filter field a minimum height of 24 CSS px (MAJOR 3 of the accessibility review of #159)', () => {
      const input = root().querySelector('#user-filter') as HTMLElement;
      expect(getComputedStyle(input).minHeight).toBe('24px');
    });

    it('limits the filter field to 200 characters, the maximum length the API accepts (MINOR 1 of the Angular review of #159)', () => {
      const input = root().querySelector('#user-filter') as HTMLInputElement;
      expect(input.maxLength).toBe(200);
    });

    it('does not navigate again when the filter is submitted with the unchanged value (MINOR 2 of the Angular review of #159)', () => {
      fixture.componentRef.setInput('q', 'carol');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=1&q=carol').flush(buildPage());
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const form = root().querySelector('form') as HTMLFormElement;
      form.dispatchEvent(new Event('submit', { cancelable: true }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('announces the number of users that match, after the answer arrives (accessibility BLOCKER 1 of #159)', () => {
      startStore();
      flushUsers(buildPage());
      const announcer = TestBed.inject(Announcer);

      fixture.componentRef.setInput('q', 'dan');
      fixture.detectChanges();
      vi.advanceTimersByTime(200);
      expect(announcer.message()).toBe('');

      httpMock
        .expectOne('/api/apps/7/users?page=1&q=dan')
        .flush(buildPage({ rows: [buildRow(), buildRow({ userId: 'other' })] }));
      fixture.detectChanges();
      vi.advanceTimersByTime(200);

      expect(announcer.message()).toContain('2 users match');
    });

    it('announces "No user matches" when the filter finds no row (accessibility BLOCKER 1 of #159)', () => {
      startStore();
      flushUsers(buildPage());
      const announcer = TestBed.inject(Announcer);

      fixture.componentRef.setInput('q', 'zzz');
      fixture.detectChanges();
      httpMock.expectOne('/api/apps/7/users?page=1&q=zzz').flush(buildPage({ rows: [] }));
      fixture.detectChanges();
      vi.advanceTimersByTime(200);

      expect(announcer.message()).toContain('No user matches');
    });
  });

  describe('the pager', () => {
    it('shows "Previous", "Next", and "Page X of Y"', () => {
      startStore();
      flushUsers(buildPage({ page: 2, pageCount: 100 }));

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.textContent).toContain('Previous');
      expect(nav.textContent).toContain('Next');
      expect(nav.textContent).toContain('Page 2 of 100');
    });

    it('gives the current page text aria-current="true", so only the breadcrumb holds aria-current="page" (MINOR 1 of the accessibility review of #159)', () => {
      startStore();
      flushUsers(buildPage({ page: 2, pageCount: 100 }));

      const current = root().querySelector('[aria-current="true"]');
      expect(current?.textContent).toContain('Page 2 of 100');
      expect(root().querySelector('nav.pager [aria-current="page"]')).toBeNull();
    });

    it('gives the pager landmark a name that says what it is (MINOR 2 of the accessibility review of #159)', () => {
      startStore();
      flushUsers(buildPage());

      const nav = root().querySelector('nav.pager') as HTMLElement;
      expect(nav.getAttribute('aria-label')).toBe('Pages of the user list');
    });

    it('disables Previous with aria-disabled on page 1, and does not navigate on a click', () => {
      startStore();
      flushUsers(buildPage({ page: 1, pageCount: 5 }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      expect(previous.getAttribute('aria-disabled')).toBe('true');

      previous.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('gives a disabled pager button a different color and cursor than an enabled one (MAJOR 2 of the accessibility review of #159)', () => {
      startStore();
      flushUsers(buildPage({ page: 1, pageCount: 5 }));

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;

      expect(previous.getAttribute('aria-disabled')).toBe('true');
      expect(next.hasAttribute('aria-disabled')).toBe(false);
      expect(getComputedStyle(previous).cursor).toBe('default');
      expect(getComputedStyle(previous).color).not.toBe(getComputedStyle(next).color);
    });

    it('disables Next with aria-disabled on the last page, and does not navigate on a click', () => {
      fixture.componentRef.setInput('page', '5');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=5').flush(buildPage({ page: 5, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      expect(next.getAttribute('aria-disabled')).toBe('true');

      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('navigates to the next page on a click on Next', () => {
      fixture.componentRef.setInput('page', '2');
      fixture.componentRef.setInput('q', 'sam');
      fixture.detectChanges();
      startStore();
      httpMock
        .expectOne('/api/apps/7/users?page=2&q=sam')
        .flush(buildPage({ page: 2, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: 3, q: 'sam' },
      });
    });

    it('navigates to the previous page on a click on Previous', () => {
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=2').flush(buildPage({ page: 2, pageCount: 5 }));
      fixture.detectChanges();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const previous = buttons.find((b) => b.textContent?.trim() === 'Previous') as HTMLElement;
      previous.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: 1, q: null },
      });
    });

    it('announces the new page only after the answer arrives, not before the request (MINOR 4 of the accessibility review of #159)', () => {
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();
      startStore();
      httpMock.expectOne('/api/apps/7/users?page=2').flush(buildPage({ page: 2, pageCount: 5 }));
      fixture.detectChanges();
      const announcer = TestBed.inject(Announcer);
      // Drains the announcement of this first load of page 2, so it does
      // not confuse the check below, which is about the click on Next.
      vi.advanceTimersByTime(200);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const buttons = Array.from(root().querySelectorAll('nav.pager button'));
      const next = buttons.find((b) => b.textContent?.trim() === 'Next') as HTMLElement;
      next.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', '7', 'users'], {
        queryParams: { page: 3, q: null },
      });
      vi.advanceTimersByTime(200);
      // The click itself changes no route input (navigate is mocked), so
      // the region still holds the text of the last real answer: page 2.
      // It never jumps to "Page 3" before the server confirms it.
      expect(announcer.message()).toContain('Page 2 of 5');

      // Simulates the router echoing the navigated page back as the route input.
      fixture.componentRef.setInput('page', '3');
      fixture.detectChanges();
      httpMock.expectOne('/api/apps/7/users?page=3').flush(buildPage({ page: 3, pageCount: 5 }));
      fixture.detectChanges();
      vi.advanceTimersByTime(200);

      expect(announcer.message()).toContain('Page 3 of 5');
    });

    it('re-sends the request after a query-parameter navigation moves the page', () => {
      startStore();
      flushUsers(buildPage({ page: 1, pageCount: 5 }));

      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();

      httpMock.expectOne('/api/apps/7/users?page=2').flush(buildPage({ page: 2, pageCount: 5 }));
    });
  });

  describe('a 404 on the app', () => {
    it('navigates to /apps with the app id, and does not render a table', () => {
      startStore();
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      httpMock
        .expectOne('/api/apps/7/users?page=1')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '7' } });
      expect(root().querySelector('table')).toBeNull();
    });
  });

  describe('a failed data request (MAJOR 1 of the Angular review of #159)', () => {
    it('shows the fixed sentence of the API for a 400 answer', () => {
      startStore();
      httpMock
        .expectOne('/api/apps/7/users?page=1')
        .flush({ error: 'The filter q is too long.' }, { status: 400, statusText: 'Bad Request' });
      fixture.detectChanges();

      expect(root().querySelector('.error-text')?.getAttribute('role')).toBe('status');
      expect(root().querySelector('.error-text')?.textContent?.trim()).toBe(
        'The filter q is too long.',
      );
    });

    it('shows a general sentence for a network failure, and clears it after a good answer', () => {
      startStore(10);
      httpMock.expectOne('/api/apps/7/users?page=1').error(new ProgressEvent('error'));
      fixture.detectChanges();

      expect(root().querySelector('.error-text')?.textContent?.trim()).toBe(
        'Octometer cannot reach the API. Check the network connection.',
      );

      vi.advanceTimersByTime(10_000);
      httpMock.expectOne('/api/apps/7/users?page=1').flush(buildPage());
      fixture.detectChanges();

      expect(root().querySelector('.error-text')).toBeNull();
    });
  });

  describe('a change of appId on the same component instance (MAJOR 2 of the Angular review of #159)', () => {
    it('resets the table at once, and ignores a late answer of the old app', () => {
      startStore(10);
      flushUsers(buildPage({ rows: [buildRow({ userId: 'app-7-user' })] }));

      // A poll tick starts a new app-7 request that stays in flight.
      vi.advanceTimersByTime(10_000);
      const staleRequest = httpMock.expectOne('/api/apps/7/users?page=1');

      // The route moves to app 8, on the same component instance.
      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      expect(root().querySelector('table')).toBeNull();
      expect(root().textContent).not.toContain('app-7-user');

      // The app-7 request in flight is cancelled at once, so a late answer
      // of app 7 can never reach the view.
      expect(staleRequest.cancelled).toBe(true);

      const freshRequest = httpMock.expectOne('/api/apps/8/users?page=1');
      freshRequest.flush(buildPage({ rows: [buildRow({ userId: 'app-8-user' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('app-8-user');
      expect(root().textContent).not.toContain('app-7-user');
    });

    it('redirects to /apps when a 404 answer arrives for the new app', () => {
      startStore(10);
      flushUsers(buildPage({ rows: [buildRow({ userId: 'app-7-user' })] }));
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      fixture.componentRef.setInput('appId', '8');
      fixture.detectChanges();

      httpMock
        .expectOne('/api/apps/8/users?page=1')
        .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
      fixture.detectChanges();

      expect(navigateSpy).toHaveBeenCalledWith(['/apps'], { queryParams: { notFoundAppId: '8' } });
    });
  });

  describe('sending a request during a request in flight (MAJOR 3 of the Angular review of #159)', () => {
    it('sends the request of a page change while the previous poll request is still in flight', () => {
      startStore(10);
      flushUsers(buildPage({ page: 1, pageCount: 5, rows: [buildRow({ userId: 'page-1-user' })] }));

      // A poll tick starts a new request that stays in flight.
      vi.advanceTimersByTime(10_000);
      const staleRequest = httpMock.expectOne('/api/apps/7/users?page=1');

      // The user moves to page 2 while that request is still pending.
      fixture.componentRef.setInput('page', '2');
      fixture.detectChanges();

      // The page-1 request in flight is cancelled at once: the page-2
      // request of the user action does not wait for it (MAJOR 3).
      expect(staleRequest.cancelled).toBe(true);

      const freshRequest = httpMock.expectOne('/api/apps/7/users?page=2');
      freshRequest.flush(buildPage({ page: 2, pageCount: 5, rows: [buildRow({ userId: 'page-2-user' })] }));
      fixture.detectChanges();

      expect(root().textContent).toContain('page-2-user');
    });
  });
});
