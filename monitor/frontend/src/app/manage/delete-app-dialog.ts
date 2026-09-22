import { HttpClient, HttpHeaders } from '@angular/common/http';
import {
  Component,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { numberFormat } from '../status-format';
import { readApiErrorMessage } from './app-registry-api';

const DELETE_HEADERS = new HttpHeaders({ 'Content-Type': 'application/json' });

/**
 * The delete of steps 4 and 5: a native `<dialog>`, not a browser
 * `confirm()`. The owner must type the exact app name before the "Delete
 * this app" button turns active. The dialog opens with the default focus on
 * "Cancel" (a destructive action never starts with the focus on itself).
 *
 * The DELETE request holds `Content-Type: application/json` and a body of
 * `{}`, so the header is present although the route needs no field.
 *
 * A close of the dialog, from Cancel, from Escape, or from a successful
 * delete, moves the focus back to the trigger button. The parent moves the
 * focus to the `<h1>` after a successful delete (step 5), once it removes
 * the row and this component with it.
 */
@Component({
  selector: 'app-delete-app-dialog',
  templateUrl: './delete-app-dialog.html',
  styleUrl: './delete-app-dialog.css',
})
export class DeleteAppDialog {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);

  /** The app the dialog can delete. */
  readonly app = input.required<AppRow>();

  /** Emits after a successful delete. */
  readonly deleted = output<void>();

  private readonly dialogEl = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly triggerButton =
    viewChild.required<ElementRef<HTMLButtonElement>>('triggerButton');

  protected readonly typedName = signal('');
  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly formattedClicks = computed(() => numberFormat.format(this.app().clicks));
  protected readonly canConfirm = computed(() => this.typedName() === this.app().name);

  protected get confirmId(): string {
    return `delete-app-confirm-${this.app().appId}`;
  }

  protected get errorId(): string {
    return `delete-app-error-${this.app().appId}`;
  }

  /** Opens the dialog, and puts the default focus on "Cancel". */
  protected open(): void {
    this.typedName.set('');
    this.errorMessage.set(null);
    this.dialogEl().nativeElement.showModal();
    this.cancelButton().nativeElement.focus();
  }

  protected cancel(): void {
    this.dialogEl().nativeElement.close();
  }

  /** Restores the focus to the trigger button after any close of the dialog. */
  protected onDialogClose(): void {
    this.triggerButton().nativeElement.focus();
  }

  protected onTypedNameInput(event: Event): void {
    this.typedName.set((event.target as HTMLInputElement).value);
  }

  protected confirmDelete(): void {
    if (!this.canConfirm() || this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    this.http
      .delete<void>(`/api/apps/${this.app().appId}`, { headers: DELETE_HEADERS, body: {} })
      .subscribe({
        next: () => this.onDeleted(),
        error: (error: unknown) => this.onFailed(error),
      });
  }

  private onDeleted(): void {
    this.pending.set(false);
    this.dialogEl().nativeElement.close();
    this.announcer.announce('App deleted');
    this.deleted.emit();
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
