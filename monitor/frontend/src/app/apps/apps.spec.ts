import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { Announcer } from '../announcer';
import type { AppRow } from './app-row';
import { Apps } from './apps';

/**
 * Finds the first loaded CSS rule with the given selector text. `jsdom`
 * does not compute a style for a `:hover` selector, so a test reads the
 * rule from the stylesheet, not from a live style. Angular appends its own
 * content attribute to a selector of an emulated component, so this
 * function strips that attribute before the comparison.
 */
function findCssRule(selectorText: string): CSSStyleRule | undefined {
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList | undefined;
    try {
      rules = sheet.cssRules;
    } catch {
      continue;
    }
    for (const rule of Array.from(rules ?? [])) {
      const styleRule = rule as CSSStyleRule;
      const plainSelector = styleRule.selectorText
        ?.replace(/\[_ngcontent-[\w-]+\]/g, '')
        .replace(/\s+/g, ' ');
      const selectorParts = plainSelector?.split(',').map((part) => part.trim()) ?? [];
      if (selectorParts.includes(selectorText)) {
        return styleRule;
      }
    }
  }
  return undefined;
}

/** Builds one app row. Each test overrides only the fields it checks. */
function buildRow(overrides: Partial<AppRow> = {}): AppRow {
  return {
    appId: 1,
    name: 'traficio',
    clicks: 1234,
    uniqueUsers: 42,
    uniqueSessions: 99,
    status: 'OK',
    lastSuccessAt: '2026-09-21T14:23:07.512Z',
    lastError: null,
    nextPollAt: '2026-09-21T14:24:07.512Z',
    ...overrides,
  };
}

