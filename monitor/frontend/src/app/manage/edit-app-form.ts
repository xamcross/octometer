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
 * (the correction of the brief review of 2026-10-01): it opens the one
 * form below, with the name, the origin list, and the route pattern
 * list of the app.
 *
 * The connection string field of D13 is gone (issue #296): the keys
 * panel of the add form and the "New secret key" action cover the
 * hosted path; this form edits the name, the origins, and the route
 * patterns of any app.
 *
 * The focus moves back to the trigger button after a cancel and after
 * a successful save, so a keyboard user stays near the row (the
 * pattern of `DeleteAppDialog.onDialogClose`).
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

  private readonly nameField = viewChild<ElementRef<HTMLInputElement>>('nameField');
  private readonly editToggle = viewChild<ElementRef<HTMLButtonElement>>('editToggle');

  protected readonly originsHint = ORIGINS_HINT;
  protected readonly routePatternsHint = ROUTE_PATTERNS_HINT;

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

  /** Opens the form with the current name, the current origin list, and the current route pattern list. */
  protected open(): void {
    this.name.set(this.app().name);
    this.origins.set('');
    this.routePatterns.set('');
    this.routePatternsReadFailed.set(false);
    this.errorMessage.set(null);
    this.editing.set(true);
    afterNextRender(() => this.nameField()?.nativeElement.focus(), { injector: this.injector });
    this.http.get<AppDetail>(`/api/apps/${this.app().appId}`).subscribe({
      next: (detail) => {
        this.origins.set(detail.origins.join('\n'));
        this.routePatterns.set(detail.routePatterns.join('\n'));
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

  /** Closes the form. It discards each typed value, and moves the focus back to "Edit origins". */
  protected cancel(): void {
    this.editing.set(false);
    afterNextRender(() => this.editToggle()?.nativeElement.focus(), { injector: this.injector });
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
  // list by accident.
  private saveRoutes(): void {
    if (this.routePatternsReadFailed()) {
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
    this.editing.set(false);
    this.announcer.announce('App updated');
    afterNextRender(() => this.editToggle()?.nativeElement.focus(), { injector: this.injector });
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
