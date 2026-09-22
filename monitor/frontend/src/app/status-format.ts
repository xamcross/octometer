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

/** Formats an ISO time in local time, the time part only: `HH:mm:ss`. */
export function formatLocalTime(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** Reads the short zone name of the browser, for example "UTC" or "GMT+2" (D31). */
export function readZoneName(): string {
  const part = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((entry) => entry.type === 'timeZoneName');
  return part?.value ?? '';
}
