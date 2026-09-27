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

  /** The row of one user id. Give `null` for the anonymous row (shown as "(anonymous)"). */
  rowOf(userId: string | null): UserRowLocators {
    const label = userId ?? '(anonymous)';
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(label)}\\b`) });
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
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
