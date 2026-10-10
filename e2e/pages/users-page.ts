import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

export interface UserRowLocators {
  readonly row: Locator;
  readonly clicks: Locator;
  readonly sessions: Locator;
  readonly uniqueElements: Locator;
}

/** The level 2 view of one app: the users, with the click totals of each user. */
export class UsersPage {
  constructor(private readonly page: Page) {}

  async goto(appId: number): Promise<void> {
    await this.page.goto(`/apps/${appId}/users`);
  }

  /**
   * The row of one user id. Give `null` for the anonymous row (shown
   * as "(anonymous)").
   *
   * Issue #300, a defect this story's own scenario found: the label
   * "(anonymous)" starts and ends with a character that is not a word
   * character, so a `\b` word boundary never matches right before it;
   * `\b\(anonymous\)\b` then matches no row text at all. The boundary
   * here is a run of whitespace, or the start or the end of the row's
   * whole text, instead: it matches every label this page gives it,
   * word-shaped or not.
   */
  rowOf(userId: string | null): UserRowLocators {
    const label = userId ?? '(anonymous)';
    const row = this.page.getByRole('row', { name: new RegExp(`(?:^|\\s)${escapeRegExp(label)}(?:\\s|$)`) });
    const cells = row.getByRole('cell');
    return {
      row,
      clicks: cells.nth(0),
      sessions: cells.nth(1),
      uniqueElements: cells.nth(2),
    };
  }

  /** The count of data rows of the table (the header row excluded). */
  async rowCount(): Promise<number> {
    return this.page.getByRole('row').count().then((count) => Math.max(count - 1, 0));
  }

  /** The filter input of the users view (issue #374). */
  filterInput(): Locator {
    return this.page.locator('#user-filter');
  }

  /** The Filter button of the users view (issue #374). */
  filterButton(): Locator {
    return this.page.getByRole('button', { name: 'Filter', exact: true });
  }

  async expectRow(
    userId: string,
    totals: { clicks: number; sessions: number; uniqueElements: number },
    timeoutMs: number,
  ): Promise<void> {
    const locators = this.rowOf(userId);
    await expect(locators.clicks).toHaveText(String(totals.clicks), { timeout: timeoutMs });
    await expect(locators.sessions).toHaveText(String(totals.sessions), { timeout: timeoutMs });
    await expect(locators.uniqueElements).toHaveText(String(totals.uniqueElements), {
      timeout: timeoutMs,
    });
  }

  /**
   * Waits, with a retry, for the row of `userId` to leave the table
   * (issue #300, the correction round of 2026-10-01, B3). `rowCount()`
   * reads the table once, at once; an erasure needs one more poll cycle
   * before the row leaves, so a caller must retry, not read once.
   */
  async expectNoRow(userId: string, timeoutMs: number): Promise<void> {
    await expect(this.rowOf(userId).row).toHaveCount(0, { timeout: timeoutMs });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
