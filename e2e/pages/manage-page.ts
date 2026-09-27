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

  /** Fills the add-app form and submits it. Returns the created app id (D13, `AppSummary`). */
  async addApp(fields: AddAppFields): Promise<number> {
    await this.page.getByLabel('Name').fill(fields.name);
    await this.page.getByLabel('Connection string').fill(fields.connectionString);
    await this.page.getByLabel('Database').fill(fields.database);
    const [response] = await Promise.all([
      this.page.waitForResponse(
        (candidate) => candidate.url().endsWith('/api/apps') && candidate.request().method() === 'POST',
      ),
      this.page.getByRole('button', { name: 'Add app' }).click(),
    ]);
    const body = (await response.json()) as { appId: number };
    return body.appId;
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
