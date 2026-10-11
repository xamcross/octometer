import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The anonymous session view of one app (`monitor/frontend/src/app/sessions`).
 * This suite uses it only for the negative proof of decision 5b: an
 * anonymous click, with anonymous events off (D19), gives no session row.
 */
export class SessionsPage {
  constructor(private readonly page: Page) {}

  async goto(appId: number): Promise<void> {
    await this.page.goto(`/apps/${appId}/sessions?anonymous=true`);
  }

  async expectNoSessions(timeoutMs: number): Promise<void> {
    await expect(this.page.getByText('No sessions yet.')).toBeVisible({
      timeout: timeoutMs,
    });
  }

  /** The table caption element (issue #482: the sessions view names "session", not "anonymous session"). */
  caption(): Locator {
    return this.page.locator('#sessions-caption');
  }
}
