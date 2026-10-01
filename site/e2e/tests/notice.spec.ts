// The "What we keep" section and the footer notice of the waitlist (issue
// #290): the four points, the link into the notice, the notice sentences,
// the contact link, and the quality floor (no cookie, no third-party
// request, no sideways scroll at 360 px and at 1440 px).
import { test, expect } from '@playwright/test';

test('the notice is reachable from the form and the footer', async ({ page, context }) => {
  // The request collection starts before the navigation (correction of the
  // brief review), so the list holds the page document request too.
  const requests: string[] = [];
  page.on('request', (request) => requests.push(request.url()));

  await page.goto('/');
  const origin = new URL(page.url()).origin;

  // The four points of "What we keep".
  await expect(page.locator('.points p')).toHaveText([
    'We keep the click, the page pattern, the time, and the host your visitor came from. Nothing else.',
    'Your users get a pseudonymous id and a session id. We set no cookie in your page.',
    'We never store an IP address.',
    'One call erases one user. Each click goes after 12 months.',
  ]);

  // The link into the notice moves the hash, moves the focus into the
  // footer (BLOCKER of the brief review: "footer#notice" carries
  // "tabindex=-1", so the fragment navigation focuses it with no
  // script), and the heading comes into view.
  const noticeLink = page.getByRole('link', { name: 'Read the notice of the waitlist.' });
  await expect(noticeLink).toHaveAttribute('href', '#notice');

  const footer = page.locator('footer#notice');
  const footerHeading = footer.locator('h2');

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await noticeLink.click();

  await expect(page).toHaveURL(/#notice$/);
  await expect(footer).toBeFocused();
  await expect(footerHeading).toBeInViewport();

  // The notice sentences.
  await expect(footerHeading).toHaveText('The notice of the waitlist');
  await expect(page.locator('.notice')).toContainText(
    'We use your address for the news of Octometer. We send no other mail.',
  );
  await expect(page.locator('.notice')).toContainText('7 days');
  await expect(page.locator('.notice')).toContainText(
    'Each mail holds a removal link. You can also write to the contact address.',
  );

  // The contact line is a "mailto:" link.
  const contact = page.getByRole('link', { name: 'support@0ct0meter.com' });
  await expect(contact).toHaveAttribute('href', 'mailto:support@0ct0meter.com');

  // The quality floor: no cookie, after the load and after the click.
  const cookies = await context.cookies();
  expect(cookies).toEqual([]);

  // The quality floor: no request left the page origin.
  expect(requests.length).toBeGreaterThan(0);
  for (const url of requests) {
    expect(url.startsWith(origin)).toBe(true);
  }

  // The quality floor (issue #288): no sideways scroll at 360 px and at
  // 1440 px.
  await page.setViewportSize({ width: 360, height: 800 });
  const at360 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at360.scroll).toBeLessThanOrEqual(at360.client);

  await page.setViewportSize({ width: 1440, height: 900 });
  const at1440 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at1440.scroll).toBeLessThanOrEqual(at1440.client);
});
