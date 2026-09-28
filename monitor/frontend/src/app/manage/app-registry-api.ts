import { HttpErrorResponse } from '@angular/common/http';

/**
 * The body of `POST /api/apps` (D13). Design decision D48 (issue #277)
 * ends the typed route pattern list of issue #226: the app publishes
 * its own list now, so this body carries no `pathPatterns` field. The
 * API gives 400 with a fixed reason for a body with that field.
 *
 * Issue #272: the registry derives the database name from the path of
 * `connectionString`. This form gives the API no `database` field any
 * more.
 */
export interface CreateAppRequest {
  readonly name: string;
  readonly connectionString: string;
}

/**
 * The body of `PATCH /api/apps/{id}` (D13). The caller gives a name, a
 * connection string, or both. An absent `name` or `connectionString`
 * means "keep the old value". Design decision D48 (issue #277): this
 * body carries no `pathPatterns` field any more (see
 * [CreateAppRequest]).
 */
export interface UpdateAppRequest {
  readonly name?: string;
  readonly connectionString?: string;
}

/**
 * The answer of a successful `POST /api/apps` (D13, D11).
 * It never holds a connection string: the API does not return one.
 */
export interface AppSummary {
  readonly appId: number;
  readonly name: string;
  readonly database: string;
  readonly collection: string;
}

/**
 * The answer of `GET /api/apps/{id}` (design decision D48, issue #277;
 * formerly issue #226). It never holds a connection string.
 * `pathPatterns` is the route list that the app published, one pattern
 * per line, or `null` before the app's first published list.
 * `pathPatternsPublishedAt` is the UTC ISO 8601 text of that publish
 * time, or `null` with `pathPatterns`. `kitVersion` is the kit version
 * of that same publish, or `null` with `pathPatterns`.
 */
export interface AppDetail {
  readonly appId: number;
  readonly name: string;
  readonly pathPatterns: string | null;
  readonly pathPatternsPublishedAt: string | null;
  readonly kitVersion: string | null;
}

/**
 * The text for a network failure (an HTTP status of 0). The monitor sent no
 * answer at all, so the API gives no fixed sentence for this case.
 */
export const NETWORK_FAILURE_MESSAGE = 'The monitor did not answer. Try again.';

/**
 * Reads the fixed error sentence of the registry API from a failed request
 * (`Errors.kt`: `{"error": "..."}`, sent for a 400, a 404, a 409, and a
 * 503). Returns `NETWORK_FAILURE_MESSAGE` for a network failure (a status
 * of 0): the shell banner can miss this case (#164), so this page shows its
 * own text. Returns null when the answer carries neither shape.
 */
export function readApiErrorMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 0) {
    return NETWORK_FAILURE_MESSAGE;
  }
  const body: unknown = error.error;
  if (
    body !== null &&
    typeof body === 'object' &&
    typeof (body as { error?: unknown }).error === 'string'
  ) {
    return (body as { error: string }).error;
  }
  return null;
}
