import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Announcer } from '../announcer';
import { NETWORK_FAILURE_MESSAGE, readApiErrorMessage } from '../manage/app-registry-api';

/** The body of `POST /api/signin` (D52). */
interface SignInRequest {
  readonly email: string;
}

/** The body of `POST /api/signin/confirm` (D52). */
interface ConfirmRequest {
  readonly token: string;
}

const CHECK_YOUR_MAIL_MESSAGE = 'Check your mail.';

/**
 * The sign-in page of issue #301 (D52). With no `token` query
 * parameter, it shows the email form and the two states of a submit
 * (`requestSent`, an error). With a `token` query parameter, it shows
 * the one confirm button, never a state change from the link alone: a
 * mail scanner that fetches the link changes nothing (M5).
 *
 * After a good confirm, `history.replaceState` removes the token from
 * the address bar and the browser history, so the one-use token stays
 * out of both (the maintainer's correction, m9).
 */
@Component({
  selector: 'app-signin',
  imports: [RouterLink],
  templateUrl: './signin.html',
  styleUrl: './signin.css',
})
export class Signin {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly announcer = inject(Announcer);

  /** The token of the query string, or null for the plain request form. */
  protected readonly token = signal<string | null>(this.route.snapshot.queryParamMap.get('token'));

  protected readonly email = signal('');
  protected readonly pending = signal(false);
  protected readonly requestSent = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected onEmailInput(event: Event): void {
    this.email.set((event.target as HTMLInputElement).value);
  }

  protected onRequestSubmit(event: Event): void {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    const body: SignInRequest = { email: this.email() };
    this.http.post('/api/signin', body).subscribe({
      next: () => this.onRequestSent(),
      error: (error: unknown) => this.onRequestFailed(error),
    });
  }

  protected onConfirmClick(): void {
    const token = this.token();
    if (token === null || this.pending()) {
      return;
    }
    this.pending.set(true);
    this.errorMessage.set(null);
    const body: ConfirmRequest = { token };
    this.http.post('/api/signin/confirm', body).subscribe({
      next: () => this.onConfirmed(),
      error: (error: unknown) => this.onConfirmFailed(error),
    });
  }

  private onRequestSent(): void {
    this.pending.set(false);
    this.requestSent.set(true);
    this.announcer.announce(CHECK_YOUR_MAIL_MESSAGE);
  }

  private onRequestFailed(error: unknown): void {
    this.pending.set(false);
    const message = readApiErrorMessage(error) ?? NETWORK_FAILURE_MESSAGE;
    this.errorMessage.set(message);
    this.announcer.announce(message);
  }

  private onConfirmed(): void {
    // The maintainer's correction, m9: the token leaves the address
    // bar and the history once the confirm succeeds.
    history.replaceState(null, '', '/signin');
    void this.router.navigate(['/apps']);
  }

  private onConfirmFailed(error: unknown): void {
    this.pending.set(false);
    const message = readApiErrorMessage(error) ?? NETWORK_FAILURE_MESSAGE;
    this.errorMessage.set(message);
    this.announcer.announce(message);
  }
}