describe('Apps', () => {
  let fixture: ComponentFixture<Apps>;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Apps],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(Apps);
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

  /** Answers the pending `GET /api/apps` request, then updates the view. */
  function flushApps(rows: AppRow[]): void {
    httpMock.expectOne('/api/apps').flush(rows);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows a focusable heading', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toContain('Apps');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
  });

  it('puts the refresh bar directly after the heading, before any other content', () => {
    const children = Array.from(root().children);
    const headingIndex = children.findIndex((el) => el.tagName === 'H1');
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');

    expect(headingIndex).toBe(0);
    expect(refreshBarIndex).toBe(1);
  });

  it('shows a loading text before the first answer arrives, and no table', () => {
    expect(root().querySelector('table')).toBeNull();
    expect(root().textContent).toContain('Octometer reads the app list.');
  });

  it('shows no not-found message when the query parameter is absent', () => {
    expect(root().querySelector('.not-found-message')).toBeNull();
  });

  it('shows a message with the app id when notFoundAppId is set (D30, #52)', () => {
    fixture.componentRef.setInput('notFoundAppId', '9');
    fixture.detectChanges();

    const message = root().querySelector('.not-found-message');
    expect(message?.textContent?.trim()).toBe('App 9 is not registered.');
  });

  it('keeps the refresh bar directly after the heading, even with a not-found message', () => {
    fixture.componentRef.setInput('notFoundAppId', '9');
    fixture.detectChanges();

    const children = Array.from(root().children);
    const headingIndex = children.findIndex((el) => el.tagName === 'H1');
    const refreshBarIndex = children.findIndex((el) => el.tagName === 'APP-REFRESH-BAR');

    expect(headingIndex).toBe(0);
    expect(refreshBarIndex).toBe(1);
  });

  it('announces the not-found message through the shared status region (accessibility MAJOR 1 of the correction round 1 of #159)', () => {
    const announcer = TestBed.inject(Announcer);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentRef.setInput('notFoundAppId', '9');
    fixture.detectChanges();
    vi.advanceTimersByTime(200);

    expect(announcer.message()).toBe('App 9 is not registered.');
    startStore();
    flushApps([]);
  });

  it('clears notFoundAppId from the URL after the message shows, but keeps the message on the screen and in the status region (accessibility BLOCKER A of the correction round 2 of #159)', () => {
    const announcer = TestBed.inject(Announcer);
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentRef.setInput('notFoundAppId', '9');
    fixture.detectChanges();
    vi.advanceTimersByTime(200);

    expect(navigateSpy).toHaveBeenCalledWith([], {
      queryParams: { notFoundAppId: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });

    // Simulates the real router: it echoes the cleared query parameter back
    // as `undefined`, not as `null`.
    fixture.componentRef.setInput('notFoundAppId', undefined);
    fixture.detectChanges();
    vi.advanceTimersByTime(200);

    const message = root().querySelector('.not-found-message');
    expect(message?.textContent?.trim()).toBe('App 9 is not registered.');
    expect(announcer.message()).toBe('App 9 is not registered.');
    startStore();
    flushApps([]);
  });

  describe('once the app list answers', () => {
    it('shows "No app is registered." and a link to /manage when the list is empty', () => {
      startStore();
      flushApps([]);

      expect(root().textContent).toContain('No app is registered.');
      const link = root().querySelector('a[href="/manage"]') as HTMLAnchorElement;
      expect(link).toBeTruthy();
    });

    it('gives the link of the empty state a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushApps([]);

      const link = root().querySelector('a[href="/manage"]') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('renders a table with a caption and a scoped row header for the app name', () => {
      startStore();
      flushApps([buildRow()]);

      const table = root().querySelector('table');
      expect(table).toBeTruthy();
      expect(table?.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);

      const rowHeader = table?.querySelector('tbody th') as HTMLTableCellElement;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.querySelector('a')?.textContent?.trim()).toBe('traficio');
    });

    it('gives each column header a scope of "col"', () => {
      startStore();
      flushApps([buildRow()]);

      const headers = Array.from(root().querySelectorAll('thead th'));
      expect(headers.length).toBeGreaterThan(0);
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
    });

    it('links the name cell to the level 2 view of that app', () => {
      startStore();
      flushApps([buildRow({ appId: 7 })]);

      const link = root().querySelector('tbody th a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/apps/7/users');
    });

    it('formats each count with Intl.NumberFormat, right-aligned with tabular numbers', () => {
      startStore();
      flushApps([buildRow({ clicks: 12345 })]);

      const cell = root().querySelector('tbody td.num') as HTMLElement;
      expect(cell.textContent?.trim()).toBe(new Intl.NumberFormat().format(12345));
      const style = getComputedStyle(cell);
      expect(style.textAlign).toBe('right');
      expect(style.fontVariantNumeric).toContain('tabular-nums');
    });

    it('shows "–" for each count of a NEVER_POLLED app, not 0, with a hidden text for a screen reader', () => {
      startStore();
      flushApps([
        buildRow({
          status: 'NEVER_POLLED',
          clicks: 0,
          uniqueUsers: 0,
          uniqueSessions: 0,
          lastSuccessAt: null,
        }),
      ]);

      const numCells = Array.from(root().querySelectorAll('tbody td.num'));
      expect(numCells.length).toBe(3);
      for (const cell of numCells) {
        const hiddenText = cell.querySelector('.visually-hidden');
        const dash = cell.querySelector('[aria-hidden="true"]');
        expect(hiddenText?.textContent?.trim()).toBe('No data');
        expect(dash?.textContent?.trim()).toBe('–');
      }
    });

    it('shows the status as an icon plus text, never colour alone', () => {
      startStore();
      flushApps([buildRow({ status: 'UNREACHABLE' })]);

      const statusCell = root().querySelector('td.status') as HTMLElement;
      const icon = statusCell.querySelector('[aria-hidden="true"]');
      expect(icon?.textContent?.trim().length).toBeGreaterThan(0);
      expect(statusCell.textContent).toContain('Unreachable');
    });

    it('keeps the numbers of a failed app, and shows "Data from HH:mm:ss" and the visible lastError', () => {
      startStore();
      flushApps([
        buildRow({
          status: 'UNREACHABLE',
          clicks: 500,
          lastSuccessAt: '2026-09-21T09:05:03.000Z',
          lastError: 'The server did not answer.',
        }),
      ]);

      const rowCells = root().querySelectorAll('tbody td');
      const clicksCell = rowCells[0] as HTMLElement;
      expect(clicksCell.textContent?.trim()).toBe(new Intl.NumberFormat().format(500));

      const date = new Date('2026-09-21T09:05:03.000Z');
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const expectedTime = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;

      expect(root().textContent).toContain(`Data from ${expectedTime}`);
      expect(root().textContent).toContain('The server did not answer.');
    });

    it('shows the data time of an OK app with the local date and time, and a <time> element', () => {
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.getAttribute('datetime')).toBe('2026-09-21T09:05:03.000Z');

      const date = new Date('2026-09-21T09:05:03.000Z');
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const expected = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
      expect(time.textContent?.trim()).toBe(expected);
    });

    it('formats the data time in a fixed zone against a literal string, not against a mirror of the code', () => {
      vi.stubEnv('TZ', 'UTC');
      try {
        startStore();
        flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

        const time = root().querySelector('td.data-time time') as HTMLTimeElement;
        expect(time.textContent?.trim()).toBe('2026-09-21 09:05:03');
      } finally {
        vi.unstubAllEnvs();
      }
    });

    it('shows 0 for an OK app with zero events, not a dash', () => {
      startStore();
      flushApps([buildRow({ status: 'OK', clicks: 0, uniqueUsers: 0, uniqueSessions: 0 })]);

      const numCells = Array.from(root().querySelectorAll('tbody td.num'));
      expect(numCells.length).toBe(3);
      for (const cell of numCells) {
        expect(cell.textContent?.trim()).toBe('0');
      }
    });

    it('shows the data time of a failed app that holds a lastSuccessAt, not an empty cell', () => {
      startStore();
      flushApps([
        buildRow({
          status: 'UNREACHABLE',
          lastSuccessAt: '2026-09-21T09:05:03.000Z',
          lastError: 'The server did not answer.',
        }),
      ]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time).toBeTruthy();
      expect(time.getAttribute('datetime')).toBe('2026-09-21T09:05:03.000Z');
    });

    it('shows a dash with a hidden text in the "Data time" cell of a row with no lastSuccessAt', () => {
      startStore();
      flushApps([buildRow({ status: 'NEVER_POLLED', lastSuccessAt: null })]);

      const cell = root().querySelector('td.data-time') as HTMLElement;
      expect(cell.querySelector('time')).toBeNull();
      expect(cell.querySelector('.visually-hidden')?.textContent?.trim()).toBe('No data');
      expect(cell.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('–');
    });

    it('names the time zone in the "Data time" column header', () => {
      startStore();
      flushApps([buildRow()]);

      const expectedZone =
        new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
          .formatToParts(new Date())
          .find((part) => part.type === 'timeZoneName')?.value ?? '';

      const headers = Array.from(root().querySelectorAll('thead th'));
      const dataTimeHeader = headers.find((h) => h.textContent?.includes('Data time'));
      expect(dataTimeHeader?.textContent).toContain(expectedZone);
    });

    it('opens the name-cell link on a click on a different cell of the row', () => {
      startStore();
      flushApps([buildRow({ appId: 9 })]);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      const statusCell = root().querySelector('td.status') as HTMLElement;
      statusCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', 9, 'users']);
    });

    it('does not navigate on a cell click while the user has text selected inside that cell', () => {
      startStore();
      flushApps([buildRow()]);
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

    it('navigates on a cell click even while the user has text selected in a different part of the page', () => {
      startStore();
      flushApps([buildRow({ appId: 4 })]);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;
      const heading = root().querySelector('h1') as HTMLElement;
      vi.spyOn(window, 'getSelection').mockReturnValue({
        isCollapsed: false,
        toString: () => 'Apps',
        containsNode: (node: Node) => node === heading,
      } as unknown as Selection);

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true }));

      expect(navigateSpy).toHaveBeenCalledWith(['/apps', 4, 'users']);
    });

    it('does not navigate on a cell click with a modifier key, so the browser can open a new tab', () => {
      startStore();
      flushApps([buildRow({ appId: 9 })]);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      for (const init of [
        { ctrlKey: true },
        { metaKey: true },
        { shiftKey: true },
        { altKey: true },
      ]) {
        numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, ...init }));
      }

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('does not navigate on a cell click with a button other than the primary button', () => {
      startStore();
      flushApps([buildRow({ appId: 9 })]);
      const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
      const numCell = root().querySelector('td.num') as HTMLElement;

      numCell.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 1 }));

      expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('adds no CSS display value on a table element', () => {
      startStore();
      flushApps([buildRow()]);

      const table = root().querySelector('table') as HTMLElement;
      expect(getComputedStyle(table).display).toBe('table');
      const thead = root().querySelector('thead') as HTMLElement;
      expect(getComputedStyle(thead).display).toBe('table-header-group');
      const tbody = root().querySelector('tbody') as HTMLElement;
      expect(getComputedStyle(tbody).display).toBe('table-row-group');
      const tr = root().querySelector('tbody tr') as HTMLElement;
      expect(getComputedStyle(tr).display).toBe('table-row');
      const td = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(td).display).toBe('table-cell');
      const th = root().querySelector('tbody th') as HTMLElement;
      expect(getComputedStyle(th).display).toBe('table-cell');
    });

    it('gives the name link a minimum target size of 24 by 24 CSS px', () => {
      startStore();
      flushApps([buildRow()]);

      const link = root().querySelector('tbody th a') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      startStore();
      flushApps([buildRow()]);

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
      expect(wrapper.querySelector('table')).toBeTruthy();
    });

    it('keeps a number cell and the data-time cell on one line, not broken word by word', () => {
      startStore();
      flushApps([buildRow()]);

      const numCell = root().querySelector('tbody td.num') as HTMLElement;
      const dataTimeCell = root().querySelector('tbody td.data-time') as HTMLElement;
      expect(getComputedStyle(numCell).whiteSpace).toBe('nowrap');
      expect(getComputedStyle(dataTimeCell).whiteSpace).toBe('nowrap');
    });

    it('gives a pointer cursor to each data cell of a row', () => {
      startStore();
      flushApps([buildRow()]);

      const cell = root().querySelector('tbody td') as HTMLElement;
      expect(getComputedStyle(cell).cursor).toBe('pointer');
    });

    it('gives a hovered row a background colour, not colour alone, as the hover state', () => {
      startStore();
      flushApps([buildRow()]);

      const hoverRule = findCssRule('tbody tr:hover');
      expect(hoverRule?.style.backgroundColor).toBeTruthy();
    });

    it('gives .table-scroll position relative, so a hidden span cannot escape its clip', () => {
      startStore();
      flushApps([buildRow()]);

      const scrollRule = findCssRule('.table-scroll');
      expect(scrollRule?.style.position).toBe('relative');
    });

    it('wraps a long lastError with overflow-wrap anywhere, and no ellipsis', () => {
      startStore();
      flushApps([
        buildRow({
          status: 'ERROR',
          lastError: 'x'.repeat(300),
          lastSuccessAt: '2026-09-21T09:05:03.000Z',
        }),
      ]);

      const errorText = root().querySelector('.error-text') as HTMLElement;
      const style = getComputedStyle(errorText);
      expect(style.overflowWrap).toBe('anywhere');
      expect(style.textOverflow).not.toBe('ellipsis');
    });

    it('tracks each row with the stable key appId: a second answer that drops a row and changes the order keeps the tr node of each surviving row', () => {
      startStore(10);
      flushApps([
        buildRow({ appId: 1, name: 'alpha' }),
        buildRow({ appId: 2, name: 'beta' }),
        buildRow({ appId: 3, name: 'gamma' }),
      ]);

      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));
      const trOfAppId = new Map<number, Element>([
        [1, rowsBefore[0]],
        [2, rowsBefore[1]],
        [3, rowsBefore[2]],
      ]);

      vi.advanceTimersByTime(10_000);
      // The second answer drops appId 2, and it puts appId 3 before appId 1.
      flushApps([buildRow({ appId: 3, name: 'gamma' }), buildRow({ appId: 1, name: 'alpha' })]);

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter.length).toBe(2);
      expect(rowsAfter[0]).toBe(trOfAppId.get(3));
      expect(rowsAfter[1]).toBe(trOfAppId.get(1));
    });

    it('keeps a focus outside tbody across a refresh', () => {
      startStore(10);
      flushApps([buildRow({ appId: 3, clicks: 1 })]);

      // The pause button sits before the table, outside <tbody>. A focus
      // there does not stop the poll store: D29 stops it only for a focus
      // inside <tbody>.
      const pauseButton = root().querySelector('button') as HTMLElement;
      pauseButton.focus();

      vi.advanceTimersByTime(10_000);
      flushApps([buildRow({ appId: 3, clicks: 2 })]);

      expect(document.activeElement).toBe(pauseButton);
      expect(root().querySelector('tbody td.num')?.textContent?.trim()).toBe(
        new Intl.NumberFormat().format(2),
      );
    });

    it('keeps the old rows visible while a refresh request is pending', () => {
      startStore(10);
      flushApps([buildRow({ appId: 5, name: 'first-app' })]);

      vi.advanceTimersByTime(10_000);
      expect(root().querySelector('tbody th a')?.textContent?.trim()).toBe('first-app');

      httpMock.expectOne('/api/apps').flush([buildRow({ appId: 5, name: 'first-app' })]);
      fixture.detectChanges();
    });
  });
});
