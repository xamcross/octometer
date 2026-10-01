import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';

import { Announcer } from '../announcer';
import type { AppRow } from '../apps/app-row';
import { SecretRotation, readApiErrorMessage } from './app-registry-api';

/**
 * The "New secret key" action of issue #296 (D51), the dialog pattern
 * of `DeleteAppDialog`: a native `<dialog>`, not a browser `confirm()`.
 * The confirm step asks a yes/no question, with no typed confirmation
 * (the key rotation reaches no event, unlike a delete). The dialog
 * opens with the default focus on "Cancel".
 *
 * `POST /api/apps/{id}/secret` answers the new secret key one time.
 * This dialog then shows it, with a copy button, in place of the
 * confirm question, until the owner clicks "Done".
 *
 * A close of the dialog, from Cancel or from Escape, moves the focus
 * back to the trigger button (the same queued-task note as
 * `DeleteAppDialog`).
 */
@Component({
  selector: 'app-new-secret-key-dialog',
  templateUrl: './new-secret-key-dialog.html',
  styleUrl: './new-secret-key-dialog.css',
})
export class NewSecretKeyDialog {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);

  /** The app whose secret key this dialog can rotate. */
  readonly app = input.required<AppRow>();

  private readonly dialogEl = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly cancelButton = viewChild.required<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly triggerButton =
    viewChild.required<ElementRef<HTMLButtonElement>>('triggerButton');

  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly newSecretKey = signal<string | null>(null);
  protected readonly copyDone = signal(false);

  /** True while `onDialogClose` must skip the trigger-focus step (the "Done" button already moved it). */
  private restoreFocusOnClose = true;

  protected get headingId(): string {
    return `new-secret-key-heading-${this.app().appId}`;
  }

  protected get errorId(): string {
    return `new-secret-key-error-${this.app().appId}`;
  }

  /** Opens the dialog at the confirm question, with the default focus on "Cancel". */
  protected open(): void {
    this.errorMessage.set(null);
    this.newSecretKey.set(null);
    this.copyDone.set(false);
    this.dialogEl().nativeElement.showModal();
    this.cancelButton().nativeElement.focus();
  }

  protected cancel(): void {
    this.dialogEl().nativeElement.close();
  }

  protected onDialogClose(): void {
    if (!this.restoreFocusOnClose) {
      this.restoreFocusOnClose = true;
      return;
    }
    this.triggerButton().nativeElement.focus();
  }

  protected confirmRotate(): void {
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    this.http.post<SecretRotation>(`/api/apps/${this.app().appId}/secret`, {}).subscribe({
      next: (rotation) => this.onRotated(rotation),
      error: (error: unknown) => this.onFailed(error),
    });
  }

  protected copySecretKey(): void {
    const value = this.newSecretKey();
    if (!value || !navigator.clipboard) {
      return;
    }
    navigator.clipboard.writeText(value).then(
      () => {
        this.copyDone.set(true);
        this.announcer.announce('Copied');
      },
      () => {
        // The input stays selectable; no further action here.
      },
    );
  }

  /** Closes the dialog after the one-time view of the new secret key, and moves the focus to the trigger. */
  protected done(): void {
    this.restoreFocusOnClose = false;
    this.dialogEl().nativeElement.close();
    this.triggerButton().nativeElement.focus();
  }

  private onRotated(rotation: SecretRotation): void {
    this.pending.set(false);
    this.newSecretKey.set(rotation.secretKey);
    this.announcer.announce('New secret key created');
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
