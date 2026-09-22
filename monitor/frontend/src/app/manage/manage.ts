import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, inject, signal } from '@angular/core';
import type { Subscription } from 'rxjs';

import { Announcer } from '../announcer';
import type { AppRow, AppStatus } from '../apps/app-row';
import { STATUS_ICON, STATUS_LABEL } from '../status-format';
import { AddAppForm } from './add-app-form';
import { readApiErrorMessage } from './app-registry-api';
import { DeleteAppDialog } from './delete-app-dialog';
import { EditAppForm } from './edit-app-form';

/**
 * The route "/manage" (D28, D32). The owner adds an app, replaces the
 * connection string of an app, and deletes an app here.
 *
 * The page reads `GET /api/apps` one time on load, and again after each
 * successful change: an add, an edit, or a delete. It shows no
 * `RefreshBar` and it runs no poll timer of its own (#92): the list changes
 * only because of an action of the owner on this page, not because of a
 * background poll cycle.
 *
 * The connection string never appears here: `AddAppForm` and
 * `EditAppForm` hold the write-only field, and the API never returns a
 * stored value (D11, D15).
 */
@Component({
  selector: 'app-manage',
  imports: [AddAppForm, EditAppForm, DeleteAppDialog],
  styleUrl: './manage.css',
  templateUrl: './manage.html',
})
export class Manage {
  private readonly http = inject(HttpClient);
  private readonly hostElement: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly announcer = inject(Announcer);

  /** The app list. Undefined before the first answer, good or bad. */
  protected readonly apps = signal<AppRow[] | undefined>(undefined);

  /** The text of a failed list request. Null while the list holds a good answer. */
  protected readonly listError = signal<string | null>(null);

  /** The request in flight. A new call cancels it, so a late answer cannot overwrite a fresh one. */
  private reloadSubscription: Subscription | undefined;

  constructor() {
    this.reload();
  }

  /** Reads the app list again. A failed request shows a text and a "Try again" button. */
  protected reload(): void {
    this.reloadSubscription?.unsubscribe();
    this.reloadSubscription = this.http.get<AppRow[]>('/api/apps').subscribe({
      next: (rows) => {
        this.apps.set(rows);
        this.listError.set(null);
      },
      error: (error: unknown) => this.onListFailed(error),
    });
  }

  private onListFailed(error: unknown): void {
    const message = readApiErrorMessage(error) ?? 'Octometer cannot read the app list. Try again.';
    this.listError.set(message);
    this.announcer.announce(message);
  }

  protected statusLabel(status: AppStatus): string {
    return STATUS_LABEL[status];
  }

  protected statusIcon(status: AppStatus): string {
    return STATUS_ICON[status];
  }

  /** True for each status other than `OK` and `NEVER_POLLED` (D30). */
  protected isFailed(status: AppStatus): boolean {
    return status !== 'OK' && status !== 'NEVER_POLLED';
  }

  protected onAppAdded(): void {
    this.reload();
  }

  protected onAppUpdated(): void {
    this.reload();
  }

  /** Step 5: after a delete, the list reloads and the focus moves to the `<h1>`. */
  protected onAppDeleted(): void {
    this.reload();
    this.focusHeading();
  }

  private focusHeading(): void {
    this.hostElement.nativeElement.querySelector<HTMLElement>('h1[tabindex="-1"]')?.focus();
  }
}
