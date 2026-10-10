import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AppNames } from '../app-names';
import { EmptyState } from '../empty-state/empty-state';
import { createPollStore } from '../poll/poll-store';
import { RefreshIntervalState } from '../poll/refresh-interval-state';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import {
  STATUS_BADGE_CLASS,
  STATUS_ICON,
  STATUS_LABEL,
  formatLocalDate,
  formatLocalMinute,
  formatRelativeTime,
  numberFormat,
} from '../status-format';
import type { AppRow, AppStatus, Gap } from './app-row';

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
  imports: [EmptyState, RefreshBar, RouterLink],
  styleUrl: './apps.css',
  templateUrl: './apps.html',
})
export class Apps implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly refreshIntervalState = inject(RefreshIntervalState);
  private readonly appNames = inject(AppNames);

  /** The poll store of the app list. */
  protected readonly store = createPollStore<AppRow[]>(() => this.http.get<AppRow[]>('/api/apps'));

  /**
   * A clock that moves every 30 s, so the "Last updated" cell reads a
   * fresh relative time with no new poll answer (issue #390, MAJOR 1 of
   * the review of pull request #437). The zoneless app else recomputes the
   * cell text only on a poll answer; a paused poll, a hidden tab, or a
   * focus inside `<tbody>` would then freeze a stale "just now".
   */
  private readonly clockTick = signal(Date.now());
  private readonly clockIntervalId = setInterval(() => this.clockTick.set(Date.now()), 30_000);

  /**
   * The "Totals of the last <n> days." text (D13, D15, issue #66). Null
   * while `retentionDays` of the health answer is null, so the view shows
   * no text before the answer arrives and no text for an invalid value.
   */
  protected readonly retentionText = computed(() => {
    const days = this.refreshIntervalState.retentionDays();
    return days === null ? null : `Totals of the last ${numberFormat.format(days)} days.`;
  });

  constructor() {
    // Fills the app-name store from each answer of GET /api/apps (issue
    // #270), so the breadcrumb of a level 2 or a level 3 view can show the
    // name with no GET /api/apps/{id} of its own, once this view has run.
    effect(() => {
      const rows = this.store.data();
      if (rows) {
        this.appNames.setFromRows(rows);
      }
    });
  }

  /** Clears the 30 s clock interval, so no timer survives this component. */
  ngOnDestroy(): void {
    clearInterval(this.clockIntervalId);
  }

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

  /** The badge class of the status value (D45, docs/design.md). */
  protected statusBadgeClass(status: AppStatus): string {
    return STATUS_BADGE_CLASS[status];
  }

  /** The text of a count cell. `NEVER_POLLED` shows "–", never 0 (D30). */
  protected formatCount(row: AppRow, value: number): string {
    return row.status === 'NEVER_POLLED' ? '–' : numberFormat.format(value);
  }

  /**
   * The full local date and time, with no seconds (issue #390, step 4).
   * The "Last updated" cell shows this text in the `title` attribute of
   * its `<time>` element.
   */
  protected formatDateTime(iso: string): string {
    return formatLocalMinute(iso);
  }

  /**
   * The text of the "Last updated" cell: a short relative time against
   * `Date.now()`. The read of `clockTick` below gives this method a
   * signal dependency: a 30 s tick, with no poll answer, makes the
   * template call this method again, with a fresh `Date.now()` (issue
   * #390, MAJOR 1).
   */
  protected lastUpdatedText(iso: string): string {
    this.clockTick();
    return formatRelativeTime(iso);
  }

  /** The text of one gap row of D16 (issue #62): the date part only, in local time. */
  protected gapText(gap: Gap): string {
    return `Events from ${formatLocalDate(gap.from)} to ${formatLocalDate(gap.to)} can be absent.`;
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
