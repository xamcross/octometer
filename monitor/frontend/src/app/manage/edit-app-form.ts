import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import {
  AppDetail,
  UpdateOriginsRequest,
  UpdateRoutesRequest,
  readApiErrorMessage,
} from './app-registry-api';
import { ORIGINS_HINT, ROUTE_PATTERNS_HINT } from './field-hints';

/**
 * The control of issue #296 (D52) and issue #299 (D5, D40): the save
 * order is the origins, then the route patterns, then a changed name
 * (the correction of the brief review of 2026-10-01, MAJOR 2 of #323:
 * a rename must never ship ahead of a rejected list). Each row of the
 * list gets its own instance. The trigger button reads "Edit app"
 * (the correction of the brief review of 2026-10-01): it opens a
 * dialog with the name, the origin list, and the route pattern list
 * of the app (issue #364, D3), the same native `<dialog>` pattern as
 * `DeleteAppDialog` and `NewSecretKeyDialog`. The table row keeps its
 * own height: the open dialog sits in the browser top layer, not
 * inside the Actions cell.
 *
 * The connection string field of D13 is gone (issue #296): the keys
 * panel of the add form and the "New secret key" action cover the
 * hosted path; this form edits the name, the origins, and the route
 * patterns of any app.
 *
 * The focus moves to the name field on open, and back to the trigger
 * button after a cancel, an Escape, or a successful save, so a
 * keyboard user stays near the row (the pattern of
 * `DeleteAppDialog.onDialogClose`). A close from Cancel or from Escape
 * fires the native `close` event from a queued task, and not at once
 * (the HTML Standard, "close the dialog"); a successful save closes
 * the dialog itself and focuses the trigger at once, then
 * `restoreFocusOnClose` skips the later, queued focus step (the
 * pattern of `NewSecretKeyDialog.done`).
 *
 * Issue #299, D5, D40: the typed route pattern list returns to this
 * form, one pattern per line, in place of the read-only published list
 * of design decision D48 (issue #277; that story closes here). It
 * reads `GET /api/apps/{id}` on open, so it shows the app's own stored
 * list, ready to edit.
 *
 * A 404 on the save means a second window already removed the app. The
 * form emits `staleList` in that case, so the parent reads the list again
 * and drops the stale row.
 */
@Component({
  selector: 'app-edit-app-form',
  templateUrl: './edit-app-form.html',
  styleUrl: './edit-app-form.css',
})
export class EditAppForm {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  /** The app the control edits. */
  readonly app = input.required<AppRow>();

  /** Emits after a successful save. */
  readonly updated = output<void>();

  /** Emits when a 404 answer shows that the app is no longer registered. */
  readonly staleList = output<void>();

  private readonly dialogEl = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly nameField = viewChild<ElementRef<HTMLInputElement>>('nameField');
  private readonly editToggle = viewChild.required<ElementRef<HTMLButtonElement>>('editToggle');

  protected readonly originsHint = ORIGINS_HINT;
  protected readonly routePatternsHint = ROUTE_PATTERNS_HINT;

  /** True while `onDialogClose` must skip the trigger-focus step (a save just succeeded). */
  private restoreFocusOnClose = true;

  /**
   * True while the dialog holds its form (issue #364). The heading and
   * the form render only then: the "Name" field and the "Origins" field
   * of this dialog share their label text with the add-app form's own
   * fields, so a closed dialog must hold neither one, or a page-level
   * `getByLabel` query of the real app (and of the end-to-end suite)
   * resolves to more than one element.
   */
  protected readonly editing = signal(false);

  protected readonly name = signal('');
  protected readonly origins = signal('');
  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  /** The typed route pattern list, one pattern per line (issue #299, D5, D40). */
  protected readonly routePatterns = signal('');

  /**
   * True after a failed `GET /api/apps/{id}` (issue #299, the same
   * rule as design decision D48's own read failure). The field stays
   * empty; Save stays enabled, so a rename or an origin change still
   * works, with no wrong overwrite of the route pattern list: the
   * empty field never ships, because [saveRoutes] skips its own
   * request while this flag is set.
   */
  protected readonly routePatternsReadFailed = signal(false);

  /**
   * True once the `GET /api/apps/{id}` answer sets the route pattern
   * list (issue #299, MAJOR 1 of the review of pull request #332). A
   * save that starts before the answer must send no
   * `PUT .../routes` request, or an empty field clears the stored
   * list; [saveRoutes] waits for this signal and still runs the name
   * save and the origins save.
   */
  protected readonly routePatternsLoaded = signal(false);

  protected get nameId(): string {
    return `edit-app-name-${this.app().appId}`;
  }

  protected get originsId(): string {
    return `edit-app-origins-${this.app().appId}`;
  }

  protected get originsHintId(): string {
    return `edit-app-origins-hint-${this.app().appId}`;
  }

  protected get routePatternsId(): string {
    return `edit-app-route-patterns-${this.app().appId}`;
  }

  protected get routePatternsHintId(): string {
    return `edit-app-route-patterns-hint-${this.app().appId}`;
  }

  protected get errorId(): string {
    return `edit-app-error-${this.app().appId}`;
  }

  protected get headingId(): string {
    return `edit-app-heading-${this.app().appId}`;
  }

