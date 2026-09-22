import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import { AddAppForm } from './add-app-form';
import type { AppSummary } from './app-registry-api';

describe('AddAppForm', () => {
  let fixture: ComponentFixture<AddAppForm>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AddAppForm],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    announcer = TestBed.inject(Announcer);
    vi.useFakeTimers();
    fixture = TestBed.createComponent(AddAppForm);
    fixture.detectChanges();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    httpMock.verify();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function fillAndSubmit(
    overrides: {
      name?: string;
      connectionString?: string;
      database?: string;
      collection?: string;
    } = {},
  ): void {
    const name = root().querySelector<HTMLInputElement>('#add-app-name')!;
    const connectionString = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    const database = root().querySelector<HTMLInputElement>('#add-app-database')!;
    const collection = root().querySelector<HTMLSelectElement>('#add-app-collection')!;

    name.value = overrides.name ?? 'traficio';
    name.dispatchEvent(new Event('input'));
    connectionString.value =
      overrides.connectionString ??
      'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    connectionString.dispatchEvent(new Event('input'));
    database.value = overrides.database ?? 'exampledb';
    database.dispatchEvent(new Event('input'));
    if (overrides.collection) {
      collection.value = overrides.collection;
      collection.dispatchEvent(new Event('change'));
    }

    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('labels each field', () => {
    for (const id of [
      'add-app-name',
      'add-app-connection-string',
      'add-app-database',
      'add-app-collection',
    ]) {
      const label = root().querySelector(`label[for="${id}"]`);
      expect(label, `label for #${id}`).toBeTruthy();
      expect(label?.textContent?.trim().length).toBeGreaterThan(0);
    }
  });

  it('gives the connection string field type password and autocomplete off', () => {
    const field = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    expect(field.type).toBe('password');
    expect(field.getAttribute('autocomplete')).toBe('off');
  });

  it('offers the two allowed collection names, with octometer_events as the default', () => {
    const select = root().querySelector<HTMLSelectElement>('#add-app-collection')!;
    const options = Array.from(select.options).map((option) => option.value);
    expect(options).toEqual(['octometer_events', 'octometer_events_v2']);
    expect(select.value).toBe('octometer_events');
  });

  it('sends POST /api/apps with the entered fields', () => {
    fillAndSubmit({ name: 'traficio', database: 'exampledb' });

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      connectionString: 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net',
      database: 'exampledb',
      collection: 'octometer_events',
    });
    req.flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      { status: 201, statusText: 'Created' },
    );
  });

  it('disables the submit button while the request is pending', () => {
    fillAndSubmit();
    const submit = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.disabled).toBe(true);

    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      {
        status: 201,
        statusText: 'Created',
      },
    );
    fixture.detectChanges();
    expect(submit.disabled).toBe(false);
  });

  describe('on a 201 answer', () => {
    function submitAndFlushCreated(summary: AppSummary): void {
      fillAndSubmit({ name: summary.name, database: summary.database });
      httpMock
        .expectOne({ url: '/api/apps', method: 'POST' })
        .flush(summary, { status: 201, statusText: 'Created' });
      fixture.detectChanges();
    }

    it('clears the form fields', () => {
      submitAndFlushCreated({
        appId: 1,
        name: 'traficio',
        database: 'exampledb',
        collection: 'octometer_events',
      });

      expect(root().querySelector<HTMLInputElement>('#add-app-name')!.value).toBe('');
      expect(root().querySelector<HTMLInputElement>('#add-app-connection-string')!.value).toBe('');
      expect(root().querySelector<HTMLInputElement>('#add-app-database')!.value).toBe('');
    });

    it('announces "App added" through the shared status region', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      submitAndFlushCreated({
        appId: 1,
        name: 'traficio',
        database: 'exampledb',
        collection: 'octometer_events',
      });

      expect(announceSpy).toHaveBeenCalledWith('App added');
    });

    it('emits the created app summary', () => {
      const addedSpy = vi.fn();
      fixture.componentRef.instance.added.subscribe(addedSpy);
      const summary: AppSummary = {
        appId: 4,
        name: 'traficio',
        database: 'exampledb',
        collection: 'octometer_events',
      };
      submitAndFlushCreated(summary);

      expect(addedSpy).toHaveBeenCalledWith(summary);
    });
  });

  it('shows the fixed 400 message from the API next to the field, linked with aria-describedby', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'Give a shorter connection string.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain('Give a shorter connection string.');
    const errorEl = root().querySelector('#add-app-error');
    expect(errorEl).toBeTruthy();
    const connectionString = root().querySelector('#add-app-connection-string');
    expect(connectionString?.getAttribute('aria-describedby')).toBe('add-app-error');
  });

  it('shows the fixed 503 message from the API', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'The secret store is not available. Try again.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain('The secret store is not available. Try again.');
  });

  it('shows no inline text for a network failure, and relies on the shell banner', () => {
    fillAndSubmit();
    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).error(new ProgressEvent('error'), {
      status: 0,
      statusText: 'Unknown Error',
    });
    fixture.detectChanges();

    expect(root().querySelector('#add-app-error')).toBeNull();
  });

  it('never writes the connection string to the console, or to the URL, after a save', () => {
    const secret = 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    fillAndSubmit({ connectionString: secret });
    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      {
        status: 201,
        statusText: 'Created',
      },
    );
    fixture.detectChanges();

    for (const spy of [logSpy, warnSpy, errorSpy]) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(secret);
      }
    }
    expect(window.location.href).not.toContain(secret);
    expect(
      root().querySelector<HTMLInputElement>('#add-app-connection-string')!.value,
    ).not.toContain(secret);
  });
});
