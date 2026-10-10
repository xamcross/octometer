import { readFileSync } from 'node:fs';

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { RefreshIntervalState } from '../poll/refresh-interval-state';
import type { AppRow, AppStatus } from './app-row';
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
    gaps: [],
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
    // Issue #301, brief review B4: the health poll waits for the start
    // gate. This suite tests the view alone, with no App shell to open
    // it, so it opens the gate itself.
    vi.useFakeTimers();
    TestBed.inject(RefreshIntervalState).allowStart();
    fixture = TestBed.createComponent(Apps);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    httpMock.verify();
  });

  /** Answers the one health request of the page, and runs the first data tick. */
  function startStore(refreshSeconds = 10, retentionDays?: number): void {
    vi.advanceTimersByTime(0);
    httpMock.expectOne('/api/health').flush({ refreshSeconds, retentionDays });
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
    expect(root().textContent).toContain('Loading apps…');
  });

  it('shows no retention text before the health answer arrives', () => {
    expect(root().textContent).not.toContain('Totals of the last');
  });

  it('shows "Totals of the last <n> days." with the number from the health answer (issue #66)', () => {
    startStore(10, 395);
    flushApps([]);

    const text = root().querySelector('p.retention-text');
    expect(text?.textContent?.trim()).toBe(
      `Totals of the last ${new Intl.NumberFormat().format(395)} days.`,
    );
  });

  it('shows no retention text when the health answer lacks retentionDays (issue #66)', () => {
    startStore(10);
    flushApps([]);

    expect(root().querySelector('p.retention-text')).toBeNull();
  });

  describe('once the app list answers', () => {
    it('shows "No app yet." and a link "Manage apps" to /manage when the list is empty', () => {
      startStore();
      flushApps([]);

      expect(root().textContent).toContain('No app yet.');
      const link = root().querySelector('a[href="/manage"]') as HTMLAnchorElement;
      expect(link).toBeTruthy();
      expect(link.textContent?.trim()).toBe('Manage apps');
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

    it('adds a link cell "First pages" after the "Unique sessions" column (#114)', () => {
      startStore();
      flushApps([buildRow({ appId: 7 })]);

      const headers = Array.from(root().querySelectorAll('thead th'));
      const sessionsIndex = headers.findIndex((h) => h.textContent?.trim() === 'Unique sessions');
      const firstPagesIndex = headers.findIndex((h) => h.textContent?.trim() === 'First pages');
      expect(sessionsIndex).toBeGreaterThanOrEqual(0);
      expect(firstPagesIndex).toBe(sessionsIndex + 1);

      const link = root().querySelector('a.first-pages-link') as HTMLAnchorElement;
      expect(link.textContent?.trim()).toBe('First pages');
      expect(link.getAttribute('href')).toBe('/apps/7/first-pages');
    });

    it('gives the "First pages" link a minimum target size of 24 by 24 CSS px, and the keyboard reaches it in the row order (#114)', () => {
      startStore();
      flushApps([buildRow()]);

      const link = root().querySelector('a.first-pages-link') as HTMLElement;
      const style = getComputedStyle(link);
      expect(style.minWidth).toBe('24px');
      expect(style.minHeight).toBe('24px');
      expect(link.hasAttribute('tabindex')).toBe(false);

      const focusable = Array.from(root().querySelectorAll('tbody a'));
      expect(focusable[0]).not.toBe(link);
      expect(focusable[focusable.indexOf(link) - 1]?.textContent?.trim()).toBe('traficio');
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

    it('keeps the numbers of a failed app, shows no "Data from" line, and shows the visible lastError (issue #390)', () => {
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

      expect(root().textContent).not.toContain('Data from');
      expect(root().querySelector('.data-from')).toBeNull();
      expect(root().textContent).toContain('The server did not answer.');
    });

    // D16, issue #62: one line for each gap, under the app in the status cell.
    // The expected text below builds from the same local-date logic as
    // formatLocalDate, so the test stays correct in every time zone.
    it('shows one line for each gap of a row, with the date part only', () => {
      const gaps = [
        { from: '2026-08-01T14:00:00.000Z', to: '2026-08-30T14:00:00.000Z' },
        { from: '2026-09-01T14:00:00.000Z', to: '2026-09-02T14:00:00.000Z' },
      ];
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const localDate = (iso: string): string => {
        const date = new Date(iso);
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
      };
      startStore();
      flushApps([buildRow({ gaps })]);

      const gapLines = Array.from(root().querySelectorAll('td.status .gap-text')).map((line) =>
        line.textContent?.trim(),
      );
      expect(gapLines).toEqual(
        gaps.map(
          (gap) => `Events from ${localDate(gap.from)} to ${localDate(gap.to)} can be absent.`,
        ),
      );
    });

    it('shows no gap line for a row with no gap', () => {
      startStore();
      flushApps([buildRow({ gaps: [] })]);

      expect(root().querySelector('.gap-text')).toBeNull();
    });

    it('shows the "Last updated" cell of an OK app as a relative time, with the full local date and time in the title (issue #390)', () => {
      vi.setSystemTime(new Date('2026-09-21T09:08:03.000Z'));
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.getAttribute('datetime')).toBe('2026-09-21T09:05:03.000Z');
      expect(time.textContent?.trim()).toBe('3 min ago');

      const date = new Date('2026-09-21T09:05:03.000Z');
      const pad = (n: number): string => n.toString().padStart(2, '0');
      const expectedTitle = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
      expect(time.getAttribute('title')).toBe(expectedTitle);
    });

    it('formats the title in a fixed zone against a literal string, with no seconds (issue #390)', () => {
      vi.stubEnv('TZ', 'UTC');
      try {
        vi.setSystemTime(new Date('2026-09-21T09:08:03.000Z'));
        startStore();
        flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

        const time = root().querySelector('td.data-time time') as HTMLTimeElement;
        expect(time.getAttribute('title')).toBe('2026-09-21 09:05');
      } finally {
        vi.unstubAllEnvs();
      }
    });

    it('refreshes the relative "Last updated" text from its own 30 s clock, with no new poll answer (issue #390, MAJOR 1 of the review of pull request #437)', () => {
      vi.setSystemTime(new Date('2026-09-21T09:05:00.000Z'));
      // A long refresh interval, so the 60 s advance below moves the
      // clock tick only, with no poll request of its own to flush.
      startStore(10_000);
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:00.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('just now');

      // Two 30 s clock ticks, with no poll answer and no other signal
      // write; the fake clock moves `Date.now()` along with the timers.
      vi.advanceTimersByTime(60_000);
      fixture.detectChanges();

      expect(time.textContent?.trim()).toBe('1 min ago');
    });

    it('shows "just now" in the "Last updated" cell inside the first minute (issue #390)', () => {
      vi.setSystemTime(new Date('2026-09-21T09:05:30.000Z'));
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('just now');
    });

    it('shows "1 hour ago" in the "Last updated" cell, with no plural, for one full hour (issue #390)', () => {
      vi.setSystemTime(new Date('2026-09-21T10:05:03.000Z'));
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('1 hour ago');
    });

    it('shows "2 hours ago" in the "Last updated" cell, with the plural, for two full hours (issue #390)', () => {
      vi.setSystemTime(new Date('2026-09-21T11:05:03.000Z'));
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('2 hours ago');
    });

    it('shows "2 days ago" in the "Last updated" cell for two full days (issue #390)', () => {
      vi.setSystemTime(new Date('2026-09-23T09:05:03.000Z'));
      startStore();
      flushApps([buildRow({ status: 'OK', lastSuccessAt: '2026-09-21T09:05:03.000Z' })]);

      const time = root().querySelector('td.data-time time') as HTMLTimeElement;
      expect(time.textContent?.trim()).toBe('2 days ago');
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

    it('shows a dash with a hidden text in the "Last updated" cell of a row with no lastSuccessAt', () => {
      startStore();
      flushApps([buildRow({ status: 'NEVER_POLLED', lastSuccessAt: null })]);

      const cell = root().querySelector('td.data-time') as HTMLElement;
      expect(cell.querySelector('time')).toBeNull();
      expect(cell.querySelector('.visually-hidden')?.textContent?.trim()).toBe('No data');
      expect(cell.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('–');
    });

    it('names the "Last updated" column header, with no "Data time" text left (issue #390)', () => {
      startStore();
      flushApps([buildRow()]);

      const headers = Array.from(root().querySelectorAll('thead th'));
      const lastUpdatedHeader = headers.find((h) => h.textContent?.trim() === 'Last updated');
      expect(lastUpdatedHeader).toBeTruthy();
      expect(headers.some((h) => h.textContent?.includes('Data time'))).toBe(false);
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

    it('sets no overflow hidden on .panel, so the ring of .table-scroll stays visible (#249)', () => {
      // Vitest links the global block of styles.css to document.styleSheets
      // only in browser mode. This test reads the block and the component
      // style as plain text instead. The test then walks the parsed rules
      // by hand. The text holds only the #249 block, because the `@import`
      // lines at the top of the file give jsdom a parse error. Each file
      // path starts at the project root, the working directory of the
      // test run.
      const stylesCssText = readFileSync('src/styles.css', 'utf8');
      const appsCssText = readFileSync('src/app/apps/apps.css', 'utf8');
      const panelsBlockMarker = '/* The panels, the tables, and the badges (#249). */';
      const panelsBlockText = stylesCssText.slice(stylesCssText.indexOf(panelsBlockMarker));

      const panelRules: CSSStyleRule[] = [];
      for (const cssText of [panelsBlockText, appsCssText]) {
        const style = document.createElement('style');
        style.textContent = cssText;
        document.head.appendChild(style);
        const rules = Array.from(style.sheet?.cssRules ?? []);
        document.head.removeChild(style);
        for (const rule of rules) {
          const styleRule = rule as CSSStyleRule;
          const selectorParts = styleRule.selectorText?.split(',').map((part) => part.trim()) ?? [];
          if (selectorParts.includes('.panel')) {
            panelRules.push(styleRule);
          }
        }
      }

      expect(panelRules.length).toBeGreaterThan(0);
      for (const rule of panelRules) {
        expect(rule.style.overflow).not.toBe('hidden');
      }
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

    it('puts the scroll container inside a panel (#249)', () => {
      startStore();
      flushApps([buildRow()]);

      const panel = root().querySelector('section.panel') as HTMLElement;
      expect(panel).toBeTruthy();
      expect(panel.querySelector('.table-scroll table')).toBeTruthy();
    });

    it('gives each status a badge with the fill class of D45, over the seven statuses (docs/design.md)', () => {
      const cases: { status: AppStatus; badgeClass: string }[] = [
        { status: 'OK', badgeClass: 'badge-ok' },
        { status: 'NEVER_POLLED', badgeClass: 'badge-none' },
        { status: 'UNREACHABLE', badgeClass: 'badge-bad' },
        { status: 'UNAUTHORIZED', badgeClass: 'badge-bad' },
        { status: 'OVERPRIVILEGED', badgeClass: 'badge-warn' },
        { status: 'INVALID_DATA', badgeClass: 'badge-bad' },
        { status: 'ERROR', badgeClass: 'badge-bad' },
      ];
      startStore();
      flushApps(
        cases.map((testCase, index) => buildRow({ appId: index, status: testCase.status })),
      );

      const badges = Array.from(root().querySelectorAll('td.status .badge'));
      expect(badges.length).toBe(cases.length);
      cases.forEach((testCase, index) => {
        const badge = badges[index] as HTMLElement;
        expect(badge.classList.contains('badge')).toBe(true);
        expect(badge.classList.contains(testCase.badgeClass)).toBe(true);
      });
    });

    it('keeps the mark and the word inside the badge span, and each note after it, outside the span', () => {
      startStore();
      flushApps([
        buildRow({
          status: 'ERROR',
          lastSuccessAt: '2026-09-21T09:05:03.000Z',
          lastError: 'The server did not answer.',
          gaps: [{ from: '2026-08-01T14:00:00.000Z', to: '2026-08-02T14:00:00.000Z' }],
        }),
      ]);

      const statusCell = root().querySelector('td.status') as HTMLElement;
      const badge = statusCell.querySelector('.badge') as HTMLElement;
      expect(badge.querySelector('[aria-hidden="true"]')).toBeTruthy();
      expect(badge.textContent).toContain('Error');
      expect(badge.querySelector('.error-text')).toBeNull();
      expect(badge.querySelector('.gap-text')).toBeNull();
      expect(statusCell.querySelector('.error-text')).toBeTruthy();
      expect(statusCell.querySelector('.gap-text')).toBeTruthy();
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

      // jsdom drops a var() value from the camelCase style.backgroundColor
      // getter, so this assertion reads the property through
      // getPropertyValue instead. The selector targets each cell (`> *`),
      // because the shared zebra rule of styles.css paints a background on
      // the cell too, above the row (#249).
      const hoverRule = findCssRule('tbody tr:hover > *');
      expect(hoverRule?.style.getPropertyValue('background')).toBeTruthy();
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
