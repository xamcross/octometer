import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import { AddAppForm } from './add-app-form';
import type { AppSummary } from './app-registry-api';

/**
 * Finds the first loaded CSS rule with the given selector text. jsdom
 * gives no computed style for a rule that a live element does not use
 * yet, so a test of the rule text reads the stylesheet, not a live
 * style. Angular appends its own content attribute to a selector of an
 * emulated component, so this function strips that attribute first
 * (the pattern of apps.spec.ts).
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
      pathPatterns?: string;
    } = {},
  ): void {
    const name = root().querySelector<HTMLInputElement>('#add-app-name')!;
    const connectionString = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    const database = root().querySelector<HTMLInputElement>('#add-app-database')!;
    const pathPatterns = root().querySelector<HTMLTextAreaElement>('#add-app-path-patterns')!;

    name.value = overrides.name ?? 'traficio';
    name.dispatchEvent(new Event('input'));
    connectionString.value =
      overrides.connectionString ??
      'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net';
    connectionString.dispatchEvent(new Event('input'));
    database.value = overrides.database ?? 'exampledb';
    database.dispatchEvent(new Event('input'));
    if (overrides.pathPatterns) {
      pathPatterns.value = overrides.pathPatterns;
      pathPatterns.dispatchEvent(new Event('input'));
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
      'add-app-path-patterns',
    ]) {
      const label = root().querySelector(`label[for="${id}"]`);
      expect(label, `label for #${id}`).toBeTruthy();
      expect(label?.textContent?.trim().length).toBeGreaterThan(0);
    }
  });

  it('gives the connection string field type password and autocomplete new-password', () => {
    const field = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    expect(field.type).toBe('password');
    expect(field.getAttribute('autocomplete')).toBe('new-password');
  });

  it('gives the submit button the classes btn and btn-primary', () => {
    const submit = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.textContent?.trim()).toBe('Add app');
    expect(submit.classList.contains('btn')).toBe(true);
    expect(submit.classList.contains('btn-primary')).toBe(true);
  });

  it('gives the connection string field no aria-invalid before a submit', () => {
    const field = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    expect(field.hasAttribute('aria-invalid')).toBe(false);
  });

  it('gives the connection string field aria-invalid true once an error message shows', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'Give a shorter connection string.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    const field = root().querySelector<HTMLInputElement>('#add-app-connection-string')!;
    expect(field.getAttribute('aria-invalid')).toBe('true');
  });

  it('gives [aria-invalid="true"] a coral ring inside the ink border', () => {
    const rule = findCssRule('[aria-invalid=true]');
    expect(rule?.style.getPropertyValue('box-shadow')).toContain('--coral');
  });

  it('sends POST /api/apps with the entered fields, and no collection key', () => {
    fillAndSubmit({ name: 'traficio', database: 'exampledb' });

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      connectionString: 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net',
      database: 'exampledb',
    });
    expect(req.request.body).not.toHaveProperty('collection');
    req.flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      { status: 201, statusText: 'Created' },
    );
  });

  it('sends POST /api/apps with pathPatterns when the route pattern field is filled', () => {
    fillAndSubmit({ name: 'traficio', database: 'exampledb', pathPatterns: '/checkout\n/cart' });

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      connectionString: 'mongodb+srv://octotest:S3cr3t-Test-Only@cluster0.example.mongodb.net',
      database: 'exampledb',
      pathPatterns: '/checkout\n/cart',
    });
    req.flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      { status: 201, statusText: 'Created' },
    );
  });

  it('shows no #add-app-collection element', () => {
    expect(root().querySelector('#add-app-collection')).toBeNull();
  });

  it('names the hint id of each input in aria-describedby, and the hint holds the example', () => {
    const connectionString = root().querySelector('#add-app-connection-string')!;
    const database = root().querySelector('#add-app-database')!;
    const pathPatterns = root().querySelector('#add-app-path-patterns')!;

    expect(connectionString.getAttribute('aria-describedby')).toBe(
      'add-app-connection-string-hint',
    );
    expect(database.getAttribute('aria-describedby')).toBe('add-app-database-hint');
    expect(pathPatterns.getAttribute('aria-describedby')).toBe('add-app-path-patterns-hint');

    const connectionStringHint = root().querySelector('#add-app-connection-string-hint');
    expect(connectionStringHint?.textContent).toContain('mongodb://127.0.0.1:27017');
  });

  it('shows the fixed 400 message for an invalid route pattern list, linked with aria-describedby', () => {
    fillAndSubmit({ pathPatterns: '/a?b' });
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'The route pattern at index 0 breaks the route pattern grammar.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain(
      'The route pattern at index 0 breaks the route pattern grammar.',
    );
    const pathPatterns = root().querySelector('#add-app-path-patterns');
    expect(pathPatterns?.getAttribute('aria-describedby')).toBe(
      'add-app-path-patterns-hint add-app-error',
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

  it('sends exactly one POST when the form is submitted three times while a request is pending', () => {
    fillAndSubmit();
    const form = root().querySelector('form')!;
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    form.dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    req.flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      { status: 201, statusText: 'Created' },
    );
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
    expect(connectionString?.getAttribute('aria-describedby')).toBe(
      'add-app-connection-string-hint add-app-error',
    );
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

  it('shows a general sentence for a network failure, linked with aria-describedby', () => {
    fillAndSubmit();
    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).error(new ProgressEvent('error'), {
      status: 0,
      statusText: 'Unknown Error',
    });
    fixture.detectChanges();

    expect(root().textContent).toContain('The monitor did not answer. Try again.');
    const connectionString = root().querySelector('#add-app-connection-string');
    expect(connectionString?.getAttribute('aria-describedby')).toBe(
      'add-app-connection-string-hint add-app-error',
    );
  });

  it('never writes the connection string to the console, to storage, or to the URL, after a save', () => {
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

    fillAndSubmit({ connectionString: secret });
    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).flush(
      { appId: 1, name: 'traficio', database: 'exampledb', collection: 'octometer_events' },
      {
        status: 201,
        statusText: 'Created',
      },
    );
    fixture.detectChanges();

    for (const spy of spies) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(secret);
      }
    }
    expect(window.location.href).not.toContain(secret);
    expect(
      root().querySelector<HTMLInputElement>('#add-app-connection-string')!.value,
    ).not.toContain(secret);
    expect(JSON.stringify(Object.entries(localStorage))).not.toContain(secret);
    expect(JSON.stringify(Object.entries(sessionStorage))).not.toContain(secret);
  });
});
