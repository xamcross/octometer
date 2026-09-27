import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * Issue #272: the form has no "Database" field any more. The path of
 * the connection string names the database.
 */
export interface AddAppFields {
  readonly name: string;
  readonly connectionString: string;
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
    await this.page.getByRole('button', { name: 'Add app' }).click();
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(fields.name)}\\b`) });
    await expect(row).toBeVisible();
  }

  /**
   * Fills the add-app form and submits it, for a create that the
   * registry rejects (issue #272, design decision D13). It waits for
   * the error area of `add-app-form.html` to hold `expectedMessage`,
   * never for a row. The name lookup is exact: once one row exists,
   * its delete dialog gives a second control whose label text ("Type
   * the app name to confirm") also holds the word "Name".
   *
   * A wait on `toBeVisible()` alone is not enough: the error area of a
   * former rejected submit can still sit in the DOM the instant after
   * the new click, before Angular clears and re-fills it, and
   * `toBeVisible()` then resolves on that stale text at once. The
   * assertion below names the expected text, so Playwright retries
   * until that exact text shows, not the first visible text.
   */
  async addAppExpectingError(fields: AddAppFields, expectedMessage: string): Promise<void> {
    await this.page.getByLabel('Name', { exact: true }).fill(fields.name);
    await this.page.getByLabel('Connection string').fill(fields.connectionString);
    await this.page.getByRole('button', { name: 'Add app' }).click();
    await expect(this.page.locator('#add-app-error')).toContainText(expectedMessage);
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
