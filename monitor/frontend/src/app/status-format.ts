import type { AppStatus } from './apps/app-row';

/** The visible text of each status value (D33: an icon plus text, never colour alone). */
export const STATUS_LABEL: Record<AppStatus, string> = {
  OK: 'OK',
  NEVER_POLLED: 'Not polled yet',
  UNREACHABLE: 'Unreachable',
  UNAUTHORIZED: 'Unauthorized',
  OVERPRIVILEGED: 'Overprivileged',
  INVALID_DATA: 'Invalid data',
  ERROR: 'Error',
};

/** The icon of each status value. `aria-hidden` hides it. The text next to it gives the meaning. */
export const STATUS_ICON: Record<AppStatus, string> = {
  OK: '✓',
  NEVER_POLLED: '–',
  UNREACHABLE: '⚠',
  UNAUTHORIZED: '⚠',
  OVERPRIVILEGED: '⚠',
  INVALID_DATA: '⚠',
  ERROR: '⚠',
};

/** The badge class of each status value (D45, the status badge table of docs/design.md). */
export const STATUS_BADGE_CLASS: Record<AppStatus, string> = {
  OK: 'badge-ok',
  NEVER_POLLED: 'badge-none',
  UNREACHABLE: 'badge-bad',
  UNAUTHORIZED: 'badge-bad',
  OVERPRIVILEGED: 'badge-warn',
  INVALID_DATA: 'badge-bad',
  ERROR: 'badge-bad',
};

/** Formats a count in the locale of the browser (D30). */
export const numberFormat = new Intl.NumberFormat();

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

/** Formats an ISO time in local time, as D31 states: `yyyy-MM-dd HH:mm:ss`. */
export function formatLocalDateTime(iso: string): string {
  const date = new Date(iso);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * Formats an ISO time in local time, with no seconds: `yyyy-MM-dd HH:mm`.
 * The Apps "Last updated" cell uses this format for its visible absolute
 * text and its `title` (the addendum of D31, issue #390). The Sessions
 * "Start time" column and the Elements "Last interaction" column keep
 * `formatLocalDateTime` and its seconds.
 */
export function formatLocalMinute(iso: string): string {
  const date = new Date(iso);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/**
 * Formats an ISO time as a short relative text against `Date.now()`, for
 * example "just now", "3 min ago", "2 hours ago", or "5 days ago" (issue
 * #390, the "Last updated" cell text). The Apps view reads its own clock
 * signal next to the call, so a new 30 s tick makes the template call this
 * function again, with a fresh `Date.now()` (MAJOR 1 of the review of pull
 * request #437).
 */
export function formatRelativeTime(iso: string): string {
  const diffMs = Math.max(0, Date.now() - new Date(iso).getTime());
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) {
    return 'just now';
  }
  if (minutes < 60) {
    return `${minutes} min ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`;
  }
  const days = Math.floor(hours / 24);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}

/** Formats an ISO time in local time, the date part only: `yyyy-MM-dd` (D16, the gap text). */
export function formatLocalDate(iso: string): string {
  const date = new Date(iso);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Reads the short zone name of the browser, for example "UTC" or "GMT+2" (D31). */
export function readZoneName(): string {
  const part = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((entry) => entry.type === 'timeZoneName');
  return part?.value ?? '';
}
