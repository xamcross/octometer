import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import {
  STATUS_ICON,
  STATUS_LABEL,
  formatLocalDateTime,
  formatLocalTime,
  numberFormat,
  readZoneName,
} from '../status-format';
import type { AppRow, AppStatus } from './app-row';

/**
 * The route "/apps" (D28, level 1 of the design). It shows one row for each
 * app, and it reads `GET /api/apps` through the poll store of #25.
 * `RefreshBar` of #92 stands between the heading and the table.
 *
 * The table is one flat `<table>`. `@for` tracks each row by `appId` (D29).
 * A refresh then keeps the DOM node of a row, and with it the focus and the
 * scroll position of the user.
 *
 * A click on a data cell of a row opens the link of the name cell (D28).
 * The cell carries no `role`, and it adds no second link. The click handler
 * still lets the user select the text of a cell with the mouse: it does
 * nothing while a selection touches the clicked cell, for a secondary
 * button, or for a modifier key.
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
   * same row (D28). It does nothing while the user selects text inside the
   * clicked cell with the mouse, so a drag over a number still selects that
   * number. A selection in a different part of the page does not stop the
   * click. It also does nothing for a secondary button or a modifier key.
   * The browser then handles a click on the name link itself, for example
   * to open a new tab.
   */
  protected onRowCellClick(row: AppRow, event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    if (this.selectionTouchesCell(event.currentTarget)) {
      return;
    }
    void this.router.navigate(['/apps', row.appId, 'users']);
  }

  /** True while the user selects text that touches the given cell. */
  private selectionTouchesCell(cell: EventTarget | null): boolean {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || cell === null) {
      return false;
    }
    return selection.containsNode(cell as Node, true);
  }
}
