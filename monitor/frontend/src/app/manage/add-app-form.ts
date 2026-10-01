import { HttpClient } from '@angular/common/http';
import { Component, WritableSignal, inject, output, signal } from '@angular/core';

import { Announcer } from '../announcer';
import { Me } from '../account/me';
import { AppKeys, CreateAppWithOriginsRequest, readApiErrorMessage } from './app-registry-api';
import { ORIGINS_HINT } from './field-hints';

/**
 * The form of issue #296 (D51, D52, D57): `POST /api/apps` with a name
 * and an origin list, in place of the former connection string (D32,
 * the local developer path stays in the API only). One origin per
 * line, in the textarea labelled "Origins (one per line)".
 *
 * After a successful save, the keys panel shows the app key and the
 * secret key with a copy button each, and the tracker script tag. The
 * secret key shows one time: the API never returns it again.
 */
@Component({
  selector: 'app-add-app-form',
  templateUrl: './add-app-form.html',
  styleUrl: './add-app-form.css',
})
export class AddAppForm {
  private readonly http = inject(HttpClient);
  private readonly announcer = inject(Announcer);
  private readonly me = inject(Me);

  /** Emits the created app's keys after a successful save. */
  readonly added = output<AppKeys>();

  protected readonly originsHint = ORIGINS_HINT;

  protected readonly name = signal('');
  protected readonly origins = signal('');

  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  /** The keys of the app this form just created, or null before a save. */
  protected readonly keys = signal<AppKeys | null>(null);

  protected readonly copyAppKeyDone = signal(false);
  protected readonly copySecretKeyDone = signal(false);

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
    const request: CreateAppWithOriginsRequest = {
      name: this.name(),
      origins: linesOf(this.origins()),
    };
    this.pending.set(true);
    this.errorMessage.set(null);
    this.http.post<AppKeys>('/api/apps', request).subscribe({
      next: (keys) => this.onCreated(keys),
      error: (error: unknown) => this.onFailed(error),
    });
  }

  /** The hash of the served tracker bundle (issue #298), for the keys panel template. */
  protected readonly trackerHash = this.me.trackerHash;

  /**
   * The hashed tracker script tag of the keys panel (issue #296, #298).
   * A blank publicUrl falls back to the page's own origin. The caller
   * reads this only when [trackerHash] holds a value.
   */
  protected scriptTag(appKey: string, trackerHash: string): string {
    const base = this.me.publicUrl() || location.origin;
    return `<script src="${base}/tracker.js?v=${trackerHash}" data-app-key="${appKey}"></script>`;
  }

  protected copyAppKey(): void {
    this.copy(this.keys()?.appKey, this.copyAppKeyDone);
  }

  protected copySecretKey(): void {
    this.copy(this.keys()?.secretKey, this.copySecretKeyDone);
  }

  // The Clipboard API, with no fallback call here: the field itself is
  // a readonly, selectable text input, so a browser with no Clipboard
  // API (or a denied permission) still lets the owner select and copy
  // it by hand (the fallback the brief names).
  private copy(value: string | undefined, done: WritableSignal<boolean>): void {
    if (!value || !navigator.clipboard) {
      return;
    }
    navigator.clipboard.writeText(value).then(
      () => {
        done.set(true);
        this.announcer.announce('Copied');
      },
      () => {
        // The input stays selectable; no further action here.
      },
    );
  }

  private onCreated(keys: AppKeys): void {
    this.pending.set(false);
    this.name.set('');
    this.origins.set('');
    this.copyAppKeyDone.set(false);
    this.copySecretKeyDone.set(false);
    this.keys.set(keys);
    this.announcer.announce('App added');
    this.added.emit(keys);
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

/** Splits a textarea value into one origin per non-blank line (issue #296). */
function linesOf(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}
