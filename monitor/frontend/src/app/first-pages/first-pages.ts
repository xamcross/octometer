import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Announcer } from '../announcer';
import { EmptyState } from '../empty-state/empty-state';
import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import { numberFormat } from '../status-format';
import type { FirstPageRow, FirstPagesResponse } from './first-page-row';

/**
 * The route "/apps/:appId/first-pages" (D28, D44 of the design). It shows
 * one row for each first page with its session count, and it reads
 * `GET /api/apps/{appId}/first-pages?page=` through the poll store of D29,
 * the same way `users.ts` reads the user list.
 *
 * `RefreshBar` of #92 stands between the heading and the table. The `page`
 * query parameter lives in the route as a router-bound signal input, so a
 * reload and the Back button keep it.
 *
 * `@for` tracks each row by `row.path ?? '\u0000'` (issue #213): a stored
 * path always starts with `/` (contract C4), so a stored path never holds
 * that key, and a `null` path always holds that same key.
 *
 * The path cell opens the anonymous-sessions view of #115 (D44), with the
 * query parameters `anonymous=true` and `firstPath=<path>`. A click on the
 * count cell of a row opens that same link (D28), the same way as the
 * level 1 view; a `null`-path row holds no link, and a click on its count
 * cell does nothing (issue #213), because the API filter matches a
 * missing path against no session (decision 2 of issue #213).
 *
 * A 404 answer means the app is not registered. The view then shows the
 * not-found card in place of the table, and the poll stops, the same way
 * `users.ts` does (issue #348).
 */
