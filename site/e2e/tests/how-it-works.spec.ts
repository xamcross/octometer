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

  // Issue #355: at 1280 px, the gap between the right edge of the step
  // paragraph text and the left edge of the flow diagram must not pass
  // 56 px (the issue's 48 px bound plus the maintainer's 8 px
  // tolerance, the review of pull request #401). A `Range` over each
  // step paragraph gives the right edge of the rendered text, not the
  // grid column box, so a change of the column gap alone cannot pass
  // this assertion (BLOCKER 2 of the review).
  const stepTextRight = await page.locator('.steps').evaluate((steps) => {
    let maxRight = 0;
    for (const p of steps.querySelectorAll('p')) {
      const range = document.createRange();
      range.selectNodeContents(p);
      for (const rect of Array.from(range.getClientRects())) {
        if (rect.right > maxRight) {
          maxRight = rect.right;
        }
      }
    }
    return maxRight;
  });
  const diagramBox = await page.locator('figure.flow svg').boundingBox();
  if (!diagramBox) {
    throw new Error('the flow svg has no box');
  }
  const textToDiagramGap = diagramBox.x - stepTextRight;
  console.log(`issue #355 text-to-diagram gap at 1280 px: ${textToDiagramGap}px`);
  expect(textToDiagramGap).toBeLessThanOrEqual(56);

  // No sideways scroll at 1280 px, the width of the measurement above.
  const at1280 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at1280.scroll).toBeLessThanOrEqual(at1280.client);

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
  //
  // Issue #343: the old images kept one fixed, tall ratio (height /
  // width = 1600 / 2400, about 0.667) below each short table, with an
  // empty area below it.
  //
  // Issue #359: one shared bound no longer fits every view, now that
  // the apps view captures at a wider viewport (about 920 px, the full
  // width of its own seven-column table) than the users view and the
  // elements view (about 720 px each), in place of 1200 px for every
  // view before. A narrower capture shrinks the width more than the
  // height, so each tightly cropped image now gives a higher ratio on
  // its own: apps about 0.54, users about 0.96, elements about 0.68
  // (the pull request text states each number). Each bound below still
  // passes its own cropped image and still fails an uncropped shot of
  // the same capture viewport (height 800 over the capture width: about
  // 0.87 for apps, about 1.11 for users and for elements).
  const MAX_HEIGHT_TO_WIDTH_RATIO = [0.7, 1.05, 0.8];
  const images = page.locator('.frame img');
  await expect(images).toHaveCount(3);
  const imageCount = await images.count();
  for (let i = 0; i < imageCount; i++) {
    const image = images.nth(i);
    const alt = await image.getAttribute('alt');
    expect(alt).toBeTruthy();
    await image.scrollIntoViewIfNeeded();
    await expect(async () => {
      const naturalSize = await image.evaluate((el: HTMLImageElement) => ({
        width: el.naturalWidth,
        height: el.naturalHeight,
      }));
      expect(naturalSize.width).toBeGreaterThan(0);
      expect(naturalSize.height / naturalSize.width).toBeLessThan(MAX_HEIGHT_TO_WIDTH_RATIO[i]);
    }).toPass();
  }

  // Issue #359 (the UI audit finding L1): the rendered width of the
  // two-column cards against the viewport of 1280 px. Site.css keeps
  // ".levels" at a floor of 480 px of image width per card (a 32rem
  // grid track minus the frame padding) until the row drops to one
  // column; below 1280 px this is the first width where two columns
  // fit two 480 px cards plus the row gap.
  const usersImageWidthAt1280 = (await images.nth(1).boundingBox())!.width;
  const elementsImageWidthAt1280 = (await images.nth(2).boundingBox())!.width;
  console.log(
    `issue #359 image width at 1280 px: users ${usersImageWidthAt1280}px, ` +
      `elements ${elementsImageWidthAt1280}px`,
  );
  expect(usersImageWidthAt1280).toBeGreaterThanOrEqual(480);
  expect(elementsImageWidthAt1280).toBeGreaterThanOrEqual(480);

  // Issue #359: the apps card spans both columns of ".levels", so at
  // 1280 px it is wider than its own capture (about 920 px declared).
  // "width: 100%" (site.css, in place of "max-width: 100%") fills the
  // card; the image keeps no blank strip beside a capture that stopped
  // at its own declared size.
  const appsWidths = await images.nth(0).evaluate((el: HTMLImageElement) => ({
    image: el.getBoundingClientRect().width,
    button: (el.closest('.frame-open') as HTMLElement).getBoundingClientRect().width,
  }));
  console.log(
    `issue #359 apps image width at 1280 px: ${appsWidths.image}px of its card's ` +
      `${appsWidths.button}px`,
  );
  expect(Math.abs(appsWidths.image - appsWidths.button)).toBeLessThan(1);

  // BLOCKER 1 of the review of pull request #399, issue #382. At 1280 px
  // the users card and the elements card share one grid row. Criterion 1
  // asks for a small gap below each image. Criterion 2 asks for one
  // shared bottom edge of the two figcaption boxes. The fix adds
  // "grid-template-rows: auto 1fr auto" to ".frame". The picture row then
  // takes the leftover height of the shorter card. The rule
  // ".frame picture { align-self: end }" pins the image to the bottom of
  // that row. The free space opens between the heading and the image,
  // not below the caption (the break proof of the pull request text
  // states each measured number).
  const frames = page.locator('.frame');
  await expect(frames).toHaveCount(3);
  const usersFrame = frames.nth(1);
  const elementsFrame = frames.nth(2);
  const measure = (frame: Element) => {
    const image = frame.querySelector('img') as HTMLElement;
    const caption = frame.querySelector('figcaption') as HTMLElement;
    return {
      gap: caption.getBoundingClientRect().top - image.getBoundingClientRect().bottom,
      captionBottom: caption.getBoundingClientRect().bottom,
    };
  };
  const usersMeasure = await usersFrame.evaluate(measure);
  const elementsMeasure = await elementsFrame.evaluate(measure);
  // Criterion 1: the gap of each card stays near the design gap token
  // (--space-3, 12 px), at most 16 px.
  expect(usersMeasure.gap).toBeLessThan(16);
  expect(elementsMeasure.gap).toBeLessThan(16);
  // Criterion 2: the two figcaption boxes share one bottom edge, within
  // 1 px.
  expect(Math.abs(elementsMeasure.captionBottom - usersMeasure.captionBottom)).toBeLessThan(1);

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

  // The sub-label size (issue #344): no sub-label of the flow figure sits
  // below the 12 px floor, and the longest one, "app.0ct0meter.com",
  // still renders on one line at a 390 px viewport. The old rule set
  // this text to 11.5 px, so both checks below fail against it (the
  // break proof of the pull request text).
  await page.setViewportSize({ width: 390, height: 800 });
  const subLabels = page.locator('.flow .ns');
  await expect(subLabels).toHaveCount(4);
  const subLabelFontSize = await subLabels
    .first()
    .evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
  expect(subLabelFontSize).toBeGreaterThanOrEqual(12);

  // A bounding box of one line sits near the single-line height of the
  // new size (about 18 px); the old, smaller size gives about 16 px, and
  // a wrap to a second line would give about double. The range below
  // accepts the new size and rejects both.
  const longestLabelBox = await subLabels.nth(3).boundingBox();
  expect(longestLabelBox).not.toBeNull();
  expect(longestLabelBox!.height).toBeGreaterThanOrEqual(17);
  expect(longestLabelBox!.height).toBeLessThan(26);

  // No sideways scroll at 390 px, the width of the sub-label check above.
  const at390 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at390.scroll).toBeLessThanOrEqual(at390.client);

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
  // "*-600.png" file fails this scenario. The browser re-runs the
  // "<picture>" source selection one paint after the resize, so this
  // read polls, not a single read (a CI run of pull request #324 found
  // the race).
  await expect
    .poll(() => images.first().evaluate((el: HTMLImageElement) => el.currentSrc))
    .toContain('-600.png');

  await page.setViewportSize({ width: 1440, height: 900 });
  const at1440 = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(at1440.scroll).toBeLessThanOrEqual(at1440.client);
});

