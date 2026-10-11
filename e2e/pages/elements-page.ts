import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

export interface ElementRowLocators {
  readonly row: Locator;
  readonly clicks: Locator;
  readonly sessions: Locator;
}

/**
 * The level 3 view of one app, filtered to one user, one anonymous visitor,
 * or one session (D13, `?userId=`, `?anonymous=true`, `?sessionId=`). The
 * suite used the `userId` filter only (decision 5f) until issue #482 added
 * the anonymous-visitor caption check.
 */
export class ElementsPage {
  constructor(private readonly page: Page) {}

  async gotoByUser(appId: number, userId: string): Promise<void> {
    await this.page.goto(`/apps/${appId}/elements?userId=${encodeURIComponent(userId)}`);
  }

  /** Opens the view filtered to one session (issue #392, D18). */
  async gotoBySession(appId: number, sessionId: string): Promise<void> {
    await this.page.goto(`/apps/${appId}/elements?sessionId=${encodeURIComponent(sessionId)}`);
  }

  /** Opens the view filtered to the anonymous visitor (issue #482). */
  async gotoByAnonymous(appId: number): Promise<void> {
    await this.page.goto(`/apps/${appId}/elements?anonymous=true`);
  }

  /** The table caption element. */
  caption(): Locator {
    return this.page.locator('#elements-caption');
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

  /**
   * The gap in CSS px between the bottom of the refresh line and the top
   * of the table panel (#271). A negative value means the panel starts
   * above the refresh line, which never happens in a correct layout.
   */
  async gapAbovePanel(): Promise<number> {
    const line = await this.page.locator('app-refresh-bar').boundingBox();
    const panel = await this.page.locator('section.panel').first().boundingBox();
    if (!line || !panel) {
      throw new Error('The refresh line or the panel has no bounding box.');
    }
    return panel.y - (line.y + line.height);
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
