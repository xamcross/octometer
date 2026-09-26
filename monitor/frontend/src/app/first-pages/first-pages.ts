import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

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
 * `@for` tracks each row by `path`. Two rows can share the text
 * `(unknown)` today, because the API maps each `NULL` path to that one
 * text (D44); issue #213 later changes the field to `path: string | null`.
 * One test of `first-pages.spec.ts` documents this present behaviour.
 *
 * The path cell opens the anonymous-sessions view of #115 (D44), with the
 * query parameters `anonymous=true` and `firstPath=<path>`. That view does
 * not exist yet on `main`; issue #115 adds it. A click on the count cell of
 * a row opens that same link (D28), the same way as the level 1 view.
 *
 * A 404 answer means the app is not registered. The view then goes to the
 * parent view `/apps`, with the app id in the query parameter
 * `notFoundAppId` (D30), the same way `users.ts` does.
 */
@Component({
  selector: 'app-first-pages',
  imports: [RefreshBar, RouterLink],
  styleUrl: './first-pages.css',
  templateUrl: './first-pages.html',
})
export class FirstPages {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

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
        this.store.refresh();
      }
      this.previousAppId = appId;
      this.previousQueryKey = key;
    });

    effect(() => this.redirectOnAppNotFound());
  }

  /** The text of a count cell. */
  protected formatCount(value: number): string {
    return numberFormat.format(value);
  }

  /**
   * Opens the link of the path cell, from a click on a different cell of
   * the same row (D28). It does nothing while the user selects text
   * inside the clicked cell with the mouse, for a secondary button, or
   * for a modifier key.
   */
  protected onRowCellClick(row: FirstPageRow, event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    if (this.selectionTouchesCell(event.currentTarget)) {
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
   * Goes to the parent view `/apps` on a 404 answer (D30). The app id
   * travels in the query parameter `notFoundAppId`, so the parent view
   * can show a message. It fires on the first load and on a later 404,
   * for example a change of appId to an app id that does not exist.
   */
  private redirectOnAppNotFound(): void {
    const error = this.store.dataError();
    if (error instanceof HttpErrorResponse && error.status === 404) {
      void this.router.navigate(['/apps'], { queryParams: { notFoundAppId: this.appId() } });
    }
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
