import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { UpdateAppRequest, readApiErrorMessage } from './app-registry-api';

/**
 * The control of step 3: `PATCH /api/apps/{id}` with the name and the
 * connection string of D13. Each row of the list gets its own instance.
 *
 * The "Edit" button opens the form. The name field starts with the current
 * name. The connection string field starts empty, because the API never
 * returns the stored value (D11, D15): a blank field then means "keep the
 * old connection string", and the request omits the field.
 */
@Component({
  selector: 'app-edit-app-form',
  templateUrl: './edit-app-form.html',
  styleUrl: './edit-app-form.css',
})
export class EditAppForm {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);

  /** The app the control edits. */
  readonly app = input.required<AppRow>();

  /** Emits after a successful save. */
  readonly updated = output<void>();

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
  }

  /** Closes the form. It discards each typed value. */
  protected cancel(): void {
    this.editing.set(false);
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
  }

  private onFailed(error: unknown): void {
    this.pending.set(false);
    const message = readApiErrorMessage(error);
    this.errorMessage.set(message);
    if (message) {
      this.announcer.announce(message);
    }
  }
}
