/**
 * One value of the app status field (D8 of the design).
 * `OK`, `UNREACHABLE`, `UNAUTHORIZED`, `OVERPRIVILEGED`, `INVALID_DATA`, and
 * `ERROR` come from the monitor. `NEVER_POLLED` is a derived value: the
 * monitor gives it for an app with no poll cycle yet.
 */
export type AppStatus =
  | 'OK'
  | 'UNREACHABLE'
  | 'UNAUTHORIZED'
  | 'OVERPRIVILEGED'
  | 'INVALID_DATA'
  | 'ERROR'
  | 'NEVER_POLLED';

/**
 * One gap of design decision D16. `from` and `to` are UTC ISO 8601
 * strings with milliseconds, never null.
 */
export interface Gap {
  readonly from: string;
  readonly to: string;
}

/**
 * One row of `GET /api/apps` (D13, level 1 of the design).
 * Each time field is a UTC ISO 8601 string with milliseconds, for example
 * `2026-09-21T14:23:07.512Z`, or null when the app holds no such time yet.
 * `gaps` (D16) is an empty array for an app with no gap.
 * A change of the API needs one edit of this file, and no other file.
 */
export interface AppRow {
  readonly appId: number;
  readonly name: string;
  readonly clicks: number;
  readonly uniqueUsers: number;
  readonly uniqueSessions: number;
  readonly status: AppStatus;
  readonly lastSuccessAt: string | null;
  readonly lastError: string | null;
  readonly nextPollAt: string | null;
  readonly gaps: Gap[];
  // Issue #296, M7: optional, so a fixture of a view that never shows
  // them (the level 1 dashboard of apps.ts) needs no update of its
  // own. The manage view reads them with a fallback: `?? null` and
  // `?? []`. null or [] for an app of the legacy connection-string
  // path (D32) that never registered keys.
  readonly appKey?: string | null;
  readonly origins?: readonly string[];
  readonly databaseName?: string | null;
  // Issue #299, M10: the count of the typed route pattern list, for
  // the "Patterns" column of the manage list. Optional, so a fixture
  // of a view that never shows it needs no update of its own.
  readonly routePatternCount?: number;
}