// The full-size view of a dashboard screenshot (issue #359, the UI audit
// finding L1, acceptance criterion 2). A click opens the dialog with the
// full capture; the close button and the Escape key each close it.
test('a click on a card opens the screenshot at full size, and each control closes it', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/');

  const openButton = page.locator('.frame-open').first();
  const fullSrc = await openButton.getAttribute('data-full-src');
  expect(fullSrc).toBeTruthy();
  const dialog = page.locator('#screenshot-lightbox');
  const lightboxImage = page.locator('#lightbox-img');

  // Closed at rest. The "toPass" guard of the frontend brief (lesson 18)
  // does not apply here: "toHaveJSProperty" polls on its own.
  await expect(dialog).toHaveJSProperty('open', false);

  // A click opens the dialog with the same capture as the card (the
  // full-size variant of the apps view, the "data-full-src" of its
  // button, not a thumbnail of a different view).
  await openButton.click();
  await expect(dialog).toHaveJSProperty('open', true);
  await expect(lightboxImage).toHaveAttribute('src', fullSrc!);
  const naturalWidth = await lightboxImage.evaluate((el: HTMLImageElement) => el.naturalWidth);
  expect(naturalWidth).toBeGreaterThan(0);

  // The Escape key closes the dialog (the native behaviour of "dialog",
  // issue #359 step 5).
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveJSProperty('open', false);

  // The close button closes the dialog too.
  await openButton.click();
  await expect(dialog).toHaveJSProperty('open', true);
  await page.locator('.lightbox-close').click();
  await expect(dialog).toHaveJSProperty('open', false);

  // A click on the backdrop, outside the image, closes the dialog; a
  // click on the image itself does not (lesson 22 of the frontend brief:
  // a loop over a located list proves nothing on an empty list, so the
  // count assertion above on the located buttons still stands as the
  // guard for this click too).
  await openButton.click();
  await expect(dialog).toHaveJSProperty('open', true);
  await lightboxImage.click();
  await expect(dialog).toHaveJSProperty('open', true);
  await dialog.click({ position: { x: 5, y: 5 } });
  await expect(dialog).toHaveJSProperty('open', false);
});
