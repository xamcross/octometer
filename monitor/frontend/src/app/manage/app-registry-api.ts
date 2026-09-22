import { HttpErrorResponse } from '@angular/common/http';

/** The body of `POST /api/apps` (D13). */
export interface CreateAppRequest {
  readonly name: string;
  readonly connectionString: string;
  readonly database: string;
  readonly collection: string;
}

/**
 * The body of `PATCH /api/apps/{id}` (D13). The caller gives a name, a
 * connection string, or both. An absent field means "keep the old value".
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

/** The two collection names the registry accepts (`ALLOWED_COLLECTION_NAMES` of the backend). */
export const ALLOWED_COLLECTIONS = ['octometer_events', 'octometer_events_v2'] as const;

/**
 * Reads the fixed error sentence of the registry API from a failed request
 * (`Errors.kt`: `{"error": "..."}`, sent for a 400, a 404, a 409, and a
 * 503). Returns null for a network failure, because that answer carries no
 * such body: the shell banner covers a network failure (D30), and this
 * component adds no second text for it.
 */
export function readApiErrorMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
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
