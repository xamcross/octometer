import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import type { AppDetail } from './app-registry-api';
import { EditAppForm } from './edit-app-form';

function buildRow(overrides: Partial<AppRow> = {}): AppRow {
  return {
    appId: 3,
    name: 'traficio',
    clicks: 10,
    uniqueUsers: 2,
    uniqueSessions: 4,
    status: 'OK',
    lastSuccessAt: '2026-09-21T14:23:07.512Z',
    lastError: null,
    nextPollAt: '2026-09-21T14:24:07.512Z',
    gaps: [],
    ...overrides,
  };
}

describe('EditAppForm', () => {
  let fixture: ComponentFixture<EditAppForm>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EditAppForm],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    announcer = TestBed.inject(Announcer);
    fixture = TestBed.createComponent(EditAppForm);
    fixture.componentRef.setInput('app', buildRow());
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    httpMock.verify();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  // Issue #226: opening the form now also fires GET /api/apps/{id}, to
  // read the stored route pattern list. This flushes that request with
  // a fixed answer of no list, unless the caller gives its own detail.
  function openEditor(detail: AppDetail = { appId: 3, name: 'traficio', pathPatterns: null }): void {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
    fixture.detectChanges();
    httpMock.expectOne({ url: '/api/apps/3', method: 'GET' }).flush(detail);
    fixture.detectChanges();
  }

  // For the one test of a failed GET: opens the form, but answers the
  // GET with a network failure, so pathPatterns stays unloaded.
  function openEditorWithFailedGet(): void {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
    fixture.detectChanges();
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'GET' })
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows only an "Edit" button before the user opens the form', () => {
    expect(root().querySelector('form')).toBeNull();
    const toggle = root().querySelector('button.edit-toggle');
    expect(toggle?.textContent?.trim()).toBe('Edit');
  });

  it('opens the form with the name field prefilled, and an empty connection string field', () => {
    openEditor();

    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(name.value).toBe('traficio');
    expect(connectionString.value).toBe('');
    expect(connectionString.type).toBe('password');
    expect(connectionString.getAttribute('autocomplete')).toBe('new-password');
  });

  it('moves the focus to the name field when the form opens', async () => {
    openEditor();
    await fixture.whenStable();

    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    expect(document.activeElement).toBe(name);
  });

  it('labels each field of the open form', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(
      root().querySelector(`label[for="${name.id}"]`)?.textContent?.trim().length,
    ).toBeGreaterThan(0);
    expect(
      root().querySelector(`label[for="${connectionString.id}"]`)?.textContent?.trim().length,
    ).toBeGreaterThan(0);
  });

  it('labels the route pattern field, and loads the stored list on open', () => {
    openEditor({ appId: 3, name: 'traficio', pathPatterns: '/checkout\n/cart' });

    const field = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-path-patterns-"]')!;
    expect(root().querySelector(`label[for="${field.id}"]`)?.textContent?.trim().length).toBeGreaterThan(0);
    expect(field.value).toBe('/checkout\n/cart');
  });

  it('sends the loaded route pattern list as it is, when the user makes no change', () => {
    openEditor({ appId: 3, name: 'traficio', pathPatterns: '/checkout' });
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio', pathPatterns: '/checkout' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends an empty pathPatterns text when the user clears the field, to clear the stored list', () => {
    openEditor({ appId: 3, name: 'traficio', pathPatterns: '/checkout' });
    const field = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-path-patterns-"]')!;
    field.value = '';
    field.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio', pathPatterns: '' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('shows the fixed 400 message for an invalid route pattern list, linked with aria-describedby', () => {
    openEditor();
    const field = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-path-patterns-"]')!;
    field.value = '/a?b';
    field.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .flush(
        { error: 'The route pattern at index 0 breaks the route pattern grammar.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain('The route pattern at index 0 breaks the route pattern grammar.');
    expect(field.getAttribute('aria-describedby')).toContain('edit-app-error-');
  });

  it('omits pathPatterns from the save when the GET on open fails, so the save keeps the stored list', () => {
    openEditorWithFailedGet();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends PATCH /api/apps/{id} with the name, and no connectionString when that field is empty', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'traficio-renamed';
    name.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio-renamed', pathPatterns: '' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends the connectionString field when the user fills it', () => {
    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    connectionString.value = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    connectionString.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      connectionString: 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net',
      pathPatterns: '',
    });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  describe('on a 204 answer', () => {
    it('closes the form, announces "App updated", and emits updated', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      const updatedSpy = vi.fn();
      fixture.componentInstance.updated.subscribe(updatedSpy);
      openEditor();
      root()
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' }).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      fixture.detectChanges();

      expect(root().querySelector('form')).toBeNull();
      expect(announceSpy).toHaveBeenCalledWith('App updated');
      expect(updatedSpy).toHaveBeenCalled();
    });

    it('moves the focus back to the "Edit" button after a save', async () => {
      openEditor();
      root()
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' }).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      fixture.detectChanges();
      await fixture.whenStable();

      const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;
      expect(document.activeElement).toBe(toggle);
    });
  });

  it('shows the fixed 404 message from the API, linked with aria-describedby, and reports a stale list', () => {
    const staleListSpy = vi.fn();
    fixture.componentInstance.staleList.subscribe(staleListSpy);
    openEditor();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(root().textContent).toContain('The app is not registered.');
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(connectionString.getAttribute('aria-describedby')).toContain('edit-app-error-');
    expect(staleListSpy).toHaveBeenCalled();
  });

  it('shows a general sentence for a network failure, linked with aria-describedby', () => {
    openEditor();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    expect(root().textContent).toContain('The monitor did not answer. Try again.');
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(connectionString.getAttribute('aria-describedby')).toContain('edit-app-error-');
  });

  it('closes the form and discards changes on Cancel', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'discarded';
    name.dispatchEvent(new Event('input'));

    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();

    expect(root().querySelector('form')).toBeNull();

    openEditor();
    const reopenedName = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    expect(reopenedName.value).toBe('traficio');
  });

  it('clears the typed connection string signal on Cancel, and keeps it out of memory', () => {
    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    connectionString.value = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    connectionString.dispatchEvent(new Event('input'));

    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();

    // `open()` also clears the field, so a later reopen cannot tell the two
    // resets apart. This reads the signal itself, right after Cancel.
    const instance = fixture.componentInstance as unknown as { connectionString: () => string };
    expect(instance.connectionString()).toBe('');
  });

  it('moves the focus back to the "Edit" button after Cancel', async () => {
    openEditor();
    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;
    expect(document.activeElement).toBe(toggle);
  });

  it('never writes the typed connection string to the console, to storage, or to the URL, after a save', () => {
    const secret = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    const methodNames = ['log', 'info', 'warn', 'error', 'debug'] as const;
    const spies = methodNames.map((name) =>
      vi.spyOn(console, name).mockImplementation(() => undefined),
    );

    // Proves the spies capture a real call, so the loop below is not vacuous.
    console.log('a control line');
    expect(spies[0].mock.calls.some((call) => call.join(' ').includes('a control line'))).toBe(
      true,
    );
    spies[0].mockClear();

    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    connectionString.value = secret;
    connectionString.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    fixture.detectChanges();

    for (const spy of spies) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(secret);
      }
    }
    expect(window.location.href).not.toContain(secret);
    expect(JSON.stringify(Object.entries(localStorage))).not.toContain(secret);
    expect(JSON.stringify(Object.entries(sessionStorage))).not.toContain(secret);
  });
});
