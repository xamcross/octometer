import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

export interface AddAppFields {
  readonly name: string;
  readonly connectionString: string;
  readonly database: string;
}

/**
 * The manage view (`monitor/frontend/src/app/manage`). `addApp` uses the
 * real form and the real "Add app" button, never a direct API call
 * (decision 4).
 */
export class ManagePage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/manage');
  }

  /**
   * Fills the add-app form and submits it. Waits for the new row in the
   * manage list, with a Playwright `expect` and a bound (correction of
   * pull request #262): the earlier version read the JSON body of the
   * POST response after Chromium had moved the page on, and Chromium
   * had by then discarded the body. This version reads no response
   * body; a test that needs the app id reads `GET /api/apps` instead.
   */
  async addApp(fields: AddAppFields): Promise<void> {
    await this.page.getByLabel('Name').fill(fields.name);
    await this.page.getByLabel('Connection string').fill(fields.connectionString);
    await this.page.getByLabel('Database').fill(fields.database);
    await this.page.getByRole('button', { name: 'Add app' }).click();
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(fields.name)}\\b`) });
    await expect(row).toBeVisible();
  }

  /** Waits for the status cell of one row to hold one of the given words (decision 5a). */
  async expectStatusOneOf(name: string, words: readonly string[], timeoutMs: number): Promise<void> {
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(name)}\\b`) });
    const status = row.getByRole('cell').first();
    await expect(status).toContainText(new RegExp(words.map(escapeRegExp).join('|')), {
      timeout: timeoutMs,
    });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
