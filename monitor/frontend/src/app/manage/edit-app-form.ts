import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { formatLocalDateTime } from '../status-format';
import { AppDetail, UpdateOriginsRequest, readApiErrorMessage } from './app-registry-api';
import { ORIGINS_HINT } from './field-hints';

/**
 * The control of issue #296 (D52): `PATCH /api/apps/{id}` for a
 * changed name, and `PUT /api/apps/{id}/origins` for the origin list.
 * Each row of the list gets its own instance. The trigger button
 * reads "Edit origins" (the correction of the brief review): it opens
 * the one form below, with the name and the origin list of the app.
 *
 * The connection string field of D13 is gone (issue #296): the keys
 * panel of the add form and the "New secret key" action cover the
 * hosted path; this form edits the name and the origins of any app.
 *
 * The focus moves back to the trigger button after a cancel and after
 * a successful save, so a keyboard user stays near the row (the
 * pattern of `DeleteAppDialog.onDialogClose`).
 *
 * Design decision D48 (issue #277) ends the typed route pattern list of
 * issue #226: the form shows the published list read-only. It reads
 * `GET /api/apps/{id}` on open, so it shows the app's own published list
 * and the "Published by the app on" line.
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

  protected readonly editing = signal(false);
  protected readonly name = signal('');
  protected readonly origins = signal('');
  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  /** The published route list, split into one entry per line, or an empty array before the first publish. */
  protected readonly pathPatterns = signal<string[]>([]);

  private readonly pathPatternsPublishedAt = signal<string | null>(null);
  private readonly kitVersion = signal<string | null>(null);

  /**
   * The line under the list, for the read-only block. A cached list
   * with no `publishedAt` is the third state of the block (MAJOR 2 of
   * the correction round of pull request #286): a list that an
   * operator typed before this change, with no publish record yet
   * (`docs/run-guide.md`, `docs/integration-ktor.md`). That state gets
   * its own sentence, never an empty line.
   */
  protected readonly publishedLine = computed(() => {
    const publishedAt = this.pathPatternsPublishedAt();
    const kitVersion = this.kitVersion();
    if (publishedAt === null) {
      return 'Typed before the app published a list.';
    }
    const time = formatLocalDateTime(publishedAt);
    return kitVersion === null
      ? `Published by the app on ${time}`
      : `Published by the app on ${time} (kit ${kitVersion})`;
  });

  /**
   * True after a failed `GET /api/apps/{id}` (design decision D48, the
   * correction of the brief review of 2026-09-28). The block shows a
   * message; Save stays enabled, because the save never carries a
   * `pathPatterns` field any more, so a failed read of this read-only
   * block risks no wrong write.
   */
  protected readonly pathPatternsReadFailed = signal(false);

  protected get nameId(): string {
    return `edit-app-name-${this.app().appId}`;
  }

  protected get originsId(): string {
    return `edit-app-origins-${this.app().appId}`;
  }

  protected get originsHintId(): string {
    return `edit-app-origins-hint-${this.app().appId}`;
  }

  protected get errorId(): string {
    return `edit-app-error-${this.app().appId}`;
  }

  /** Opens the form with the current name, the current origin list, and the published route list. */
  protected open(): void {
    this.name.set(this.app().name);
    this.origins.set('');
    this.pathPatterns.set([]);
    this.pathPatternsPublishedAt.set(null);
    this.kitVersion.set(null);
    this.pathPatternsReadFailed.set(false);
    this.errorMessage.set(null);
    this.editing.set(true);
    afterNextRender(() => this.nameField()?.nativeElement.focus(), { injector: this.injector });
    this.http.get<AppDetail>(`/api/apps/${this.app().appId}`).subscribe({
      next: (detail) => {
        this.origins.set(detail.origins.join('\n'));
        this.pathPatterns.set(detail.pathPatterns === null ? [] : detail.pathPatterns.split('\n'));
        this.pathPatternsPublishedAt.set(detail.pathPatternsPublishedAt);
        this.kitVersion.set(detail.kitVersion);
      },
      // Design decision D48 (the correction of the brief review of
      // 2026-09-28): a failed read must not stay silent. It shows a
      // message next to the read-only block. Save stays enabled: the
      // save never carries a pathPatterns field, so this failure risks
      // no wrong write.
      error: () => {
        this.pathPatternsReadFailed.set(true);
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

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    const newName = this.name().trim();
    if (newName !== this.app().name) {
      this.http.patch<void>(`/api/apps/${this.app().appId}`, { name: newName }).subscribe({
        next: () => this.saveOrigins(),
        error: (error: unknown) => this.onFailed(error),
      });
    } else {
      this.saveOrigins();
    }
  }

  // The API rejects an empty origin list (one to 20 origins, M11). An
  // app of the legacy connection-string path (D32) starts with an
  // empty list and an empty textarea here; a save with no typed
  // origin then skips this request, so a plain rename of such an app
  // still works, the same as before this story.
  private saveOrigins(): void {
    const lines = linesOf(this.origins());
    if (lines.length === 0) {
      this.onUpdated();
      return;
    }
    const request: UpdateOriginsRequest = { origins: lines };
    this.http.put<AppDetail>(`/api/apps/${this.app().appId}/origins`, request).subscribe({
      next: () => this.onUpdated(),
      error: (error: unknown) => this.onFailed(error),
    });
  }

  private onUpdated(): void {
    this.pending.set(false);
    this.editing.set(false);
    this.announcer.announce('App updated');
    this.updated.emit();
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
