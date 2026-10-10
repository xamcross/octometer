import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/** The cells of one app row of the level 1 view (`monitor/frontend/src/app/apps`). */
export interface AppRowLocators {
  readonly row: Locator;
  readonly nameLink: Locator;
  readonly clicks: Locator;
  readonly uniqueUsers: Locator;
  readonly uniqueSessions: Locator;
  readonly status: Locator;
}

export interface AppTotals {
  readonly clicks: number;
  readonly uniqueUsers: number;
  readonly uniqueSessions: number;
}

/** The level 1 view: the app rows with the clicks, the users, the sessions, the status. */
export class AppsPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/apps');
  }

  /**
   * The row of one app, by its name. The table has one row per app in
   * this suite, so a role-based row lookup by its accessible name stays
   * exact; the cells then come by column position (App, Clicks, Unique
   * users, Unique sessions, First pages, Status, Data time), through
   * `getByRole('cell')`, never a CSS class of the cartoon look.
   */
  rowOf(name: string): AppRowLocators {
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(name)}\\b`) });
    const cells = row.getByRole('cell');
    return {
      row,
      nameLink: row.getByRole('link', { name }),
      clicks: cells.nth(0),
      uniqueUsers: cells.nth(1),
      uniqueSessions: cells.nth(2),
      status: cells.nth(4),
    };
  }

  /** The count of data rows of the table (the header row excluded), for issue #301. */
  async rowCount(): Promise<number> {
    return this.page.getByRole('row').count().then((count) => Math.max(count - 1, 0));
  }

  /** Waits, up to `timeoutMs`, for the exact level 1 totals of one app (decision 5b). */
  async expectTotals(name: string, totals: AppTotals, timeoutMs: number): Promise<void> {
    const locators = this.rowOf(name);
    await expect(locators.clicks).toHaveText(String(totals.clicks), { timeout: timeoutMs });
    await expect(locators.uniqueUsers).toHaveText(String(totals.uniqueUsers), { timeout: timeoutMs });
    await expect(locators.uniqueSessions).toHaveText(String(totals.uniqueSessions), {
      timeout: timeoutMs,
    });
  }

  /** Waits, up to `timeoutMs`, for the status badge word of one app. */
  async expectStatus(name: string, word: string, timeoutMs: number): Promise<void> {
    await expect(this.rowOf(name).status).toContainText(word, { timeout: timeoutMs });
  }

  /** The "First pages" header cell of the table (issue #394). */
  get firstPagesHeader(): Locator {
    return this.page.getByRole('columnheader', { name: 'First pages' });
  }

  /** The "First pages" link of one app row, by its name (issue #394). */
  firstPagesLinkOf(name: string): Locator {
    return this.rowOf(name).row.getByRole('link', { name: 'First pages' });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
