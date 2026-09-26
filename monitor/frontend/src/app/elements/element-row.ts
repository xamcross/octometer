/**
 * One row of `GET /api/apps/{appId}/elements` (D13, level 3 of the
 * design). `lastInteractionAt` is a UTC ISO 8601 string with
 * milliseconds. A change of the API needs one edit of this file, and no
 * other file.
 */
export interface ElementRow {
  readonly element: string;
  readonly clicks: number;
  readonly sessions: number;
  readonly lastInteractionAt: string;
}

/** The one response body of `GET /api/apps/{appId}/elements` (D13). The route gives no page. */
export interface ElementsResponse {
  readonly rows: readonly ElementRow[];
}
