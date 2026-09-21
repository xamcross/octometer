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
 * One row of `GET /api/apps` (D13, level 1 of the design).
 * Each time field is a UTC ISO 8601 string with milliseconds, for example
 * `2026-09-21T14:23:07.512Z`, or null when the app holds no such time yet.
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
}
