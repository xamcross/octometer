import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input } from '@angular/core';
import { Router } from '@angular/router';

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
 * A 404 answer means the app is not registered. The view then goes to
 * the parent view `/apps`, with the app id in the query parameter
 * `notFoundAppId` (D30).
 */
@Component({
  selector: 'app-elements',
  imports: [RefreshBar],
  styleUrl: './elements.css',
  templateUrl: './elements.html',
})
export class Elements {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

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

    effect(() => this.redirectOnAppNotFound());
  }

  /** The text of a count cell. */
  protected formatCount(value: number): string {
    return numberFormat.format(value);
  }

  /** The text of the last-interaction cell, in local time (D31). */
  protected formatDateTime(iso: string): string {
    return formatLocalDateTime(iso);
  }

  /**
   * Goes to the parent view `/apps` on a 404 answer (D30). The app id
   * travels in the query parameter `notFoundAppId`, so the parent view
   * can show a message. It fires on the first load and on a later 404,
   * for example a deleted app, or a change of appId to an app id that
   * does not exist.
   */
  private redirectOnAppNotFound(): void {
    const error = this.store.dataError();
    if (error instanceof HttpErrorResponse && error.status === 404) {
      void this.router.navigate(['/apps'], { queryParams: { notFoundAppId: this.appId() } });
    }
  }
}
