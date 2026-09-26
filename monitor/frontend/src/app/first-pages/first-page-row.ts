/**
 * One row of `GET /api/apps/{appId}/first-pages` (D13, D44 of the design).
 * The API writes the text `(unknown)` for a `NULL` path (D44). Issue #213
 * later changes this field to `path: string | null`. A change of the API
 * needs one edit of this file, and no other file.
 */
export interface FirstPageRow {
  readonly path: string;
  readonly sessions: number;
}

/** The page shape of `GET /api/apps/{appId}/first-pages` (D13). */
export interface FirstPagesResponse {
  readonly page: number;
  readonly pageCount: number;
  readonly rows: readonly FirstPageRow[];
}
