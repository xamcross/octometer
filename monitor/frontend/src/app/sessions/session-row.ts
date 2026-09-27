/**
 * One row of `GET /api/apps/{appId}/sessions` (D13, D44 of the design).
 * The API writes `firstPath: "(unknown)"` for a session with no start
 * row, and `source: "(direct)"` for a start row with no
 * `referrerHost` (D44). `userId` is the user id of the earliest
 * sign-in of the session, or `null`. A change of the API needs one
 * edit of this file, and no other file.
 */
export interface SessionRow {
  readonly sessionId: string;
  readonly firstPath: string;
  readonly source: string;
  readonly startTime: string;
  readonly clicks: number;
  readonly userId: string | null;
}

/** The page shape of `GET /api/apps/{appId}/sessions` (D13). */
export interface SessionsResponse {
  readonly page: number;
  readonly pageCount: number;
  readonly rows: readonly SessionRow[];
}
