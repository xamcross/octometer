import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input } from '@angular/core';

import { EmptyState } from '../empty-state/empty-state';
import { createPollStore } from '../poll/poll-store';
import { RefreshBar } from '../refresh-bar/refresh-bar';
import { formatLocalDateTime, numberFormat, readZoneName } from '../status-format';
import type { ElementsResponse } from './element-row';

/**
 * Reads the elements filter from the route's query parameters, and
 * builds the request parameters of `GET /api/apps/{appId}/elements`
 * (D13). It reads `sessionId`, `anonymous=true`, or `userId`, the same
 * as the API: exactly one of the three, else the API answers 400
 * (issue #115).
 */
export function buildElementsRequestParams(
  userId: string | null,
  anonymous: string | null,
  sessionId: string | null,
): Record<string, string> {
  if (sessionId) {
    return { sessionId };
  }
  if (anonymous === 'true') {
    return { anonymous: 'true' };
  }
  if (userId) {
    return { userId };
  }
  return {};
}

/**
 * The route "/apps/:appId/elements" (D28, level 3 of the design). It
 * shows one row for each element that the user touched, and it reads
 * `GET /api/apps/{appId}/elements?userId=<id>`, `?anonymous=true`, or
 * `?sessionId=<id>` (issue #115) through the poll store of #164.
 * `RefreshBar` of #92 stands between the heading and the table.
 *
 * The table is one flat `<table>`. `@for` tracks each row by `element`
 * (D29), the stable key of one grouped row. The route guard of
 * `app.routes.ts` sends a request with none of `userId`,
 * `anonymous=true`, and `sessionId` back to the user list, so this
 * view always polls with exactly one of the three.
 *
 * The router keeps one component instance for a change of `appId`
 * alone, or for a change of the filter alone, for example a move from
 * one user row to another (the same reuse that `Users` handles). The
 * filter-key effect below detects each change: a change of `appId`
 * resets the poll store first, so a late answer of the old app cannot
 * reach the view; a change of the filter alone sends a fresh request
 * with no reset, because the rows of the same app stay meaningful
 * until the fresh answer arrives.
 *
 * A 404 answer means the app is not registered. The view then shows the
 * not-found card in place of the table, and the poll stops, the same way
 * `users.ts` does (issue #348).
 */
@Component({
  selector: 'app-elements',
  imports: [EmptyState, RefreshBar],
  styleUrl: './elements.css',
  templateUrl: './elements.html',
})
export class Elements {
  private readonly http = inject(HttpClient);

  /** The `appId` path parameter, bound by the router. */
  readonly appId = input.required<string>();

  /** The `userId` query parameter, bound by the router. Null for an anonymous user. */
  readonly userId = input<string | null>(null);

  /** The `anonymous` query parameter, bound by the router. */
  readonly anonymous = input<string | null>(null);

  /** The `sessionId` query parameter, bound by the router (issue #115). */
  readonly sessionId = input<string | null>(null);

  /**
   * The heading text. It names the same user, or session, as the last
   * breadcrumb entry, and it shows the full session id as text
   * (issue #115).
   */
  protected readonly heading = computed(() => {
    const sessionId = this.sessionId();
    if (sessionId) {
      return `Elements of Session ${sessionId}`;
    }
    if (this.anonymous() === 'true') {
      return 'Elements of Anonymous';
    }
    const userId = this.userId();
    return userId ? `Elements of User ${userId}` : 'Elements';
  });

  /** The zone name for the "Last interaction" column header (D31). */
  protected readonly zoneName = readZoneName();

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

  /** The poll store of the element list of this app and this filter. */
  protected readonly store = createPollStore<ElementsResponse>(() =>
    this.http.get<ElementsResponse>(`/api/apps/${this.appId()}/elements`, {
      params: buildElementsRequestParams(this.userId(), this.anonymous(), this.sessionId()),
    }),
  );

  /** The key of the last request that the effect below sent, so it skips the first run. */
  private previousFilterKey: string | null = null;

  /** The `appId` of the last request that the effect below sent. */
  private previousAppId: string | null = null;

  constructor() {
    // Sends a fresh request on each change of appId, userId, or
    // anonymous: a move to a different user row, or a route reuse
    // across two app ids. A change of appId also resets the store
    // first, so the rows and the error of the old app leave the view
    // at once, and a late answer of the old app cannot fill it (the
    // same rule as MAJOR 2 of the correction round 1 of pull request
    // #159).
    effect(() => {
      const appId = this.appId();
      const key = `${appId}|${this.userId() ?? ''}|${this.anonymous() ?? ''}|${this.sessionId() ?? ''}`;
      if (this.previousFilterKey !== null && this.previousFilterKey !== key) {
        if (this.previousAppId !== null && this.previousAppId !== appId) {
          this.store.reset();
        }
        this.store.refresh();
      }
      this.previousAppId = appId;
      this.previousFilterKey = key;
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
  }

  /** The text of a count cell. */
  protected formatCount(value: number): string {
    return numberFormat.format(value);
  }

  /** The text of the last-interaction cell, in local time (D31). */
  protected formatDateTime(iso: string): string {
    return formatLocalDateTime(iso);
  }
}