  /**
   * Opens the dialog with the current name, the current origin list, and
   * the current route pattern list. The default focus goes to the name
   * field, the first field of the form (issue #364).
   *
   * The focus call sets `preventScroll: true` (the fix of the CI run
   * 38065369384 of pull request #465): with no option, a browser can
   * still scroll the page toward the name field's pre-promotion
   * position in the document, before it paints the dialog as a fixed,
   * centered top-layer box. The scroll then stays, and it moves the row
   * below by a few pixels, so the table-row scenario of issue #364
   * reads two different `y` values for one static row. The amount
   * depends on the exact height of each row above the dialog, so a
   * font change alone can turn a 0 px shift into a few-pixel one, with
   * no change to the dialog's own code. `preventScroll: true` removes
   * the scroll call itself, so the page never moves.
   */
  protected open(): void {
    this.name.set(this.app().name);
    this.origins.set('');
    this.routePatterns.set('');
    this.routePatternsReadFailed.set(false);
    this.routePatternsLoaded.set(false);
    this.errorMessage.set(null);
    this.editing.set(true);
    this.dialogEl().nativeElement.showModal();
    afterNextRender(() => this.nameField()?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
    this.http.get<AppDetail>(`/api/apps/${this.app().appId}`).subscribe({
      next: (detail) => {
        this.origins.set(detail.origins.join('\n'));
        this.routePatterns.set(detail.routePatterns.join('\n'));
        this.routePatternsLoaded.set(true);
      },
      // Issue #299: a failed read must not stay silent. It shows a
      // message next to the route pattern field. Save stays enabled:
      // routePatternsReadFailed keeps saveRoutes from sending an empty
      // list that would clear the app's real list.
      error: () => {
        this.routePatternsReadFailed.set(true);
        this.errorMessage.set(
          'Octometer cannot read the route pattern list. Close this form, then open it again.',
        );
      },
    });
  }

  /** Closes the dialog. It discards each typed value; `onDialogClose` moves the focus back to "Edit app". */
  protected cancel(): void {
    this.dialogEl().nativeElement.close();
  }

  /**
   * Restores the focus to the "Edit app" trigger after a close of the
   * dialog from Cancel or from Escape. Skips the step, once, after a
   * successful save: `onSaved` already moved the focus and cleared the
   * flag (the pattern of `DeleteAppDialog.onDialogClose`).
   */
  protected onDialogClose(): void {
    this.editing.set(false);
    if (!this.restoreFocusOnClose) {
      this.restoreFocusOnClose = true;
      return;
    }
    this.editToggle().nativeElement.focus();
  }

  protected onNameInput(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected onOriginsInput(event: Event): void {
    this.origins.set((event.target as HTMLTextAreaElement).value);
  }

  protected onRoutePatternsInput(event: Event): void {
    this.routePatterns.set((event.target as HTMLTextAreaElement).value);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    this.saveOrigins();
  }

  // The origins save now runs first, then the routes save, then the
  // name save (the correction of the brief review of 2026-10-01, M6;
  // MAJOR 2 of the review of pull request #323): each PUT alone can
  // fail on a rule the name PATCH never checks, so a rename never
  // ships ahead of a rejected origin list or a rejected route pattern
  // list. The API rejects an empty origin list (one to 20 origins,
  // M11). An app of the legacy connection-string path (D32) starts
  // with an empty list and an empty textarea here; a save with no
  // typed origin then skips this request, so a plain rename of such an
  // app still works, the same as before this story.
  private saveOrigins(): void {
    const lines = linesOf(this.origins());
    if (lines.length === 0) {
      this.saveRoutes();
      return;
    }
    const request: UpdateOriginsRequest = { origins: lines };
    this.http.put<AppDetail>(`/api/apps/${this.app().appId}/origins`, request).subscribe({
      next: () => {
        this.updated.emit();
        this.saveRoutes();
      },
      error: (error: unknown) => this.onFailed(error),
    });
  }

  // Issue #299, D5, D40: an empty route pattern field clears the list
  // (the default rule then applies to every path), so this save sends
  // its request even for an empty field, unlike saveOrigins above. A
  // failed GET of the stored list (routePatternsReadFailed) skips this
  // request instead: an empty field must never clear the app's real
  // list by accident. A save that starts before the GET answer
  // (routePatternsLoaded still false, MAJOR 1 of the review of pull
  // request #332) skips this request too, for the same reason; the
  // name save still runs.
  private saveRoutes(): void {
    if (this.routePatternsReadFailed() || !this.routePatternsLoaded()) {
      this.saveName();
      return;
    }
    const request: UpdateRoutesRequest = { patterns: linesOf(this.routePatterns()) };
    this.http.put<AppDetail>(`/api/apps/${this.app().appId}/routes`, request).subscribe({
      next: () => {
        this.updated.emit();
        this.saveName();
      },
      error: (error: unknown) => this.onFailed(error),
    });
  }

  // Each successful call emits `updated` right away (MAJOR 2), so the
  // list shows the saved part even when the other call then fails;
  // `onFailed` shows the failed call's error, with the saved part
  // already visible in the list.
  private saveName(): void {
    const newName = this.name().trim();
    if (newName === this.app().name) {
      this.onSaved();
      return;
    }
    this.http.patch<void>(`/api/apps/${this.app().appId}`, { name: newName }).subscribe({
      next: () => {
        this.updated.emit();
        this.onSaved();
      },
      error: (error: unknown) => this.onFailed(error),
    });
  }

  private onSaved(): void {
    this.pending.set(false);
    this.restoreFocusOnClose = false;
    this.editing.set(false);
    this.dialogEl().nativeElement.close();
    this.announcer.announce('App updated');
    this.editToggle().nativeElement.focus();
  }

  private onFailed(error: unknown): void {
    this.pending.set(false);
    const message = readApiErrorMessage(error);
    this.errorMessage.set(message);
    if (message) {
      this.announcer.announce(message);
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      this.staleList.emit();
    }
  }
}

/** Splits a textarea value into one origin per non-blank line (issue #296). */
function linesOf(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}
