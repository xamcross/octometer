import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import type { AppDetail } from './app-registry-api';
import { EditAppForm } from './edit-app-form';

/**
 * Finds the first loaded CSS rule with the given selector text (the
 * pattern of apps.spec.ts). Angular appends its own content attribute
 * to a selector of an emulated component, so this function strips that
 * attribute first.
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

  // Design decision D48 (issue #277): opening the form fires
  // GET /api/apps/{id}, to read the app's own published route list.
  // This flushes that request with a fixed answer of no list, unless
  // the caller gives its own detail.
  function openEditor(
    detail: AppDetail = {
      appId: 3,
      name: 'traficio',
      pathPatterns: null,
      pathPatternsPublishedAt: null,
      kitVersion: null,
    },
  ): void {
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

  it('gives the Edit, Save, and Cancel buttons their classes', () => {
    const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;
    expect(toggle.classList.contains('btn')).toBe(true);
    expect(toggle.classList.contains('btn-small')).toBe(true);

    openEditor();
    const save = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    const cancel = root().querySelector<HTMLButtonElement>('button.cancel')!;
    expect(save.classList.contains('btn')).toBe(true);
    expect(save.classList.contains('btn-primary')).toBe(true);
    expect(cancel.classList.contains('btn')).toBe(true);
    expect(cancel.classList.contains('btn-primary')).toBe(false);
  });

  it('gives the connection string field no aria-invalid before a save fails', () => {
    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(connectionString.hasAttribute('aria-invalid')).toBe(false);
  });

  it('gives the connection string field aria-invalid true once a save fails', () => {
    openEditor();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'PATCH' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;
    expect(connectionString.getAttribute('aria-invalid')).toBe('true');
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

  // Design decision D48 (issue #277): the form shows the published
  // route list read-only, with the publish line, instead of the typed
  // field of issue #226.
  it('shows the published route list as a list, and the publish line, on open', () => {
    openEditor({
      appId: 3,
      name: 'traficio',
      pathPatterns: '/checkout\n/cart',
      pathPatternsPublishedAt: '2026-09-21T14:23:07.512Z',
      kitVersion: '1.2.3',
    });

    const heading = Array.from(root().querySelectorAll('h3')).find(
      (element) => element.textContent?.trim() === 'Route patterns',
    );
    expect(heading).toBeTruthy();
    const items = Array.from(root().querySelectorAll('li code')).map((element) =>
      element.textContent?.trim(),
    );
    expect(items).toEqual(['/checkout', '/cart']);
    expect(root().textContent).toContain('kit 1.2.3');
  });

  it('shows "The app has published no list yet." when the app gives no list', () => {
    openEditor();

    expect(root().textContent).toContain('The app has published no list yet.');
    expect(root().querySelector('li')).toBeNull();
  });

  it('names the hint id of the connection string field in aria-describedby, and the hint holds the example', () => {
    openEditor();
    const connectionString = root().querySelector<HTMLInputElement>(
      'input[id^="edit-app-connection-string-"]',
    )!;

    expect(connectionString.getAttribute('aria-describedby')).toBe(
      'edit-app-connection-string-hint-3',
    );

    const connectionStringHint = root().querySelector('#edit-app-connection-string-hint-3');
    expect(connectionStringHint?.textContent).toContain('mongodb://127.0.0.1:27017');
    expect(connectionStringHint?.textContent).toContain(
      'Leave the field empty to keep the stored string.',
    );
  });

  // Design decision D48, the correction of the brief review of
  // 2026-09-28: a failed read shows a message, and Save stays enabled,
  // because the save never carries a pathPatterns field.
  it('shows a message but keeps Save enabled when the GET on open fails, and a submit still sends PATCH', () => {
    openEditorWithFailedGet();

    expect(root().textContent).toContain(
      'Octometer cannot read the route pattern list. Close this form, then open it again.',
    );
    const save = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(save.disabled).toBe(false);

    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends PATCH /api/apps/{id} with the name, and no pathPatterns field', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'traficio-renamed';
    name.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const req = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(req.request.body).toEqual({ name: 'traficio-renamed' });
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends the connectionString field when the user fills it, and no pathPatterns field', () => {
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
    expect(connectionString.getAttribute('aria-describedby')).toBe(
      'edit-app-connection-string-hint-3 edit-app-error-3',
    );
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
    expect(connectionString.getAttribute('aria-describedby')).toBe(
      'edit-app-connection-string-hint-3 edit-app-error-3',
    );
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
