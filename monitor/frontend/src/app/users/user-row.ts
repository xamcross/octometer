/**
 * One row of `GET /api/apps/{appId}/users` (D13, level 2 of the design).
 * `userId` is null for the anonymous row: the group of clicks with no
 * user id. A change of the API needs one edit of this file, and no
 * other file.
 */
export interface UserRow {
  readonly userId: string | null;
  readonly clicks: number;
  readonly sessions: number;
  readonly uniqueElements: number;
}

/** The page shape of `GET /api/apps/{appId}/users` (D13). */
export interface UserTotalsResponse {
  readonly page: number;
  readonly pageCount: number;
  readonly rows: readonly UserRow[];
}
