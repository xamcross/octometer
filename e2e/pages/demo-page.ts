import type { Page } from '@playwright/test';

const BUTTON_LABEL: Record<1 | 2 | 3, string> = {
  1: 'Button one',
  2: 'Button two',
  3: 'Button three',
};

/**
 * The pages of the demo app (`tools/demo-app`, `StaticPage.kt`). The
 * start page's select box `#demo-user` sets the cookie `demo_user`; its
 * three `data-octo` elements are `demo.button-one` to
 * `demo.button-three`. The parametrized page `/items/{id}` (design
 * decision D46, issue #275) holds one `data-octo` element,
 * `demo.item-view`.
 */
export class DemoPage {
  constructor(private readonly page: Page) {}

  /** Reads the demo URL from the state that the global setup wrote (decision 2). */
  private demoUrl(): string {
    const url = process.env.E2E_DEMO_URL;
    if (!url) {
      throw new Error('E2E_DEMO_URL is absent. The global setup did not run, or it failed.');
    }
    return url;
  }

  async goto(): Promise<void> {
    await this.page.goto(this.demoUrl() + '/');
  }

  /** Opens the parametrized item page `/items/{itemId}` (design decision D46, issue #275). */
  async gotoItem(itemId: string): Promise<void> {
    await this.page.goto(`${this.demoUrl()}/items/${itemId}`);
  }

  /** Clicks the one `data-octo` element of the item page, `demo.item-view`. */
  async clickItemView(): Promise<void> {
    await this.page.getByRole('button', { name: 'View item' }).click();
  }

  /** Selects a demo user in the select box, or `null` for "(no user)". */
  async selectUser(id: 'amy' | 'ben' | 'cleo' | null): Promise<void> {
    await this.page.getByLabel('Demo user').selectOption(id ?? '');
  }

  async clearUser(): Promise<void> {
    await this.selectUser(null);
  }

  async clickButton(button: 1 | 2 | 3): Promise<void> {
    await this.page.getByRole('button', { name: BUTTON_LABEL[button] }).click();
  }
}
