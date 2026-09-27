import type { Page } from '@playwright/test';

const BUTTON_LABEL: Record<1 | 2 | 3, string> = {
  1: 'Button one',
  2: 'Button two',
  3: 'Button three',
};

/**
 * The one page of the demo app (`tools/demo-app`, `StaticPage.kt`). The
 * select box `#demo-user` sets the cookie `demo_user`; the three
 * `data-octo` elements are `demo.button-one` to `demo.button-three`.
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
