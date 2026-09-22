import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { DeleteAppDialog } from './delete-app-dialog';

function buildRow(overrides: Partial<AppRow> = {}): AppRow {
  return {
    appId: 5,
    name: 'traficio',
    clicks: 1234,
    uniqueUsers: 2,
    uniqueSessions: 4,
    status: 'OK',
    lastSuccessAt: '2026-09-21T14:23:07.512Z',
    lastError: null,
    nextPollAt: '2026-09-21T14:24:07.512Z',
    ...overrides,
  };
}

/**
 * `jsdom` (the runner of this suite) has no working `HTMLDialogElement`:
 * `showModal`, `close`, and `show` are all absent (verified against the
 * installed `jsdom` package, 2026-09-22). This adds a minimal stand-in for
 * this file only, and it removes the stand-in in `afterEach`, so no other
 * spec file of the shared `jsdom` document (`isolate: false`) sees it.
 *
 * The HTML Standard fires the `close` event from a queued task, and not at
 * once ("close the dialog", the last step). A real `setTimeout` schedules
 * the event on a later task here too, so a test of the focus order sees the
 * real race, and not a false pass from a synchronous event.
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

describe('DeleteAppDialog', () => {
  let fixture: ComponentFixture<DeleteAppDialog>;
  let httpMock: HttpTestingController;
  let announcer: Announcer;

  beforeEach(async () => {
    installDialogPolyfill();
    await TestBed.configureTestingModule({
      imports: [DeleteAppDialog],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    announcer = TestBed.inject(Announcer);
    fixture = TestBed.createComponent(DeleteAppDialog);
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

  function openDialog(): void {
    root().querySelector<HTMLButtonElement>('button.delete-trigger')!.click();
    fixture.detectChanges();
  }

  function typeConfirmName(value: string): void {
    const input = root().querySelector<HTMLInputElement>('.confirm-name')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('opens the dialog on a click on the Delete trigger, and puts the default focus on Cancel', () => {
    openDialog();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    const cancelButton = root().querySelector('button.cancel')!;
    expect(document.activeElement).toBe(cancelButton);
  });

  it('shows the app name and the event count in the dialog text', () => {
    openDialog();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.textContent).toContain('traficio');
    expect(dialog.textContent).toContain(new Intl.NumberFormat().format(1234));
  });

  it('keeps the Delete button disabled until the typed text matches the app name exactly', () => {
    openDialog();
    const confirmButton = root().querySelector<HTMLButtonElement>('button.confirm-delete')!;
    expect(confirmButton.disabled).toBe(true);

    typeConfirmName('traf');
    expect(confirmButton.disabled).toBe(true);

    typeConfirmName('traficio');
    expect(confirmButton.disabled).toBe(false);
  });

  it('closes the dialog and restores focus to the trigger on Cancel', async () => {
    openDialog();
    const trigger = root().querySelector<HTMLButtonElement>('button.delete-trigger')!;

    root().querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();
    await flushQueuedTask();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('gives the dialog an accessible name from its own heading', () => {
    openDialog();

    const dialog = root().querySelector('dialog')!;
    const labelledBy = dialog.getAttribute('aria-labelledby');
    const heading = document.getElementById(labelledBy ?? '');
    expect(heading?.tagName).toBe('H2');
    expect(heading?.textContent?.trim().length).toBeGreaterThan(0);
  });

  it('shows a sentence with no number for a NEVER_POLLED app, not "0 events"', () => {
    fixture.componentRef.setInput('app', buildRow({ status: 'NEVER_POLLED', clicks: 0 }));
    fixture.detectChanges();
    openDialog();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.textContent).toContain('Octometer holds no event of traficio yet.');
    expect(dialog.textContent).not.toContain('0');
  });

  it('sends DELETE /api/apps/{id} with the header Content-Type: application/json', () => {
    openDialog();
    typeConfirmName('traficio');
    root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();

    const req = httpMock.expectOne({ url: '/api/apps/5', method: 'DELETE' });
    expect(req.request.headers.get('Content-Type')).toBe('application/json');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  describe('on a 204 answer', () => {
    it('closes the dialog, announces "App deleted", and emits deleted', () => {
      const announceSpy = vi.spyOn(announcer, 'announce');
      const deletedSpy = vi.fn();
      fixture.componentInstance.deleted.subscribe(deletedSpy);

      openDialog();
      typeConfirmName('traficio');
      root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();
      httpMock
        .expectOne({ url: '/api/apps/5', method: 'DELETE' })
        .flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      const dialog = root().querySelector('dialog')!;
      expect(dialog.hasAttribute('open')).toBe(false);
      expect(announceSpy).toHaveBeenCalledWith('App deleted');
      expect(deletedSpy).toHaveBeenCalled();
    });

    it('does not move the focus back to the trigger once the queued close event later arrives', async () => {
      openDialog();
      typeConfirmName('traficio');
      root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();
      httpMock
        .expectOne({ url: '/api/apps/5', method: 'DELETE' })
        .flush(null, { status: 204, statusText: 'No Content' });
      fixture.detectChanges();

      const trigger = root().querySelector<HTMLButtonElement>('button.delete-trigger')!;
      // A parent view (Manage) would already have moved the focus to its own
      // `<h1>` by the time this queued task runs. It must not fall back to
      // this trigger, whose row a parent may already have removed.
      await flushQueuedTask();
      expect(document.activeElement).not.toBe(trigger);
    });
  });

  it('shows the fixed 404 message inside the dialog, keeps the dialog open, and reports a stale list', () => {
    const staleListSpy = vi.fn();
    fixture.componentInstance.staleList.subscribe(staleListSpy);

    openDialog();
    typeConfirmName('traficio');
    root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();
    httpMock
      .expectOne({ url: '/api/apps/5', method: 'DELETE' })
      .flush({ error: 'The app is not registered.' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.textContent).toContain('The app is not registered.');
    expect(staleListSpy).toHaveBeenCalled();
  });

  it('shows the fixed 503 message inside the dialog', () => {
    openDialog();
    typeConfirmName('traficio');
    root().querySelector<HTMLButtonElement>('button.confirm-delete')!.click();
    httpMock
      .expectOne({ url: '/api/apps/5', method: 'DELETE' })
      .flush(
        { error: 'The secret store is not available. Try again.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    fixture.detectChanges();

    const dialog = root().querySelector('dialog')!;
    expect(dialog.textContent).toContain('The secret store is not available. Try again.');
  });
});
