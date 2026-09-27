import type { Page } from '@playwright/test';
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
    await expect(this.page.getByText('No anonymous session yet.')).toBeVisible({
      timeout: timeoutMs,
    });
  }
}
