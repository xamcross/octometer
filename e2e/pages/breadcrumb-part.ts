import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * The breadcrumb trail of the active view (D28, `monitor/frontend/src/app/breadcrumb-nav/`,
 * issue #270).
 */
export class BreadcrumbPart {
  private readonly nav: Locator;

  constructor(page: Page) {
    this.nav = page.getByRole('navigation', { name: 'Breadcrumb' });
  }

  /** The list item locators of the trail, root first. */
  private items(): Locator {
    return this.nav.getByRole('listitem');
  }

  /**
   * Waits, up to `timeoutMs`, for the trail to hold exactly these labels,
   * root first. A direct load of a level 2 or a level 3 view starts with
   * the fallback label "App <id>": this method retries until the app name
   * arrives, so it never reads the trail before the name resolves.
   */
  async expectLabels(labels: readonly string[], timeoutMs: number): Promise<void> {
    await expect(this.items()).toHaveText([...labels], { timeout: timeoutMs });
  }

  /** The current-page item of the trail: no link, `aria-current="page"`. */
  currentItem(): Locator {
    return this.nav.locator('[aria-current="page"]');
  }

  /** The link of one crumb of the trail, by its visible name. */
  linkNamed(name: string): Locator {
    return this.nav.getByRole('link', { name });
  }
}
