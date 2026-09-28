import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

/** The two sign-in calls that carry no session, and so must never redirect on a 401 (D52). */
const EXCLUDED_PATHS = ['/api/signin', '/api/signin/confirm'];

/**
 * Sends the visitor to `/signin` on a 401 from `/api/` (issue #301,
 * D52), except the two sign-in calls themselves: a 401 there is a
 * plain wrong-token answer of `/api/signin/confirm`, not a missing
 * session, and the sign-in page already shows that answer itself.
 *
 * It also does nothing while the browser already sits on `/signin`:
 * the health poll, and `Me.load()`, each send a call of their own
 * before the visitor ever confirms a link, and each one gets the same
 * 401 before sign-in. A plain `router.navigate(['/signin'])` there
 * would drop the `token` query parameter of a real link, and replace
 * the confirm page with the plain request form.
 *
 * The check reads `window.location.pathname`, not `router.url`. The
 * first `/api/me` call fires from the app root at start-up, at the
 * same time as the router's own first navigation. `router.url` can
 * still hold its old value at that moment, so it does not catch the
 * case. The browser's own address bar is always current.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      const isUnauthorizedApiCall =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        request.url.startsWith('/api/') &&
        !EXCLUDED_PATHS.includes(request.url);
      const alreadyOnSignIn = window.location.pathname === '/signin';
      if (isUnauthorizedApiCall && !alreadyOnSignIn) {
        void router.navigate(['/signin']);
      }
      return throwError(() => error);
    }),
  );
};
