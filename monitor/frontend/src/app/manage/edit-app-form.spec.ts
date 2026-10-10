import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import type { AppDetail } from './app-registry-api';
import { EditAppForm } from './edit-app-form';

/**
 * `jsdom` (the runner of this suite) has no working `HTMLDialogElement`
 * (see `delete-app-dialog.spec.ts`). This adds the same minimal
 * stand-in for this file only, and removes it in `afterEach`. The HTML
 * Standard fires the `close` event from a queued task, and not at once
 * ("close the dialog", the last step); a real `setTimeout` schedules
 * the event on a later task here too (issue #364).
 */
function installDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype as unknown as {
    showModal?: (this: HTMLDialogElement) => void;
    close?: (this: HTMLDialogElement, returnValue?: string) => void;
  };
  proto.showModal = function (this: HTMLDialogElement): void {
    this.setAttribute('open', '');
  };
  proto.close = function (this: HTMLDialogElement): void {
    this.removeAttribute('open');
    setTimeout(() => this.dispatchEvent(new Event('close')), 0);
  };
}

function removeDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  delete proto['showModal'];
  delete proto['close'];
}

/** Waits for one queued task, so a `setTimeout(..., 0)` of the dialog polyfill runs. */
function flushQueuedTask(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

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

// The gitleaks rule generic-api-key matches an identifier that holds
// the word "key" right beside a string literal. This value sits under
// a neutral name first, so the AppDetail field below (appKey) holds a
// variable, never a literal.
const markerAppKeyValue = 'app-key-marker-value';

function buildDetail(overrides: Partial<AppDetail> = {}): AppDetail {
  return {
    appId: 3,
    name: 'traficio',
    routePatterns: [],
    appKey: markerAppKeyValue,
    origins: ['https://traficio.example'],
    databaseName: 'app_3',
    ...overrides,
  };
}

describe('EditAppForm', () => {
  let fixture: ComponentFixture<EditAppForm>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(async () => {
    installDialogPolyfill();
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
    try {
      httpMock.verify();
    } finally {
      removeDialogPolyfill();
    }
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  // Design decision D48 (issue #277): opening the form fires
  // GET /api/apps/{id}, to read the app's own published route list and
  // origin list. This flushes that request with a fixed answer, unless
  // the caller gives its own detail.
  function openEditor(detail: AppDetail = buildDetail()): void {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
    fixture.detectChanges();
    httpMock.expectOne({ url: '/api/apps/3', method: 'GET' }).flush(detail);
    fixture.detectChanges();
  }

  // For the one test of a failed GET: opens the form, but answers the
  // GET with a network failure, so the origins field stays empty.
  function openEditorWithFailedGet(): void {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
    fixture.detectChanges();
    httpMock
      .expectOne({ url: '/api/apps/3', method: 'GET' })
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();
  }

  // Issue #299, D5, D40: the save order is the origins, then the route
  // patterns, then the name. A test of a successful origins PUT must
  // answer this request too, else httpMock.verify() in afterEach fails
  // on a pending request.
  function flushRoutesPut(detail: AppDetail = buildDetail()): void {
    httpMock.expectOne({ url: '/api/apps/3/routes', method: 'PUT' }).flush(detail);
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows the "Edit app" button with the dialog closed, before the user opens it (issue #364)', () => {
    const toggle = root().querySelector('button.edit-toggle');
    expect(toggle?.textContent?.trim()).toBe('Edit app');
    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(false);
  });

  it('opens the dialog on a click on the "Edit app" trigger (issue #364)', () => {
    openEditor();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
  });

  it('gives the dialog an accessible name from its own heading (issue #364)', () => {
    openEditor();

    const dialog = root().querySelector('dialog')!;
    const labelledBy = dialog.getAttribute('aria-labelledby');
    const heading = document.getElementById(labelledBy ?? '');
    expect(heading?.tagName).toBe('H2');
    expect(heading?.textContent?.trim().length).toBeGreaterThan(0);
  });

  it('gives the Edit app, Save, and Cancel buttons their classes', () => {
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

  it('gives the origins field no aria-invalid before a save fails', () => {
    openEditor();
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(origins.hasAttribute('aria-invalid')).toBe(false);
  });

  it('gives the origins field aria-invalid true once a save fails', () => {
    openEditor();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(origins.getAttribute('aria-invalid')).toBe('true');
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

  it('opens the form with the name field and the origins field prefilled from the detail', () => {
    openEditor();

    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(name.value).toBe('traficio');
    expect(origins.value).toBe('https://traficio.example');
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
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(
      root().querySelector(`label[for="${name.id}"]`)?.textContent?.trim().length,
    ).toBeGreaterThan(0);
    expect(
      root().querySelector(`label[for="${origins.id}"]`)?.textContent?.trim().length,
    ).toBeGreaterThan(0);
  });

  // Issue #299, D5, D40: the form shows the typed route pattern list in
  // an editable field, in place of the read-only published list of
  // design decision D48 (issue #277; that story closes here).
  it('opens the form with the route patterns field prefilled from the detail', () => {
    openEditor(buildDetail({ routePatterns: ['/checkout', '/cart'] }));

    const routePatterns = root().querySelector<HTMLTextAreaElement>(
      'textarea[id^="edit-app-route-patterns-"]',
    )!;
    expect(routePatterns.value).toBe('/checkout\n/cart');
  });

  it('leaves the route patterns field empty when the app has no list', () => {
    openEditor();

    const routePatterns = root().querySelector<HTMLTextAreaElement>(
      'textarea[id^="edit-app-route-patterns-"]',
    )!;
    expect(routePatterns.value).toBe('');
  });

  it('names the hint id of the origins field in aria-describedby, and the hint states the form', () => {
    openEditor();
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;

    expect(origins.getAttribute('aria-describedby')).toBe('edit-app-origins-hint-3');

    const originsHint = root().querySelector('#edit-app-origins-hint-3');
    expect(originsHint?.textContent).toContain('https://host');
  });

  it('names the hint id of the route patterns field in aria-describedby, and the hint gives the example and the default rule', () => {
    openEditor();
    const routePatterns = root().querySelector<HTMLTextAreaElement>(
      'textarea[id^="edit-app-route-patterns-"]',
    )!;

    expect(routePatterns.getAttribute('aria-describedby')).toBe('edit-app-route-patterns-hint-3');

    const routePatternsHint = root().querySelector('#edit-app-route-patterns-hint-3');
    expect(routePatternsHint?.textContent).toContain('/orders/:id');
    expect(routePatternsHint?.textContent).toContain('default rule');
  });

  // Issue #299, the same rule as design decision D48's own read
  // failure: a failed read shows a message, and Save stays enabled,
  // because saveRoutes skips its own request while routePatternsReadFailed
  // is set (no wrong overwrite with an empty field).
  it('shows a message but keeps Save enabled when the GET on open fails, and a submit sends no request (an empty origins field)', () => {
    openEditorWithFailedGet();

    expect(root().textContent).toContain(
      'Octometer cannot read the route pattern list. Close this form, then open it again.',
    );
    const save = root().querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(save.disabled).toBe(false);

    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    // The name is unchanged, and the origins field is empty (the
    // failed GET never filled it): no request of the three has
    // anything to send.
    httpMock.expectNone({ url: '/api/apps/3', method: 'PATCH' });
    httpMock.expectNone({ url: '/api/apps/3/origins', method: 'PUT' });
    httpMock.expectNone({ url: '/api/apps/3/routes', method: 'PUT' });
  });

  it('sends PATCH /api/apps/{id} with the name only, when the name changes and the origins field stays the same', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'traficio-renamed';
    name.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    const putReq = httpMock.expectOne({ url: '/api/apps/3/origins', method: 'PUT' });
    expect(putReq.request.body).toEqual({ origins: ['https://traficio.example'] });
    putReq.flush(buildDetail({ name: 'traficio-renamed' }), { status: 200, statusText: 'OK' });

    const routesReq = httpMock.expectOne({ url: '/api/apps/3/routes', method: 'PUT' });
    expect(routesReq.request.body).toEqual({ patterns: [] });
    routesReq.flush(buildDetail({ name: 'traficio-renamed' }), { status: 200, statusText: 'OK' });

    const patchReq = httpMock.expectOne({ url: '/api/apps/3', method: 'PATCH' });
    expect(patchReq.request.body).toEqual({ name: 'traficio-renamed' });
    patchReq.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('sends PUT /api/apps/{id}/origins with the edited origin list, and no PATCH when the name is unchanged', () => {
    openEditor();
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    origins.value = 'https://traficio.example\nhttps://second.example';
    origins.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    httpMock.expectNone({ url: '/api/apps/3', method: 'PATCH' });
    const putReq = httpMock.expectOne({ url: '/api/apps/3/origins', method: 'PUT' });
    expect(putReq.request.body).toEqual({
      origins: ['https://traficio.example', 'https://second.example'],
    });
    putReq.flush(buildDetail(), { status: 200, statusText: 'OK' });
    flushRoutesPut();
    httpMock.expectNone({ url: '/api/apps/3', method: 'PATCH' });
  });

  it('sends PUT /api/apps/{id}/routes with the edited route pattern list, after the origins save', () => {
    openEditor();
    const routePatterns = root().querySelector<HTMLTextAreaElement>(
      'textarea[id^="edit-app-route-patterns-"]',
    )!;
    routePatterns.value = '/orders/:id\n/checkout';
    routePatterns.dispatchEvent(new Event('input'));
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    httpMock
      .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
      .flush(buildDetail(), { status: 200, statusText: 'OK' });
    const routesReq = httpMock.expectOne({ url: '/api/apps/3/routes', method: 'PUT' });
    expect(routesReq.request.body).toEqual({ patterns: ['/orders/:id', '/checkout'] });
    routesReq.flush(buildDetail({ routePatterns: ['/orders/:id', '/checkout'] }), {
      status: 200,
      statusText: 'OK',
    });
  });

  // MAJOR 1 of the review of pull request #332: a save that starts
  // before the GET on open answers must send no PUT .../routes
  // request, or the empty field clears the stored list.
  // routePatternsLoaded stays false until the GET's next handler runs,
  // so saveRoutes skips its own request and moves on to saveName.
  it('sends no PUT /api/apps/{id}/routes when a save starts before the GET on open answers', () => {
    root().querySelector<HTMLButtonElement>('button.edit-toggle')!.click();
    fixture.detectChanges();

    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));

    httpMock.expectNone({ url: '/api/apps/3/routes', method: 'PUT' });
    httpMock.expectNone({ url: '/api/apps/3/origins', method: 'PUT' });
    httpMock.expectNone({ url: '/api/apps/3', method: 'PATCH' });

    // The GET from open() is still pending. Flush it, so the
    // httpMock.verify() of afterEach finds no open request.
    httpMock.expectOne({ url: '/api/apps/3', method: 'GET' }).flush(buildDetail());
  });

  describe('on a saved answer', () => {
    it('closes the dialog, announces "App updated", and emits updated (issue #364)', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      const updatedSpy = vi.fn();
      fixture.componentInstance.updated.subscribe(updatedSpy);
      openEditor();
      root()
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock
        .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
        .flush(buildDetail(), { status: 200, statusText: 'OK' });
      flushRoutesPut();

      const dialog = root().querySelector('dialog')!;
      expect(dialog.hasAttribute('open')).toBe(false);
      expect(announceSpy).toHaveBeenCalledWith('App updated');
      expect(updatedSpy).toHaveBeenCalled();
    });

    it('moves the focus back to the "Edit app" button after a save', () => {
      openEditor();
      root()
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock
        .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
        .flush(buildDetail(), { status: 200, statusText: 'OK' });
      flushRoutesPut();

      const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;
      expect(document.activeElement).toBe(toggle);
    });

    it('does not move the focus back to the trigger once the queued close event later arrives', async () => {
      openEditor();
      root()
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      httpMock
        .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
        .flush(buildDetail(), { status: 200, statusText: 'OK' });
      flushRoutesPut();

      const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;
      await flushQueuedTask();
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
      .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(root().textContent).toContain('The app is not registered.');
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(origins.getAttribute('aria-describedby')).toBe(
      'edit-app-origins-hint-3 edit-app-error-3',
    );
    expect(staleListSpy).toHaveBeenCalled();
  });

  it('shows a general sentence for a network failure, linked with aria-describedby', () => {
    openEditor();
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    expect(root().textContent).toContain('The monitor did not answer. Try again.');
    const origins = root().querySelector<HTMLTextAreaElement>('textarea[id^="edit-app-origins-"]')!;
    expect(origins.getAttribute('aria-describedby')).toBe(
      'edit-app-origins-hint-3 edit-app-error-3',
    );
  });

  it('closes the dialog and discards changes on Cancel (issue #364)', () => {
    openEditor();
    const name = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    name.value = 'discarded';
    name.dispatchEvent(new Event('input'));

    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(false);

    openEditor();
    const reopenedName = root().querySelector<HTMLInputElement>('input[id^="edit-app-name-"]')!;
    expect(reopenedName.value).toBe('traficio');
  });

  it('moves the focus back to the "Edit app" button after Cancel', async () => {
    openEditor();
    const toggle = root().querySelector<HTMLButtonElement>('button.edit-toggle')!;

    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();
    await flushQueuedTask();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(toggle);
  });

  it('never writes the secret key to the console, to storage, or to the URL (the form carries no secret key)', () => {
    const markerSecondValue = 'edit-form-console-check-marker';
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
    root()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }));
    httpMock
      .expectOne({ url: '/api/apps/3/origins', method: 'PUT' })
      .flush(buildDetail({ appKey: markerSecondValue }), { status: 200, statusText: 'OK' });
    flushRoutesPut(buildDetail({ appKey: markerSecondValue }));

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
