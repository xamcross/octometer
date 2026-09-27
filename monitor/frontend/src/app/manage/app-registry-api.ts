import { HttpErrorResponse } from '@angular/common/http';

/**
 * The body of `POST /api/apps` (D13). `pathPatterns` is the route
 * pattern list of issue #226, in the form of `OCTOMETER_PATH_PATTERNS`:
 * patterns with whitespace between them. An absent or a blank text
 * means the app gives no list.
 */
export interface CreateAppRequest {
  readonly name: string;
  readonly connectionString: string;
  readonly database: string;
  readonly collection: string;
  readonly pathPatterns?: string;
}

/**
 * The body of `PATCH /api/apps/{id}` (D13). The caller gives a name, a
 * connection string, a route pattern list, or a mix. An absent
 * `name` or `connectionString` means "keep the old value". For
 * `pathPatterns` (issue #226): an absent field keeps the stored list, a
 * blank text clears it, a non-blank text sets it.
 */
export interface UpdateAppRequest {
  readonly name?: string;
  readonly connectionString?: string;
  readonly pathPatterns?: string;
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
 * The answer of `GET /api/apps/{id}` (issue #226). It never holds a
 * connection string. `pathPatterns` is the stored, normalized text, or
 * `null` when the app gives no list.
 */
export interface AppDetail {
  readonly appId: number;
  readonly name: string;
  readonly pathPatterns: string | null;
}

/** The two collection names the registry accepts (`ALLOWED_COLLECTION_NAMES` of the backend). */
export const ALLOWED_COLLECTIONS = ['octometer_events', 'octometer_events_v2'] as const;

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
