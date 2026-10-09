import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { Me } from '../account/me';
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
  private readonly me = inject(Me);

  /**
   * The token of the query string, or null for the plain request form.
   * MAJOR 1 of the review of pull request #313: Angular reuses this one
   * component instance for the whole `/signin` route, so a read of
   * `route.snapshot` at construction never changes again. This reads
   * the query-parameter stream instead, so a link such as "Request a
   * new link" (which changes the URL to `/signin`, with no navigation
   * away from the route) brings the plain form back.
   */
  protected readonly token = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('token'))),
    { initialValue: this.route.snapshot.queryParamMap.get('token') },
  );

  protected readonly email = signal('');
  protected readonly pending = signal(false);
  protected readonly requestSent = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  constructor() {
    // MAJOR 1 of the review of pull request #313: a token that turns
    // null (the visitor left the confirm page for the plain form)
    // clears a stale confirm error, so the form shows with no leftover
    // text of the link it replaces.
    effect(() => {
      if (this.token() === null) {
        this.errorMessage.set(null);
      }
    });

    // Issue #347, MAJOR 1 of the review of pull request #350: the root
    // app already sends one `GET /api/me` (`app.ts`), through `Me`.
    // This effect reads its ready state in place of a second request.
    // The plain route (no `token` query parameter) navigates away once
    // a session answer arrives; the confirm route stays out of it.
    effect(() => {
      if (this.token() === null && this.me.ready()) {
        void this.router.navigate(['/apps']);
      }
    });
  }

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
