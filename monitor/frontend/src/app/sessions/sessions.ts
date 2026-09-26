import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Announcer } from '../announcer';
import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import { formatLocalDateTime, numberFormat, readZoneName } from '../status-format';
import type { SessionRow, SessionsResponse } from './session-row';

/**
 * The route "/apps/:appId/sessions" (D28, D44, issue #115). It shows
 * one row for each anonymous session, and it reads
 * `GET /api/apps/{appId}/sessions?anonymous=true&firstPath=&page=`
 * through the poll store of D29, the same way `users.ts` reads the
 * user list.
 *
 * The guard `requireSessionsFilter` of `app.routes.ts` sends a
 * request with no `anonymous=true` back to the user list, because the
 * API of issue #113 gives 400 without that value. `firstPath` is
 * optional (D44): an absent or an empty value means no filter, and a
 * present value keeps only the sessions of that one first page. The
 * view shows the active `firstPath` value above the table, through
 * text interpolation only.
 *
 * `RefreshBar` of #92 stands between the heading and the table. Each
 * of `firstPath` and `page` lives in the route as a router-bound
 * signal input, so a reload and the Back button keep them.
 *
 * `@for` tracks each row by `sessionId`, a UUID that the store never
 * repeats inside one page (D29).
 *
 * The session cell opens the elements view of #53 with the
 * `sessionId` value (issue #115, step 4). A click on a different cell
 * of the same row opens that same link (D28), the same way as the
 * other table views.
 *
 * A 404 answer means the app is not registered. The view then goes to
 * the parent view `/apps`, with the app id in the query parameter
 * `notFoundAppId` (D30), the same way `users.ts` does.
 */
@Component({
  selector: 'app-sessions',
  imports: [RefreshBar, RouterLink],
  styleUrl: './sessions.css',
  templateUrl: './sessions.html',
})
export class Sessions {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);

  /** The `appId` path parameter, bound by the router. */
  readonly appId = input.required<string>();

  /** The `firstPath` query parameter, bound by the router. Null means no filter. */
  readonly firstPath = input<string | null>(null);

  /** The `page` query parameter, bound by the router. Null means page 1. */
  readonly page = input<string | null>(null);

  /** The zone name for the "Start time" column header (D31). */
  protected readonly zoneName = readZoneName();

  /** The page number of the query parameter, clamped to a minimum of 1. */
  protected readonly pageNumber = computed(() => {
    const parsed = Number(this.page());
    return Number.isInteger(parsed) && parsed >= 1 ? parsed : 1;
  });

  /** The poll store of the anonymous session list of this app. */
  protected readonly store = createPollStore<SessionsResponse>(() =>
    this.http.get<SessionsResponse>(`/api/apps/${this.appId()}/sessions`, {
      params: this.buildParams(),
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

  /**
   * True after a pager click or a `firstPath` change sends a fresh
   * request, until that request answers. The announce effect below
   * reads this flag, so it speaks the result after the answer, and
   * never before the request (the form of `users.ts` and
   * `first-pages.ts`, lesson 6 of the frontend brief).
   */
  private readonly pendingAnnouncement = signal(false);

  /**
   * The `data` and `dataError` of the store at the moment a query-key
   * change starts a fresh request. Old rows stay on the screen while
   * that request is in flight (D30), so the announce effect below
   * compares each signal against these fields, and it waits until one
   * of them holds a new reference.
   */
  private pendingBaselineData: SessionsResponse | undefined = undefined;
  private pendingBaselineError: unknown = undefined;

  constructor() {
    // Calls refresh() once for each user action that changes appId,
    // firstPath, or page: a pager click, a first-page filter change, a
    // reload, or the Back button. The store itself already sends the
    // first request. A change of appId also resets the store first, so
    // the rows and the error of the old app leave the view at once, and
    // a late answer of the old app cannot fill it (the form of
    // `users.ts`).
    effect(() => {
      const appId = this.appId();
      const key = `${appId}|${this.firstPath() ?? ''}|${this.pageNumber()}`;
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

    effect(() => this.redirectOnAppNotFound());

    // Announces the result of a pager click or a filter change, once
    // that request answers (lesson 6 of the frontend brief). It stays
    // silent for the first load, for an automatic poll tick, and for a
    // failed request.
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

  /** The text of the start-time cell, in local time (D31). */
  protected formatDateTime(iso: string): string {
    return formatLocalDateTime(iso);
  }

  /** The text of the user cell: "(anonymous)" for a null user id, else the id. */
  protected formatUser(userId: string | null): string {
    return userId === null ? '(anonymous)' : userId;
  }

  /**
   * Opens the link of the session cell, from a click on a different
   * cell of the same row (D28). It does nothing while the user selects
   * text inside the clicked cell with the mouse, for a secondary
   * button, or for a modifier key.
   */
  protected onRowCellClick(row: SessionRow, event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    if (this.selectionTouchesCell(event.currentTarget)) {
      return;
    }
    void this.router.navigate(['/apps', this.appId(), 'elements'], {
      queryParams: { sessionId: row.sessionId },
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

  /** Navigates to the given page. It keeps the active firstPath filter. */
  private goToPage(page: number): void {
    void this.router.navigate(['/apps', this.appId(), 'sessions'], {
      queryParams: { anonymous: 'true', firstPath: this.firstPath(), page },
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

  /**
   * Announces the row count of a fresh answer, plus the page it
   * belongs to (lesson 6 of the frontend brief).
   */
  private announceResult(page: SessionsResponse): void {
    this.announcer.announce(
      `${this.rowCountText(page.rows.length)} Page ${page.page} of ${page.pageCount}.`,
    );
  }

  /** The count part of the announce text: "No anonymous session." or "N anonymous sessions." */
  private rowCountText(count: number): string {
    if (count === 0) {
      return 'No anonymous session.';
    }
    if (count === 1) {
      return '1 anonymous session.';
    }
    return `${count} anonymous sessions.`;
  }

  /** The request parameters: anonymous=true and the page always, firstPath only when it holds a value. */
  private buildParams(): Record<string, string | number> {
    const params: Record<string, string | number> = { anonymous: 'true' };
    const firstPath = this.firstPath();
    if (firstPath) {
      params['firstPath'] = firstPath;
    }
    params['page'] = this.pageNumber();
    return params;
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
