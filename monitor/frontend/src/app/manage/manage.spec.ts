import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { Manage } from './manage';

/** jsdom has no working `<dialog>`. See delete-app-dialog.spec.ts for the reason. */
function installDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype as unknown as {
    showModal?: (this: HTMLDialogElement) => void;
    close?: (this: HTMLDialogElement) => void;
  };
  proto.showModal = function (this: HTMLDialogElement): void {
    this.setAttribute('open', '');
  };
  proto.close = function (this: HTMLDialogElement): void {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
}

function removeDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  delete proto['showModal'];
  delete proto['close'];
}

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

describe('Manage', () => {
  let fixture: ComponentFixture<Manage>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(async () => {
    installDialogPolyfill();
    await TestBed.configureTestingModule({
      imports: [Manage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    announcer = TestBed.inject(Announcer);
    fixture = TestBed.createComponent(Manage);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    httpMock.verify();
    removeDialogPolyfill();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function flushList(rows: AppRow[]): void {
    httpMock.expectOne({ url: '/api/apps', method: 'GET' }).flush(rows);
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
    flushList([]);
  });

  it('shows a focusable heading', () => {
    const heading = root().querySelector('h1');
    expect(heading?.textContent).toContain('Manage');
    expect(heading?.getAttribute('tabindex')).toBe('-1');
    flushList([]);
  });

  it('shows no refresh bar (#92)', () => {
    expect(root().querySelector('app-refresh-bar')).toBeNull();
    flushList([]);
  });

  it('sends GET /api/apps on load, and shows a loading text first', () => {
    expect(root().textContent).toContain('Octometer reads the app list.');
    flushList([buildRow()]);
    expect(root().querySelector('table')).toBeTruthy();
  });

  it('shows "No app is registered." when the list is empty, and no table', () => {
    flushList([]);

    expect(root().textContent).toContain('No app is registered.');
    expect(root().querySelector('table')).toBeNull();
  });

  describe('once the list answers', () => {
    it('renders a table with a caption, a scoped row header, and a scoped column header', () => {
      flushList([buildRow()]);

      const table = root().querySelector('table')!;
      expect(table.querySelector('caption')?.textContent?.trim().length).toBeGreaterThan(0);
      const rowHeader = table.querySelector('tbody th')!;
      expect(rowHeader.getAttribute('scope')).toBe('row');
      expect(rowHeader.textContent?.trim()).toBe('traficio');
      for (const header of Array.from(table.querySelectorAll('thead th'))) {
        expect(header.getAttribute('scope')).toBe('col');
      }
    });

    it('adds no CSS display value on a table element', () => {
      flushList([buildRow()]);

      const table = root().querySelector('table') as HTMLElement;
      expect(getComputedStyle(table).display).toBe('table');
      const tbody = root().querySelector('tbody') as HTMLElement;
      expect(getComputedStyle(tbody).display).toBe('table-row-group');
    });

    it('puts the table in a scroll container with tabindex 0, role region, and a name from the caption', () => {
      flushList([buildRow()]);

      const wrapper = root().querySelector('.table-scroll') as HTMLElement;
      expect(wrapper).toBeTruthy();
      expect(wrapper.getAttribute('tabindex')).toBe('0');
      expect(wrapper.getAttribute('role')).toBe('region');
      const labelledBy = wrapper.getAttribute('aria-labelledby');
      expect(document.getElementById(labelledBy ?? '')?.tagName).toBe('CAPTION');
    });

    it('shows the status as an icon plus text, never colour alone', () => {
      flushList([buildRow({ status: 'UNREACHABLE', lastError: 'The server did not answer.' })]);

      const statusCell = root().querySelector('td.status') as HTMLElement;
      const icon = statusCell.querySelector('[aria-hidden="true"]');
      expect(icon?.textContent?.trim().length).toBeGreaterThan(0);
      expect(statusCell.textContent).toContain('Unreachable');
      expect(statusCell.textContent).toContain('The server did not answer.');
    });

    it('shows "Not polled yet" for a NEVER_POLLED app, not a blank cell', () => {
      flushList([buildRow({ status: 'NEVER_POLLED', lastSuccessAt: null })]);

      const statusCell = root().querySelector('td.status') as HTMLElement;
      expect(statusCell.textContent).toContain('Not polled yet');
    });

    it('gives each row an Edit control and a Delete trigger with a minimum target size of 24 by 24 CSS px', () => {
      flushList([buildRow()]);

      const editToggle = root().querySelector('button.edit-toggle') as HTMLElement;
      const deleteTrigger = root().querySelector('button.delete-trigger') as HTMLElement;
      expect(editToggle).toBeTruthy();
      expect(deleteTrigger).toBeTruthy();
      for (const button of [editToggle, deleteTrigger]) {
        const style = getComputedStyle(button);
        expect(style.minWidth).toBe('24px');
        expect(style.minHeight).toBe('24px');
      }
    });

    it('tracks each row with the stable key appId, so a later answer keeps the tr node of a surviving row', () => {
      flushList([buildRow({ appId: 1, name: 'alpha' }), buildRow({ appId: 2, name: 'beta' })]);
      const rowsBefore = Array.from(root().querySelectorAll('tbody tr'));

      (fixture.componentInstance as unknown as { reload(): void }).reload();
      flushList([
        buildRow({ appId: 3, name: 'gamma' }),
        buildRow({ appId: 1, name: 'alpha' }),
        buildRow({ appId: 2, name: 'beta' }),
      ]);

      const rowsAfter = Array.from(root().querySelectorAll('tbody tr'));
      expect(rowsAfter[1]).toBe(rowsBefore[0]);
      expect(rowsAfter[2]).toBe(rowsBefore[1]);
    });
  });

  describe('the add-app form', () => {
    it('shows the add-app form', () => {
      flushList([]);
      expect(root().querySelector('app-add-app-form')).toBeTruthy();
    });

    it('reloads the list after the form reports a created app', () => {
      flushList([]);
      const nameField = root().querySelector<HTMLInputElement>('#add-app-name')!;
      const connectionStringField = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
      const databaseField = root().querySelector<HTMLInputElement>('#add-app-database')!;
      nameField.value = 'traficio';
      nameField.dispatchEvent(new Event('input'));
      connectionStringField.value = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
      connectionStringField.dispatchEvent(new Event('input'));
      databaseField.value = 'exampledb';
      databaseField.dispatchEvent(new Event('input'));
      root().querySelector('app-add-app-form form')!.dispatchEvent(new Event('submit', { cancelable: true }));

      httpMock
        .expectOne({ url: '/api/apps', method: 'POST' })
        .flush({ appId: 9, name: 'traficio', database: 'exampledb', collection: 'octometer_events' }, {
          status: 201,
          statusText: 'Created',
        });
      fixture.detectChanges();

      flushList([buildRow({ appId: 9, name: 'traficio' })]);
      expect(root().querySelector('table')).toBeTruthy();
    });
  });

  describe('editing an app', () => {
    it('reloads the list after the edit form reports a save', () => {
      flushList([buildRow({ appId: 4, name: 'traficio' })]);

      root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
      fixture.detectChanges();
      root().querySelector('app-edit-app-form form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock
        .expectOne({ url: '/api/apps/4', method: 'PATCH' })
        .flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      flushList([buildRow({ appId: 4, name: 'traficio-renamed' })]);
      expect(root().querySelector('tbody th')?.textContent?.trim()).toBe('traficio-renamed');
    });
  });

  describe('deleting an app', () => {
    it('reloads the list and moves the focus to the <h1> after a successful delete', () => {
      flushList([buildRow({ appId: 7, name: 'traficio' })]);

      root().querySelector<HTMLButtonElement>('button.delete-trigger')!.click();
      fixture.detectChanges();
      const confirmName = root().querySelector<HTMLInputElement>('.confirm-name')!;
      confirmName.value = 'traficio';
      confirmName.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();

      httpMock
        .expectOne({ url: '/api/apps/7', method: 'DELETE' })
        .flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      flushList([]);

      const heading = root().querySelector('h1');
      expect(document.activeElement).toBe(heading);
    });

    it('announces "App deleted" through the shared status region', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      flushList([buildRow({ appId: 7, name: 'traficio' })]);

      root().querySelector<HTMLButtonElement>('button.delete-trigger')!.click();
      fixture.detectChanges();
      const confirmName = root().querySelector<HTMLInputElement>('.confirm-name')!;
      confirmName.value = 'traficio';
      confirmName.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();
      httpMock
        .expectOne({ url: '/api/apps/7', method: 'DELETE' })
        .flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();
      flushList([]);

      expect(announceSpy).toHaveBeenCalledWith('App deleted');
    });
  });

  it('never writes the connection string of the add form to the console, or to the URL', () => {
    const secret = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    flushList([]);
    const nameField = root().querySelector<HTMLInputElement>('#add-app-name')!;
    const connectionStringField = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    const databaseField = root().querySelector<HTMLInputElement>('#add-app-database')!;
    nameField.value = 'traficio';
    nameField.dispatchEvent(new Event('input'));
    connectionStringField.value = secret;
    connectionStringField.dispatchEvent(new Event('input'));
    databaseField.value = 'exampledb';
    databaseField.dispatchEvent(new Event('input'));
    root().querySelector('app-add-app-form form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush({ appId: 9, name: 'traficio', database: 'exampledb', collection: 'octometer_events' }, {
        status: 201,
        statusText: 'Created',
      });
    fixture.detectChanges();
    flushList([buildRow({ appId: 9, name: 'traficio' })]);

    for (const spy of [logSpy, warnSpy, errorSpy]) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(secret);
      }
    }
    expect(window.location.href).not.toContain(secret);
    expect(root().innerHTML).not.toContain(secret);
  });
});
