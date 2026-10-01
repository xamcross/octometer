// The "How it works" and "Three levels in your dashboard" sections (issue
// #289): the step texts, the flow illustration, the three screenshots, and
// the scroll reveal under each motion answer and under no script.
import { test, expect } from '@playwright/test';

test('the three sections render with their images and texts, and nothing moves under reduced motion', async ({
  page,
}) => {
  // A short viewport, set before the navigation: the flow figure then
  // sits below the fold at rest, the premise of state 1 below (the page
  // at rest shows the hero; a scroll carries the flow figure into view,
  // issue #289 note 3 of the maintainer).
  await page.setViewportSize({ width: 1280, height: 480 });
  await page.goto('/');

  // The three step titles and the flow figcaption.
  await expect(page.locator('.steps h3')).toHaveText([
    'Register your app',
    'Add one script tag',
    'Open your dashboard',
  ]);
  await expect(page.locator('.flow figcaption')).toHaveText(
    'One click travels from your page to Octometer and to your dashboard. Nothing else travels.',
  );

  // The four node labels of the inline SVG.
  await expect(page.locator('.flow .nt')).toHaveText([
    'A click',
    'The tracker',
    'Octometer',
    'Your dashboard',
  ]);

  const nodes = page.locator('.node');
  // MAJOR 1 of the review of pull request #324: a loop of "nodeCount"
  // passes on zero nodes with no count assertion. This line fails the
  // scenario the moment the SVG loses its four ".node" groups.
  await expect(nodes).toHaveCount(4);
  const nodeCount = await nodes.count();

  // State 1: without reduced motion, the root carries the class "js", and
  // each node sits hidden before the scroll. This check runs before the
  // image scroll below, because a scroll to a frame further down the page
  // would carry the flow figure past the viewport first and reveal it
  // early.
  await expect(page.locator('html')).toHaveClass(/\bjs\b/);
  for (let i = 0; i < nodeCount; i++) {
    await expect(nodes.nth(i)).toHaveCSS('opacity', '0');
  }

  // State 2: a scroll to the figure reveals each node in turn, and the
  // reveal rule brings each node to full opacity (not the class alone:
  // a lost ".js .node-in" rule must fail this scenario too).
  await page.locator('figure.flow').scrollIntoViewIfNeeded();
  for (let i = 0; i < nodeCount; i++) {
    await expect(nodes.nth(i)).toHaveClass(/node-in/);
    await expect(nodes.nth(i)).toHaveCSS('opacity', '1');
  }

  // The three screenshots: a non-empty alt text, and a loaded image. Each
  // image is lazy, so the scroll comes before the "naturalWidth" read (the
  // lesson of the frontend brief on a lazy image, applied to this suite).
  const images = page.locator('.frame img');
  await expect(images).toHaveCount(3);
  const imageCount = await images.count();
  for (let i = 0; i < imageCount; i++) {
    const image = images.nth(i);
    const alt = await image.getAttribute('alt');
    expect(alt).toBeTruthy();
    await image.scrollIntoViewIfNeeded();
    await expect(async () => {
      const naturalWidth = await image.evaluate((el: HTMLImageElement) => el.naturalWidth);
      expect(naturalWidth).toBeGreaterThan(0);
    }).toPass();
  }

  // State 3: under reduced motion, a reload shows each node at once, with
  // no transition (lesson 17 of the frontend brief guards against a false
  // pass here too: the computed style, not the animation name, proves it).
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.reload();
  for (let i = 0; i < nodeCount; i++) {
    await expect(nodes.nth(i)).toHaveCSS('opacity', '1');
    await expect(nodes.nth(i)).toHaveCSS('transition-duration', '0s');
  }

  // State 4: with no "IntersectionObserver", the root carries no class
  // "js", and each node stays visible (the no-JavaScript rule).
  await page.addInitScript(() => {
    // @ts-expect-error - the test removes the global to prove the rule
    // that gates the hidden state on the class "js" alone.
    delete window.IntersectionObserver;
  });
  await page.reload();
  await expect(page.locator('html')).not.toHaveClass(/\bjs\b/);
  for (let i = 0; i < nodeCount; i++) {
    await expect(nodes.nth(i)).toHaveCSS('opacity', '1');
  }

  // The quality floor (issue #288, hero.spec.ts:70-85): no sideways scroll
  // at 360 px and at 1440 px, now that both sections render.
  await page.setViewportSize({ width: 360, height: 800 });
  const at360 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at360.scroll).toBeLessThanOrEqual(at360.client);

  // Guard of BLOCKER 1, the review of pull request #324: at 360 px the
  // phone source resolves, so a later source change away from the
  // "*-600.png" file fails this scenario.
  const firstImageSrc = await images.first().evaluate((el: HTMLImageElement) => el.currentSrc);
  expect(firstImageSrc).toContain('-600.png');

  await page.setViewportSize({ width: 1440, height: 900 });
  const at1440 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at1440.scroll).toBeLessThanOrEqual(at1440.client);
});
