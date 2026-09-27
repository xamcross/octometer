import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

export interface ElementRowLocators {
  readonly row: Locator;
  readonly clicks: Locator;
  readonly sessions: Locator;
}

/**
 * The level 3 view of one app, filtered to one user, one anonymous set, or
 * one session (D13, `?userId=`, `?anonymous=true`, `?sessionId=`). This
 * suite needs the `userId` filter only (decision 5f).
 */
export class ElementsPage {
  constructor(private readonly page: Page) {}

  async gotoByUser(appId: number, userId: string): Promise<void> {
    await this.page.goto(`/apps/${appId}/elements?userId=${encodeURIComponent(userId)}`);
  }

  rowOf(element: string): ElementRowLocators {
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(element)}\\b`) });
    const cells = row.getByRole('cell');
    return { row, clicks: cells.nth(0), sessions: cells.nth(1) };
  }

  async rowCount(): Promise<number> {
    return this.page.getByRole('row').count().then((count) => Math.max(count - 1, 0));
  }

  async expectRow(
    element: string,
    totals: { clicks: number; sessions: number },
    timeoutMs: number,
  ): Promise<void> {
    const locators = this.rowOf(element);
    await expect(locators.clicks).toHaveText(String(totals.clicks), { timeout: timeoutMs });
    await expect(locators.sessions).toHaveText(String(totals.sessions), { timeout: timeoutMs });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
