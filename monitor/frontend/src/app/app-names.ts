import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

import type { AppRow } from './apps/app-row';
import type { AppDetail } from './manage/app-registry-api';

/**
 * Holds the app name of each app id, for the breadcrumb (issue #270).
 *
 * The Apps view fills this store from each answer of `GET /api/apps`
 * (`apps/apps.ts`, through `setFromRows`). The shell asks for a missing id
 * through one `GET /api/apps/{id}` (issue #226, through `ensureLoaded`), for
 * example on a direct load of a level 2 or a level 3 view. A 404 answer
 * keeps the caller's fallback label; the store asks again on a later call
 * for the same id, because a failed id never joins the store.
 */
@Injectable({ providedIn: 'root' })
export class AppNames {
  private readonly http = inject(HttpClient);
  private readonly names = signal<ReadonlyMap<number, string>>(new Map());

  /** The app id of each `GET /api/apps/{id}` request in flight. */
  private readonly pending = new Set<number>();

  /** The name of the given app id, or undefined while the store does not hold it. */
  nameFor(appId: number): string | undefined {
    return this.names().get(appId);
  }

  /**
   * Fills the store from one answer of `GET /api/apps` (`apps/apps.ts`).
   * `update` reads the current map with no tracked signal read, so a
   * caller inside an `effect` on the poll data never turns this write
   * into its own dependency (a self-triggering effect, an infinite
   * loop of read-then-write on the one signal).
   */
  setFromRows(rows: readonly AppRow[]): void {
    this.names.update((current) => {
      const next = new Map(current);
      for (const row of rows) {
        next.set(row.appId, row.name);
      }
      return next;
    });
  }

  /**
   * Sends one `GET /api/apps/{id}` for a missing app id. It does nothing
   * while the store already holds the id, or a request for it is in
   * flight.
   */
  ensureLoaded(appId: number): void {
    if (this.names().has(appId) || this.pending.has(appId)) {
      return;
    }
    this.pending.add(appId);
    this.http.get<AppDetail>(`/api/apps/${appId}`).subscribe({
      next: (detail) => {
        this.pending.delete(appId);
        this.names.update((current) => new Map(current).set(detail.appId, detail.name));
      },
      error: () => {
        this.pending.delete(appId);
      },
    });
  }
}
