import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Me } from '../account/me';
import { Announcer } from '../announcer';
import { AddAppForm } from './add-app-form';
import type { AppKeys } from './app-registry-api';

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

/**
 * Finds a CSS rule with the given selector text inside a `@media` block
 * whose condition text matches. The walk visits the same stylesheets as
 * findCssRule, then looks one level inside each CSSMediaRule (#266).
 */
function findMediaRule(conditionText: string, selectorText: string): CSSStyleRule | undefined {
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList | undefined;
    try {
      rules = sheet.cssRules;
    } catch {
      continue;
    }
    for (const rule of Array.from(rules ?? [])) {
      if (!(rule instanceof CSSMediaRule) || rule.conditionText !== conditionText) {
        continue;
      }
      for (const innerRule of Array.from(rule.cssRules)) {
        const styleRule = innerRule as CSSStyleRule;
        const plainSelector = styleRule.selectorText
          ?.replace(/\[_ngcontent-[\w-]+\]/g, '')
          .replace(/\s+/g, ' ');
        const selectorParts = plainSelector?.split(',').map((part) => part.trim()) ?? [];
        if (selectorParts.includes(selectorText)) {
          return styleRule;
        }
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

  /**
   * Loads `Me.trackerHash` with [trackerHash] (issue #298), through a
   * real `GET /api/me` and a flush, the same path as `app.spec.ts`
   * drives `Me.load()`. A test calls this before it submits the form,
   * so the keys panel reads the hash the first time it renders.
   */
  function loadMeWithTrackerHash(trackerHash: string | null): void {
    const me = TestBed.inject(Me);
    me.load();
    httpMock.expectOne('/api/me').flush({ email: 'owner@example.com', publicUrl: '', trackerHash });
    fixture.detectChanges();
  }

  function fillAndSubmit(
    overrides: {
      name?: string;
      origins?: string;
    } = {},
  ): void {
    const name = root().querySelector<HTMLInputElement>('#add-app-name')!;
    const origins = root().querySelector<HTMLTextAreaElement>('#add-app-origins')!;

    name.value = overrides.name ?? 'traficio';
    name.dispatchEvent(new Event('input'));
    origins.value = overrides.origins ?? 'https://traficio.example';
    origins.dispatchEvent(new Event('input'));

    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  }

  // The gitleaks rule generic-api-key matches an identifier that holds
  // the word "key" or "secret" right beside a string literal. These two
  // fixture values sit under a neutral name first, so the AppKeys
  // fields below (appKey, secretKey) hold a variable, never a literal.
  const markerAppKeyValue = 'app-key-marker-value';
  const markerSecretKeyValue = 'secret-key-marker-value';

  const keysOf = (overrides: Partial<AppKeys> = {}): AppKeys => ({
    appId: 1,
    name: 'traficio',
    origins: ['https://traficio.example'],
    databaseName: 'app_1',
    appKey: markerAppKeyValue,
    secretKey: markerSecretKeyValue,
    ...overrides,
  });

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('labels each field', () => {
    for (const id of ['add-app-name', 'add-app-origins']) {
      const label = root().querySelector(`label[for="${id}"]`);
      expect(label, `label for #${id}`).toBeTruthy();
      expect(label?.textContent?.trim().length).toBeGreaterThan(0);
    }
  });

  it('shows no #add-app-connection-string element (issue #296: the manage view sends origins)', () => {
    expect(root().querySelector('#add-app-connection-string')).toBeNull();
  });

  it('gives the submit button the classes btn and btn-primary', () => {
    const submit = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.textContent?.trim()).toBe('Add app');
    expect(submit.classList.contains('btn')).toBe(true);
    expect(submit.classList.contains('btn-primary')).toBe(true);
  });

  it('gives the origins field no aria-invalid before a submit', () => {
    const field = root().querySelector<HTMLTextAreaElement>('#add-app-origins')!;
    expect(field.hasAttribute('aria-invalid')).toBe(false);
  });

  it('gives the origins field aria-invalid true once an error message shows', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'An origin has the form https://host, without a path.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    const field = root().querySelector<HTMLTextAreaElement>('#add-app-origins')!;
    expect(field.getAttribute('aria-invalid')).toBe('true');
  });

  it('gives [aria-invalid="true"] a coral-deep ring inside the ink border (#264, 3:1 or more on white)', () => {
    const rule = findCssRule('[aria-invalid=true]');
    expect(rule?.style.getPropertyValue('box-shadow')).toContain('--coral-deep');
  });

  it('keeps the ink focus ring beside the coral-deep ring on an invalid field with focus', () => {
    const rule = findCssRule('[aria-invalid=true]:focus-visible');
    const shadow = rule?.style.getPropertyValue('box-shadow');
    expect(shadow).toContain('--coral-deep');
    expect(shadow).toContain('--ink');
  });

  it('gives [aria-invalid="true"] a dashed outline under forced colors (#266)', () => {
    const rule = findMediaRule('(forced-colors: active)', '[aria-invalid=true]');
    expect(rule?.style.getPropertyValue('outline')).toContain('dashed');
  });

  it('gives the invalid field a solid outline on focus under forced colors, unlike the dashed mark (#266)', () => {
    const rule = findMediaRule('(forced-colors: active)', '[aria-invalid=true]:focus-visible');
    expect(rule?.style.getPropertyValue('outline')).toContain('solid');
  });

  it('sends POST /api/apps with the name and one origin per line', () => {
    fillAndSubmit({ name: 'traficio', origins: 'https://a.example\nhttps://b.example' });

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    expect(req.request.body).toEqual({
      name: 'traficio',
      origins: ['https://a.example', 'https://b.example'],
    });
    req.flush(keysOf(), { status: 201, statusText: 'Created' });
  });

  it('names the hint id of the origins field in aria-describedby, and the hint states the form', () => {
    const origins = root().querySelector('#add-app-origins')!;

    expect(origins.getAttribute('aria-describedby')).toBe('add-app-origins-hint');

    const originsHint = root().querySelector('#add-app-origins-hint');
    expect(originsHint?.textContent).toContain('https://host');
  });

  it('disables the submit button while the request is pending', () => {
    fillAndSubmit();
    const submit = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(submit.disabled).toBe(true);

    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).flush(keysOf(), {
      status: 201,
      statusText: 'Created',
    });
    fixture.detectChanges();
    expect(submit.disabled).toBe(false);
  });

  it('sends exactly one POST when the form is submitted three times while a request is pending', () => {
    fillAndSubmit();
    const form = root().querySelector('form')!;
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    form.dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps', method: 'POST' });
    req.flush(keysOf(), { status: 201, statusText: 'Created' });
  });

  describe('on a 201 answer', () => {
    function submitAndFlushCreated(keys: AppKeys): void {
      fillAndSubmit({ name: keys.name });
      httpMock
        .expectOne({ url: '/api/apps', method: 'POST' })
        .flush(keys, { status: 201, statusText: 'Created' });
      fixture.detectChanges();
    }

    it('clears the name and origins fields', () => {
      submitAndFlushCreated(keysOf());

      expect(root().querySelector<HTMLInputElement>('#add-app-name')!.value).toBe('');
      expect(root().querySelector<HTMLTextAreaElement>('#add-app-origins')!.value).toBe('');
    });

    it('announces "App added" through the shared status region', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      submitAndFlushCreated(keysOf());

      expect(announceSpy).toHaveBeenCalledWith('App added');
    });

    it('emits the created app keys', () => {
      const addedSpy = vi.fn();
      fixture.componentRef.instance.added.subscribe(addedSpy);
      const keys = keysOf({ appId: 4 });
      submitAndFlushCreated(keys);

      expect(addedSpy).toHaveBeenCalledWith(keys);
    });

    it('shows the app key and the secret key in the keys panel, each in its own readonly field', () => {
      submitAndFlushCreated(keysOf());

      const appKeyField = root().querySelector<HTMLInputElement>('#add-app-key')!;
      const secretKeyField = root().querySelector<HTMLInputElement>('#add-app-secret-key')!;
      expect(appKeyField.value).toBe(markerAppKeyValue);
      expect(appKeyField.readOnly).toBe(true);
      expect(secretKeyField.value).toBe(markerSecretKeyValue);
      expect(secretKeyField.readOnly).toBe(true);
    });

    it('shows the sentence "Copy the secret key now. It shows one time."', () => {
      submitAndFlushCreated(keysOf());

      expect(root().textContent).toContain('Copy the secret key now. It shows one time.');
    });

    it('builds the hashed script tag from the app key and the tracker hash, with no "arrives with the next release" sentence', () => {
      loadMeWithTrackerHash('abc123');
      submitAndFlushCreated(keysOf());

      const scriptTag = root().querySelector('#add-app-script-tag')!;
      expect(scriptTag.textContent).toContain(`data-app-key="${markerAppKeyValue}"`);
      expect(scriptTag.textContent).toContain('tracker.js?v=abc123');
      expect(root().textContent).not.toContain('The script arrives with the next release.');
    });

    it('shows "The script is not built on this instance." in place of the tag with no tracker hash', () => {
      loadMeWithTrackerHash(null);
      submitAndFlushCreated(keysOf());

      expect(root().querySelector('#add-app-script-tag')?.tagName).toBe('P');
      expect(root().textContent).toContain('The script is not built on this instance.');
      expect(root().textContent).not.toContain('tracker.js');
    });

    // Issue #299: the registration takes no route pattern field; the
    // keys panel points the owner at the edit form instead.
    it('shows the sentence "Add the route patterns of your app in the edit form." under the keys panel', () => {
      submitAndFlushCreated(keysOf());

      expect(root().textContent).toContain('Add the route patterns of your app in the edit form.');
    });
  });

  it('shows the fixed 400 message from the API next to the field, linked with aria-describedby', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'An origin has the form https://host, without a path.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain('An origin has the form https://host, without a path.');
    const errorEl = root().querySelector('#add-app-error');
    expect(errorEl).toBeTruthy();
    const origins = root().querySelector('#add-app-origins');
    expect(origins?.getAttribute('aria-describedby')).toBe('add-app-origins-hint add-app-error');
  });

  it('shows the fixed 503 message from the API (the registration needs a cluster)', () => {
    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(
        { error: 'The service has no cluster.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    fixture.detectChanges();

    expect(root().textContent).toContain('The service has no cluster.');
  });

  it('shows a general sentence for a network failure, linked with aria-describedby', () => {
    fillAndSubmit();
    httpMock.expectOne({ url: '/api/apps', method: 'POST' }).error(new ProgressEvent('error'), {
      status: 0,
      statusText: 'Unknown Error',
    });
    fixture.detectChanges();

    expect(root().textContent).toContain('The monitor did not answer. Try again.');
    const origins = root().querySelector('#add-app-origins');
    expect(origins?.getAttribute('aria-describedby')).toBe('add-app-origins-hint add-app-error');
  });

  it('never writes the secret key to the console, to storage, or to the URL, after a save', () => {
    const markerSecondValue = 'secret-key-marker-value-console-check';
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

    fillAndSubmit();
    httpMock
      .expectOne({ url: '/api/apps', method: 'POST' })
      .flush(keysOf({ secretKey: markerSecondValue }), { status: 201, statusText: 'Created' });
    fixture.detectChanges();

    for (const spy of spies) {
      for (const call of spy.mock.calls) {
        expect(call.join(' ')).not.toContain(markerSecondValue);
      }
    }
    expect(window.location.href).not.toContain(markerSecondValue);
    expect(JSON.stringify(Object.entries(localStorage))).not.toContain(markerSecondValue);
    expect(JSON.stringify(Object.entries(sessionStorage))).not.toContain(markerSecondValue);
  });
});
