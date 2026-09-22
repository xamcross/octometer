import { HttpClient } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';

import { Announcer } from '../announcer';
import {
  ALLOWED_COLLECTIONS,
  AppSummary,
  CreateAppRequest,
  readApiErrorMessage,
} from './app-registry-api';

/**
 * The form of step 2: `POST /api/apps` with the fields of D13. The
 * connection string field is `type="password"` with `autocomplete="off"`
 * (D11, D15): the page never shows a stored connection string, and this
 * form never keeps the typed text after a successful save.
 *
 * A 400 or a 503 answer shows the fixed sentence of the API next to the
 * connection string field, through one `aria-describedby` id. A network
 * failure shows a general sentence in the same place (#164 records that the
 * shell banner can miss this case).
 */
@Component({
  selector: 'app-add-app-form',
  templateUrl: './add-app-form.html',
  styleUrl: './add-app-form.css',
})
export class AddAppForm {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);

  /** Emits the created app summary after a successful save. */
  readonly added = output<AppSummary>();

  protected readonly allowedCollections = ALLOWED_COLLECTIONS;

  protected readonly name = signal('');
  protected readonly connectionString = signal('');
  protected readonly database = signal('');
  protected readonly collection = signal<string>(ALLOWED_COLLECTIONS[0]);

  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected onNameInput(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected onConnectionStringInput(event: Event): void {
    this.connectionString.set((event.target as HTMLInputElement).value);
  }

  protected onDatabaseInput(event: Event): void {
    this.database.set((event.target as HTMLInputElement).value);
  }

  protected onCollectionChange(event: Event): void {
    this.collection.set((event.target as HTMLSelectElement).value);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    const request: CreateAppRequest = {
      name: this.name(),
      connectionString: this.connectionString(),
      database: this.database(),
      collection: this.collection(),
    };
    this.pending.set(true);
    this.errorMessage.set(null);
    this.http.post<AppSummary>('/api/apps', request).subscribe({
      next: (summary) => this.onCreated(summary),
      error: (error: unknown) => this.onFailed(error),
    });
  }

  private onCreated(summary: AppSummary): void {
    this.pending.set(false);
    this.name.set('');
    this.connectionString.set('');
    this.database.set('');
    this.collection.set(ALLOWED_COLLECTIONS[0]);
    this.announcer.announce('App added');
    this.added.emit(summary);
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
