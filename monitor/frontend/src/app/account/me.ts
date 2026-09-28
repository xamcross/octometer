import { HttpClient } from '@angular/common/http';
import { Injectable, Signal, inject, signal } from '@angular/core';

/** The answer of `GET /api/me` (D52, issue #301). */
interface MeResponse {
  readonly email: string;
}

/**
 * Holds the signed-in account (issue #301, D52). The root `App`
 * component reads [ready] to gate the start of the health poll (brief
 * review B4): the poll starts after one successful `GET /api/me` only.
 * A failed request (a 401 before sign-in) sets neither [email] nor
 * [ready]; the auth interceptor already sends the visitor to `/signin`
 * for that case, so this service raises no error of its own.
 */
@Injectable({ providedIn: 'root' })
export class Me {
  private readonly http = inject(HttpClient);
  private readonly emailState = signal<string | null>(null);
  private readonly readyState = signal(false);

  /** The email address of the signed-in account, or null before the first good answer. */
  readonly email: Signal<string | null> = this.emailState.asReadonly();

  /** True once `GET /api/me` gave one good answer. The health poll waits for this. */
  readonly ready: Signal<boolean> = this.readyState.asReadonly();

  /** Sends `GET /api/me` once. The root `App` component calls this at the start. */
  load(): void {
    this.http.get<MeResponse>('/api/me').subscribe({
      next: (response) => {
        this.emailState.set(response.email);
        this.readyState.set(true);
      },
      error: () => {
        // A 401 here is the normal state before sign-in. The auth
        // interceptor already sends the visitor to /signin.
      },
    });
  }

  /** Ends the session and clears the account state (D52). */
  signOut(): void {
    this.http.post('/api/signout', {}).subscribe({
      next: () => this.afterSignOut(),
      error: () => this.afterSignOut(),
    });
  }

  private afterSignOut(): void {
    this.emailState.set(null);
    this.readyState.set(false);
    // A full navigation, not the router: it clears every in-memory
    // store of the app, the same way a fresh sign-in should start.
    window.location.href = '/signin';
  }
}
