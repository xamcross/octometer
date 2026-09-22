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
import { UpdateAppRequest, readApiErrorMessage } from './app-registry-api';

/**
 * The control of step 3: `PATCH /api/apps/{id}` with the name and the
 * connection string of D13. Each row of the list gets its own instance.
 *
 * The "Edit" button opens the form. The name field starts with the current
 * name, and the focus moves to it. The connection string field starts
 * empty, because the API never returns the stored value (D11, D15): a
 * blank field then means "keep the old connection string", and the
 * request omits the field.
 *
 * The focus moves back to the "Edit" button after a cancel and after a
 * successful save, so a keyboard user stays near the row (the pattern of
 * `DeleteAppDialog.onDialogClose`).
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

  protected readonly editing = signal(false);
  protected readonly name = signal('');
  protected readonly connectionString = signal('');
  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected get nameId(): string {
    return `edit-app-name-${this.app().appId}`;
  }

  protected get connectionStringId(): string {
    return `edit-app-connection-string-${this.app().appId}`;
  }

  protected get errorId(): string {
    return `edit-app-error-${this.app().appId}`;
  }

  /** Opens the form with the current name, and an empty connection string field. */
  protected open(): void {
    this.name.set(this.app().name);
    this.connectionString.set('');
    this.errorMessage.set(null);
    this.editing.set(true);
    afterNextRender(() => this.nameField()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Closes the form. It discards each typed value, and moves the focus back to "Edit". */
  protected cancel(): void {
    this.editing.set(false);
    this.connectionString.set('');
    afterNextRender(() => this.editToggle()?.nativeElement.focus(), { injector: this.injector });
  }

  protected onNameInput(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected onConnectionStringInput(event: Event): void {
    this.connectionString.set((event.target as HTMLInputElement).value);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    const request: UpdateAppRequest = {
      name: this.name(),
      ...(this.connectionString() ? { connectionString: this.connectionString() } : {}),
    };
    this.pending.set(true);
    this.errorMessage.set(null);
    this.http.patch<void>(`/api/apps/${this.app().appId}`, request).subscribe({
      next: () => this.onUpdated(),
      error: (error: unknown) => this.onFailed(error),
    });
  }

  private onUpdated(): void {
    this.pending.set(false);
    this.editing.set(false);
    this.connectionString.set('');
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
