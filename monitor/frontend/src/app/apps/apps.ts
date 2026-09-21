import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import type { AppRow, AppStatus } from './app-row';

/** The visible text of each status value (D33: an icon plus text, never colour alone). */
const STATUS_LABEL: Record<AppStatus, string> = {
  OK: 'OK',
  NEVER_POLLED: 'Not polled yet',
  UNREACHABLE: 'Unreachable',
  UNAUTHORIZED: 'Unauthorized',
  OVERPRIVILEGED: 'Overprivileged',
  INVALID_DATA: 'Invalid data',
  ERROR: 'Error',
};

/** The icon of each status value. `aria-hidden` hides it; the text next to it carries the meaning. */
const STATUS_ICON: Record<AppStatus, string> = {
  OK: '✓',
  NEVER_POLLED: '–',
  UNREACHABLE: '⚠',
  UNAUTHORIZED: '⚠',
  OVERPRIVILEGED: '⚠',
  INVALID_DATA: '⚠',
  ERROR: '⚠',
};

/** Formats a count in the locale of the browser (D30). */
const numberFormat = new Intl.NumberFormat();

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

/** Formats an ISO time in local time, as D31 states: `yyyy-MM-dd HH:mm:ss`. */
function formatLocalDateTime(iso: string): string {
  const date = new Date(iso);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** Formats an ISO time in local time, the time part only: `HH:mm:ss`. */
function formatLocalTime(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** Reads the short zone name of the browser, for example "UTC" or "GMT+2" (D31). */
function readZoneName(): string {
  const part = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
    .formatToParts(new Date())
    .find((entry) => entry.type === 'timeZoneName');
  return part?.value ?? '';
}

/**
 * The route "/apps" (D28, level 1 of the design). It shows one row for each
 * app, and it reads `GET /api/apps` through the poll store of #25.
 * `RefreshBar` of #92 stands between the heading and the table.
 *
 * The table is one flat `<table>`. `@for` tracks each row by `appId` (D29),
 * so a refresh keeps the DOM node of a row, and with it the focus and the
 * scroll position of the user.
 *
 * A click on a data cell of a row opens the link of the name cell (D28). The
 * cell carries no `role`, and it adds no second link, so the browser still
 * lets the user select the text of a cell with the mouse: the click handler
 * checks `getSelection()` first, and it does nothing while text is selected.
 */
@Component({
  selector: 'app-apps',
  imports: [RefreshBar, RouterLink],
  styleUrl: './apps.css',
  templateUrl: './apps.html',
})
export class Apps {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /** The poll store of the app list. */
  protected readonly store = createPollStore<AppRow[]>(() => this.http.get<AppRow[]>('/api/apps'));

  /** The zone name for the "Data time" column header (D31). */
  protected readonly zoneName = readZoneName();

  /** True for each status other than `OK` and `NEVER_POLLED` (D30). */
  protected isFailed(status: AppStatus): boolean {
    return status !== 'OK' && status !== 'NEVER_POLLED';
  }

  protected statusLabel(status: AppStatus): string {
    return STATUS_LABEL[status];
  }

  protected statusIcon(status: AppStatus): string {
    return STATUS_ICON[status];
  }

  /** The text of a count cell. `NEVER_POLLED` shows "–", never 0 (D30). */
  protected formatCount(row: AppRow, value: number): string {
    return row.status === 'NEVER_POLLED' ? '–' : numberFormat.format(value);
  }

  protected formatDateTime(iso: string): string {
    return formatLocalDateTime(iso);
  }

  protected formatTime(iso: string): string {
    return formatLocalTime(iso);
  }

  /**
   * Opens the link of the name cell, from a click on a different cell of the
   * same row (D28). It does nothing while the user selects text with the
   * mouse, so a drag over a number still selects that number.
   */
  protected onRowCellClick(row: AppRow): void {
    if ((window.getSelection()?.toString().length ?? 0) > 0) {
      return;
    }
    void this.router.navigate(['/apps', row.appId, 'users']);
  }
}
