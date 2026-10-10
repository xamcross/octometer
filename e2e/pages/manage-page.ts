import type { APIRequestContext, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/** The fields of the real manage form (issue #296, D51, D52, D57): a name and one origin per line. */
export interface AddAppFields {
  readonly name: string;
  readonly origins: readonly string[];
}

/**
 * The manage view (`monitor/frontend/src/app/manage`). `addApp` uses the
 * real form and the real "Add app" button, never a direct API call
 * (decision 4). `addAppWithConnectionString` drives the legacy,
 * local-developer path of the API directly (design decision D32, issue
 * #296): the manage view sends no connection string any more, so no
 * form field exists for it; the suite still proves that path through
 * the API itself.
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
    await this.page.getByLabel('Name', { exact: true }).fill(fields.name);
    await this.page.getByLabel('Origins (one per line)').fill(fields.origins.join('\n'));
    await this.page.getByRole('button', { name: 'Add app' }).click();
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(fields.name)}\\b`) });
    await expect(row).toBeVisible();
  }

  /**
   * Fills the add-app form and submits it, for a create that the
   * registry rejects (issue #296). It waits for the error area of
   * `add-app-form.html` to hold `expectedMessage`, never for a row.
   * The name lookup is exact: once one row exists, its delete dialog
   * gives a second control whose label text ("Type the app name to
   * confirm") also holds the word "Name".
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
    await this.page.getByLabel('Origins (one per line)').fill(fields.origins.join('\n'));
    await this.page.getByRole('button', { name: 'Add app' }).click();
    await expect(this.page.locator('#add-app-error')).toContainText(expectedMessage);
  }

  /**
   * Registers an app with a connection string, through the API's own
   * legacy path (design decision D32, issue #296), never through the
   * manage form (it carries no connection string field any more). The
   * `Origin` header is the monitor's own origin (the dashboard origin
   * of D52), the rule that every API write of this suite follows.
   */
  async addAppWithConnectionString(
    request: APIRequestContext,
    monitorUrl: string,
    name: string,
    connectionString: string,
  ): Promise<void> {
    const response = await request.post('/api/apps', {
      headers: { Origin: monitorUrl },
      data: { name, connectionString },
    });
    expect(response.status(), await response.text()).toBe(201);
  }

  /**
   * The same create as [addAppWithConnectionString], for a create that
   * the registry rejects. It returns the response, so a test can read
   * the status and the fixed error text.
   */
  async addAppWithConnectionStringExpectingError(
    request: APIRequestContext,
    monitorUrl: string,
    name: string,
    connectionString: string,
  ) {
    return request.post('/api/apps', {
      headers: { Origin: monitorUrl },
      data: { name, connectionString },
    });
  }

  /**
   * Waits for the status cell of one row to hold one of the given
   * words (decision 5a). `td.status`, never the first cell (issue
   * #296 inserted the "App key" and "Origins" columns before it).
   */
  async expectStatusOneOf(name: string, words: readonly string[], timeoutMs: number): Promise<void> {
    const row = this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(name)}\\b`) });
    const status = row.locator('td.status');
    await expect(status).toContainText(new RegExp(words.map(escapeRegExp).join('|')), {
      timeout: timeoutMs,
    });
  }

  /**
   * Opens the edit form of the row named `name`, and gives that row's
   * own locator back (design decision D48, issue #277). Each other
   * method below takes that same locator, so it reads and fills the
   * open edit form of that one row, never the always-visible add-app
   * form below the table (both share the label "Name").
   */
  row(name: string) {
    return this.page.getByRole('row', { name: new RegExp(`\\b${escapeRegExp(name)}\\b`) });
  }

  async openEditForm(row: ReturnType<ManagePage['row']>): Promise<void> {
    await row.getByRole('button', { name: 'Edit app' }).click();
  }

  /**
   * The Edit app dialog of one row, open or closed (issue #364, D3).
   * Scoped to `app-edit-app-form`: the row also holds the delete dialog
   * and the new-secret-key dialog, each its own `<dialog>` element.
   */
  editDialog(row: ReturnType<ManagePage['row']>) {
    return row.locator('app-edit-app-form dialog');
  }

  /**
   * Replaces the route pattern list of the open edit form, and saves
   * it (issue #299, D5, D40). `open()` fetches `GET /api/apps/{id}`
   * asynchronously, the same race `addOriginAndSave` guards against;
   * an empty string is a valid fill (it clears the list), so this
   * waits for the origins field instead, which the same fetch fills.
   */
  async setRoutePatternsAndSave(
    row: ReturnType<ManagePage['row']>,
    patterns: readonly string[],
  ): Promise<void> {
    await expect(row.getByLabel('Origins (one per line)')).not.toHaveValue('');
    await row.getByLabel('Route patterns (one per line)').fill(patterns.join('\n'));
    await row.getByRole('button', { name: 'Save' }).click();
  }

  /** The `.form-error` text of the open edit form (issue #299). */
  editFormError(row: ReturnType<ManagePage['row']>) {
    return row.locator('.form-error');
  }

  /** The "Patterns" cell of one row of the list (issue #299, M10). */
  patternsCell(row: ReturnType<ManagePage['row']>) {
    return row.locator('td.patterns');
  }

  /** The status badge span of one row (issue #379, finding D10). */
  statusBadge(row: ReturnType<ManagePage['row']>) {
    return row.locator('td.status .badge');
  }

  /** Renames the app of the open edit form, and saves it (design decision D48). */
  async renameAndSave(row: ReturnType<ManagePage['row']>, newName: string): Promise<void> {
    await row.getByLabel('Name', { exact: true }).fill(newName);
    await row.getByRole('button', { name: 'Save' }).click();
  }

  /**
   * Adds one more origin, one per line, to the open edit form, and
   * saves it (issue #296). `open()` fetches `GET /api/apps/{id}`
   * asynchronously, so this waits for the field to hold the fetched
   * origins before it reads the current value, else a fast read here
   * can race that fetch and keep the typed origin alone.
   */
  async addOriginAndSave(row: ReturnType<ManagePage['row']>, origin: string): Promise<void> {
    const field = row.getByLabel('Origins (one per line)');
    await expect(field).not.toHaveValue('');
    const current = await field.inputValue();
    await field.fill(current.length > 0 ? `${current}\n${origin}` : origin);
    await row.getByRole('button', { name: 'Save' }).click();
  }

  /** The "App key" and "Origins" cells of one row of the list (issue #296). */
  appKeyCell(row: ReturnType<ManagePage['row']>) {
    return row.locator('td.app-key');
  }

  /** The Copy button of the app-key cell of one row (issue #386, finding D27). */
  appKeyCopyButton(row: ReturnType<ManagePage['row']>) {
    return this.appKeyCell(row).getByRole('button');
  }

  originCells(row: ReturnType<ManagePage['row']>) {
    return row.locator('td.origins div');
  }

  /** The app key panel of the add-app form, after a successful create (issue #296, D51). */
  appKeyField() {
    return this.page.locator('#add-app-key');
  }

  secretKeyField() {
    return this.page.locator('#add-app-secret-key');
  }

  scriptTag() {
    return this.page.locator('#add-app-script-tag');
  }

  /** The scroll container of the app table (issue #377, finding D9). */
  tableScroll() {
    return this.page.locator('.table-scroll');
  }

  /** The action-row div of one row's actions cell (issue #362, finding D2). */
  actionRow(row: ReturnType<ManagePage['row']>) {
    return row.locator('.action-row');
  }

  /** The heading of the add-app panel (issue #389, finding D29). */
  addAppHeading() {
    return this.page.getByRole('heading', { name: 'Add an app' });
  }

  /**
   * The "Type the app name to confirm" field of one row's delete
   * dialog (issue #393). Scoped to the dialog host of #384, so a
   * page-wide or row-wide lookup of this label never matches a field
   * of a different, closed dialog.
   */
  deleteConfirmNameField(row: ReturnType<ManagePage['row']>) {
    return this.deleteDialogHost(row).getByLabel('Type the app name to confirm');
  }

  /**
   * The `app-delete-app-dialog` host of one row. Both the trigger button
   * and the dialog of this issue sit inside it, so a scoped locator
   * never matches the Cancel button of the new-secret-key dialog next
   * to it (issue #384).
   */
  private deleteDialogHost(row: ReturnType<ManagePage['row']>) {
    return row.locator('app-delete-app-dialog');
  }

  /** Opens the delete dialog of one row (issue #384). */
  async openDeleteDialog(row: ReturnType<ManagePage['row']>): Promise<void> {
    await this.deleteDialogHost(row).locator('button.delete-trigger').click();
  }

  /** The "Delete this app" confirm button of the open delete dialog (issue #384). */
  deleteConfirmButton(row: ReturnType<ManagePage['row']>) {
    return this.deleteDialogHost(row).locator('button.confirm-delete');
  }

  /** Closes the delete dialog of one row without a delete (issue #384). */
  async cancelDeleteDialog(row: ReturnType<ManagePage['row']>): Promise<void> {
    await this.deleteDialogHost(row).locator('button.cancel').click();
  }

  /**
   * Clicks "New secret key" on the row, confirms the rotation, and
   * gives the dialog's own secret key field back (issue #296, D51).
   */
  async rotateSecretKey(row: ReturnType<ManagePage['row']>) {
    await row.getByRole('button', { name: 'New secret key' }).click();
    await row.getByRole('button', { name: 'Replace the secret key' }).click();
    const field = row.locator('#new-secret-key-value');
    await expect(field).toBeVisible();
    return field;
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
