import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs';

import { Announcer } from '../announcer';
import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import type { UserRow, UserTotalsResponse } from './user-row';

/** The wait after the last key stroke, before the filter field sends a request. */
const FILTER_DEBOUNCE_MS = 400;

/** Formats a count in the locale of the browser (D30). */
const numberFormat = new Intl.NumberFormat();

/**
 * The route "/apps/:appId/users" (D28, level 2 of the design). It shows
 * one row for each app user, and it reads
 * `GET /api/apps/{appId}/users?page=&q=` through the poll store of #25.
 * `RefreshBar` of #92 stands between the heading and the table.
 *
 * The page number and the filter text of the design live in the query
 * parameters `page` and `q`, so a reload and the Back button keep them.
 * The filter field sends the request 400 ms after the last key stroke,
 * or at once on a form submit (an Enter key press, or the Filter
 * button).
 *
 * The table is one flat `<table>`. `@for` tracks each row by `userId`
 * (D29). The row with `userId: null` shows the text "(anonymous)" and
 * opens the elements view with `anonymous=true` (D44). Issue #115 later
 * gives that row its own "Anonymous sessions" view.
 *
 * A click on a data cell of a row opens the link of the user cell
 * (D28), the same way as the level 1 view.
 *
 * A 404 answer means the app is not registered. The view then goes to
 * the parent view `/apps`, with the app id in the query parameter
 * `notFoundAppId` (D30).
 */
@Component({
  selector: 'app-users',
  imports: [RefreshBar, RouterLink],
  styleUrl: './users.css',
  templateUrl: './users.html',
})
export class Users {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);

  /** The `appId` path parameter, bound by the router. */
  readonly appId = input.required<string>();

  /** The `page` query parameter, bound by the router. Null means page 1. */
  readonly page = input<string | null>(null);

  /** The `q` query parameter, bound by the router. Null means no filter. */
  readonly q = input<string | null>(null);

  /** The page number of the query parameter, clamped to a minimum of 1. */
  protected readonly pageNumber = computed(() => {
    const parsed = Number(this.page());
    return Number.isInteger(parsed) && parsed >= 1 ? parsed : 1;
  });

  /** The filter text of the query parameter. An empty string means no filter. */
  protected readonly qValue = computed(() => this.q() ?? '');

  /** The text of the filter field. It mirrors `qValue`, and the user can edit it. */
  protected readonly filterDraft = signal('');

  /** The poll store of the user list of this app. */
  protected readonly store = createPollStore<UserTotalsResponse>(() =>
    this.http.get<UserTotalsResponse>(`/api/apps/${this.appId()}/users`, {
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

  constructor() {
    // Keeps the filter field in step with the q query parameter, for
    // example after the Back button, without touching text the user
    // still types (the value does not change while q does not change).
    effect(() => this.filterDraft.set(this.qValue()));

    // Calls refresh() once for each user action that changes appId,
    // page, or q: a filter submit, a pager click, a reload, or the Back
    // button. The store itself already sends the first request.
    effect(() => {
      const key = `${this.appId()}|${this.pageNumber()}|${this.qValue()}`;
      if (this.previousQueryKey !== null && this.previousQueryKey !== key) {
        this.store.refresh();
      }
      this.previousQueryKey = key;
    });

    effect(() => this.redirectOnAppNotFound());

    toObservable(this.filterDraft)
      .pipe(debounceTime(FILTER_DEBOUNCE_MS), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((value) => this.submitFilter(value));
  }

  /** The text of a count cell. */
  protected formatCount(value: number): string {
    return numberFormat.format(value);
  }

  /** The query parameters of the row link: `anonymous=true`, or the user id. */
  protected rowQueryParams(row: UserRow): Record<string, string> {
    return row.userId === null ? { anonymous: 'true' } : { userId: row.userId };
  }

  /**
   * Opens the link of the user cell, from a click on a different cell of
   * the same row (D28). It does nothing while the user selects text
   * inside the clicked cell with the mouse, for a secondary button, or
   * for a modifier key.
   */
  protected onRowCellClick(row: UserRow, event: MouseEvent): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    if (this.selectionTouchesCell(event.currentTarget)) {
      return;
    }
    void this.router.navigate(['/apps', this.appId(), 'elements'], {
      queryParams: this.rowQueryParams(row),
    });
  }

  /** Reads the value of the filter field, and updates the draft signal. */
  protected onFilterInput(event: Event): void {
    this.filterDraft.set((event.target as HTMLInputElement).value);
  }

  /** Submits the filter field at once, with no debounce wait. */
  protected onFilterSubmit(event: Event): void {
    event.preventDefault();
    this.submitFilter(this.filterDraft());
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

  /**
   * Sends the filter as the query parameter q, and resets the page to 1.
   * It does nothing when the value equals the current q, so an
   * unchanged submit does not reset the page.
   */
  private submitFilter(value: string): void {
    if (value === this.qValue()) {
      return;
    }
    void this.router.navigate(['/apps', this.appId(), 'users'], {
      queryParams: { page: null, q: value || null },
    });
  }

  /** Navigates to the given page, and announces the change through the status region. */
  private goToPage(page: number): void {
    this.announcer.announce(`Page ${page} of ${this.pageCount()}.`);
    void this.router.navigate(['/apps', this.appId(), 'users'], {
      queryParams: { page, q: this.qValue() || null },
    });
  }

  /**
   * Goes to the parent view `/apps` on a 404 answer (D30). The app id
   * travels in the query parameter `notFoundAppId`, so the parent view
   * can show a message. It checks `data()` too, so a 404 after a good
   * answer, for example a deleted app, does not clear the table under
   * the user.
   */
  private redirectOnAppNotFound(): void {
    const error = this.store.error();
    if (
      error instanceof HttpErrorResponse &&
      error.status === 404 &&
      this.store.data() === undefined
    ) {
      void this.router.navigate(['/apps'], { queryParams: { notFoundAppId: this.appId() } });
    }
  }

  /** The request parameters of the page: page always, q only when it holds a value. */
  private buildParams(): Record<string, string | number> {
    const params: Record<string, string | number> = { page: this.pageNumber() };
    const q = this.qValue();
    if (q) {
      params['q'] = q;
    }
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
