import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The first-pages view of one app (`monitor/frontend/src/app/first-pages`).
 * It shows one row for each stored first page, with its session count
 * (`first-pages.html` lines 18 and 31: the "First page" column and the
 * `{{ row.path }}` link text).
 */
export class FirstPagesPage {
  constructor(private readonly page: Page) {}

  async goto(appId: number): Promise<void> {
    await this.page.goto(`/apps/${appId}/first-pages`);
  }

  /**
   * The sessions cell of the row of one stored path, for example `/` or
   * `/other`. The "First page" column is a `<th scope="row">` (ARIA role
   * `rowheader`), not a `cell`; only the "Sessions" column has role
   * `cell`, so this is the first (and only) match of that role.
   */
  private sessionsCellOf(path: string): Locator {
    const link = this.page.getByRole('link', { name: path, exact: true });
    const row = link.locator('xpath=ancestor::tr[1]');
    return row.getByRole('cell').first();
  }

  /** Waits, up to `timeoutMs`, for the session count of one stored path. */
  async expectSessions(path: string, sessions: number, timeoutMs: number): Promise<void> {
    await expect(this.sessionsCellOf(path)).toHaveText(String(sessions), { timeout: timeoutMs });
  }
}
