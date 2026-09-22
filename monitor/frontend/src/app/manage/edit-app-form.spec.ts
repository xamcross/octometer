import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
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

  function openEditor(): void {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
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
    expect(connectionString.getAttribute('autocomplete')).toBe('off');
  });

  it('labels each field of the open form', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(root().querySelector(`label[for="${name.id}"]`)?.textContent?.trim().length).toBeGreaterThan(0);
    expect(
      root().querySelector(`label[for="${connectionString.id}"]`)?.textContent?.trim().length,
    ).toBeGreaterThan(0);
  });

  it('sends PATCH /api/apps/{id} with the name, and no connectionString when that field is empty', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'traficio-renamed';
    name.dispatchEvent(new Event('input'));
    root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio-renamed' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends the connectionString field when the user fills it', () => {
    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    connectionString.value = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    connectionString.dispatchEvent(new Event('input'));
    root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      connectionString: 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net',
    });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  describe('on a 204 answer', () => {
    it('closes the form, announces "App updated", and emits updated', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      const updatedSpy = vi.fn();
      fixture.componentInstance.updated.subscribe(updatedSpy);
      openEditor();
      root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' }).flush(null, {
        status: 204,
        statusText: 'No Content',
      });
      fixture.detectChanges();

      expect(root().querySelector('form')).toBeNull();
      expect(announceSpy).toHaveBeenCalledWith('App updated');
      expect(updatedSpy).toHaveBeenCalled();
    });
  });

  it('shows the fixed 404 message from the API, linked with aria-describedby', () => {
    openEditor();
    root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(root().textContent).toContain('The app is not registered.');
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    expect(name.getAttribute('aria-describedby')).toContain('edit-app-error-');
  });

  it('shows no inline text for a network failure', () => {
    openEditor();
    root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    expect(root().querySelector('[id^="edit-app-error-"]')).toBeNull();
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

  it('never writes the typed connection string to the console after a save', () => {
    const secret = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    connectionString.value = secret;
    connectionString.dispatchEvent(new Event('input'));
    root().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    fixture.detectChanges();

    for (const spy of [logSpy, warnSpy, errorSpy]) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(secret);
      }
    }
    expect(window.location.href).not.toContain(secret);
  });
});