@Component({
  selector: 'app-first-pages',
  imports: [EmptyState, RefreshBar, RouterLink],
  styleUrl: './first-pages.css',
  templateUrl: './first-pages.html',
})
export class FirstPages {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);

  /** The `appId` path parameter, bound by the router. */
  readonly appId = input.required<string>();

  /** The `page` query parameter, bound by the router. Null means page 1. */
  readonly page = input<string | null>(null);

  /** The page number of the query parameter, clamped to a minimum of 1. */
  protected readonly pageNumber = computed(() => {
    const parsed = Number(this.page());
    return Number.isInteger(parsed) && parsed >= 1 ? parsed : 1;
  });

  /** The poll store of the first-page list of this app. */
  protected readonly store = createPollStore<FirstPagesResponse>(() =>
    this.http.get<FirstPagesResponse>(`/api/apps/${this.appId()}/first-pages`, {
      params: { page: this.pageNumber() },
    }),
  );

  /**
   * True when the last data request failed with a 404 answer: the app id
   * of the route is not registered (issue #348). The template then shows
   * the not-found card in place of the table, and the constructor below
   * stops the poll.
   */
  protected readonly appNotFound = computed(() => {
    const error = this.store.dataError();
    return error instanceof HttpErrorResponse && error.status === 404;
  });

  /** The page count of the last good answer. 1 before the first answer arrives. */
  protected readonly pageCount = computed(() => this.store.data()?.pageCount ?? 1);

  /**
   * The current page. It reads the `page` field of the last good answer,
   * because the API can clamp an out-of-range page (D13). It falls back
   * to the route value before the first answer arrives.
   */
  protected readonly currentPage = computed(() => this.store.data()?.page ?? this.pageNumber());

  /** The key of the last request that the effect below sent, so it skips the first run. */
  private previousQueryKey: string | null = null;

  /** The `appId` of the last request that the effect below sent. */
  private previousAppId: string | null = null;

  /**
   * True after a pager click sends a fresh request, until that request
   * answers. The announce effect below reads this flag, so it speaks the
   * result after the answer, and never before the request (the form of
   * `users.ts`, accessibility BLOCKER 1 of the correction round 1 of pull
   * request #159, MAJOR 1 of the review of pull request #216).
   */
  private readonly pendingAnnouncement = signal(false);

  /**
   * The `data` and `dataError` of the store at the moment a page change
   * starts a fresh request. Old rows stay on the screen while that
   * request is in flight (D30), so the announce effect below compares
   * each signal against these fields, and it waits until one of them
   * holds a new reference.
   */
  private pendingBaselineData: FirstPagesResponse | undefined = undefined;
  private pendingBaselineError: unknown = undefined;

  constructor() {
    // Calls refresh() once for each user action that changes appId or
    // page: a pager click, a reload, or the Back button. The store itself
    // already sends the first request. A change of appId also resets the
    // store first, so the rows and the error of the old app leave the
    // view at once, and a late answer of the old app cannot fill it (the
    // form of `users.ts`).
    effect(() => {
      const appId = this.appId();
      const key = `${appId}|${this.pageNumber()}`;
      if (this.previousQueryKey !== null && this.previousQueryKey !== key) {
        if (this.previousAppId !== null && this.previousAppId !== appId) {
          this.store.reset();
        }
        this.pendingBaselineData = this.store.data();
        this.pendingBaselineError = this.store.dataError();
        this.pendingAnnouncement.set(true);
        this.store.refresh();
      }
      this.previousAppId = appId;
      this.previousQueryKey = key;
    });

    // Stops the poll on a 404 answer (issue #348): the app id of the
    // route is not registered, so a further request would only repeat
    // the same answer. A later change of appId resets the store first,
    // and that reset opens the gate again for the new target.
    effect(() => {
      if (this.appNotFound()) {
        this.store.stop();
      }
    });

    // Announces the result of a pager click, once that request answers
    // (MAJOR 1 of the review of pull request #216). It stays silent for
    // the first load, for an automatic poll tick, and for a failed
    // request.
    effect(() => {
      const page = this.store.data();
      const error = this.store.dataError();
      if (!this.pendingAnnouncement()) {
        return;
      }
      if (page === this.pendingBaselineData && error === this.pendingBaselineError) {
        return; // The request of this action has not answered yet.
      }
      this.pendingAnnouncement.set(false);
      if (page !== undefined && page !== this.pendingBaselineData) {
        this.announceResult(page);
      }
    });
  }

  /** The text of a count cell. */
  protected formatCount(value: number): string {
    return numberFormat.format(value);
  }

  /**
   * Opens the link of the path cell, from a click on a different cell of
   * the same row (D28). It does nothing while the user selects text
   * inside the clicked cell with the mouse, for a secondary button, or
   * for a modifier key. It does nothing for a `null`-path row (issue
   * #213): the router drops a `null` query parameter, so that click
   * would open the unfiltered session list, a wrong link for a row with
   * no link of its own.
   */
  protected onRowCellClick(row: FirstPageRow, event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    if (this.selectionTouchesCell(event.currentTarget)) {
      return;
    }
    if (row.path === null) {
      return;
    }
    void this.router.navigate(['/apps', this.appId(), 'sessions'], {
      queryParams: { anonymous: 'true', firstPath: row.path },
    });
  }

  /** Moves to the previous page. It does nothing on page 1. */
  protected onPreviousPage(): void {
    if (this.currentPage() <= 1) {
      return;
    }
    this.goToPage(this.currentPage() - 1);
  }

  /** Moves to the next page. It does nothing on the last page. */
  protected onNextPage(): void {
    if (this.currentPage() >= this.pageCount()) {
      return;
    }
    this.goToPage(this.currentPage() + 1);
  }

  /** Navigates to the given page. */
  private goToPage(page: number): void {
    void this.router.navigate(['/apps', this.appId(), 'first-pages'], {
      queryParams: { page },
    });
  }

  /**
   * Announces the row count of a fresh answer, plus the page it belongs
   * to (MAJOR 1 of the review of pull request #216).
   */
  private announceResult(page: FirstPagesResponse): void {
    this.announcer.announce(
      `${this.rowCountText(page.rows.length)} Page ${page.page} of ${page.pageCount}.`,
    );
  }

  /** The count part of the announce text: "No first page." or "N first pages." */
  private rowCountText(count: number): string {
    if (count === 0) {
      return 'No first page.';
    }
    if (count === 1) {
      return '1 first page.';
    }
    return `${count} first pages.`;
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
