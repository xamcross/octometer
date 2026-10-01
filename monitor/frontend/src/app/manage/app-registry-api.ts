import { HttpErrorResponse } from '@angular/common/http';

/**
 * The body of `POST /api/apps` with a connection string (D32). This
 * form stays for the local developer run only (issue #296); the manage
 * view sends `origins` through [CreateAppWithOriginsRequest] instead.
 */
export interface CreateAppRequest {
  readonly name: string;
  readonly connectionString: string;
}

/**
 * The body of `POST /api/apps` with an origin list (issue #296, D51,
 * D52, D57), in place of a connection string. The manage view sends
 * this form.
 */
export interface CreateAppWithOriginsRequest {
  readonly name: string;
  readonly origins: readonly string[];
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
 * The answer of a successful `POST /api/apps` with a connection string
 * (D13, D11). It never holds a connection string: the API does not
 * return one.
 */
export interface AppSummary {
  readonly appId: number;
  readonly name: string;
  readonly database: string;
  readonly collection: string;
}

/**
 * The answer of `GET /api/apps/{id}` (design decision D48, issue #277;
 * formerly issue #226). It never holds a connection string, or the
 * secret key, or its hash (issue #296, M7). `pathPatterns` is the
 * route list that the app published, one pattern per line, or `null`
 * before the app's first published list. `pathPatternsPublishedAt` is
 * the UTC ISO 8601 text of that publish time, or `null` with
 * `pathPatterns`. `kitVersion` is the kit version of that same
 * publish, or `null` with `pathPatterns`. `appKey` and `databaseName`
 * are `null` for an app of the legacy connection-string path. `origins`
 * is `[]` for such an app.
 */
export interface AppDetail {
  readonly appId: number;
  readonly name: string;
  readonly pathPatterns: string | null;
  readonly pathPatternsPublishedAt: string | null;
  readonly kitVersion: string | null;
  readonly appKey: string | null;
  readonly origins: readonly string[];
  readonly databaseName: string | null;
}

/**
 * The answer of a successful `POST /api/apps` with an origin list
 * (issue #296, D51). `appKey` and `secretKey` show here one time: the
 * API never returns the secret key again, not even from `GET
 * /api/apps` or `GET /api/apps/{id}`.
 */
export interface AppKeys {
  readonly appId: number;
  readonly name: string;
  readonly origins: readonly string[];
  readonly databaseName: string;
  readonly appKey: string;
  readonly secretKey: string;
}

/** The body of `PUT /api/apps/{id}/origins` (issue #296). */
export interface UpdateOriginsRequest {
  readonly origins: readonly string[];
}

/** The one-time answer of `POST /api/apps/{id}/secret` (issue #296, D51). */
export interface SecretRotation {
  readonly appId: number;
  readonly secretKey: string;
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
