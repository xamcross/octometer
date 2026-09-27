/**
 * One row of `GET /api/apps/{appId}/first-pages` (D13, D44 of the design).
 * `path` is `null` for a `NULL` path (issue #213); the view renders the
 * text `(unknown)` for that `null` value. A change of the API needs one
 * edit of this file, and no other file.
 */
export interface FirstPageRow {
  readonly path: string | null;
  readonly sessions: number;
}

/** The page shape of `GET /api/apps/{appId}/first-pages` (D13). */
export interface FirstPagesResponse {
  readonly page: number;
  readonly pageCount: number;
  readonly rows: readonly FirstPageRow[];
}
