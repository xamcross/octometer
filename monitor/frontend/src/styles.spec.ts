import { readFileSync } from 'node:fs';

/**
 * Reads the second reduced-motion media block of `src/styles.css` as
 * text, and checks that `.btn` and `.btn:active` each hold no motion
 * (#263). This second block sits after the plain `.btn` and `.btn:active`
 * rules, because a rule inside the media query needs the later source
 * position to win a tie of equal specificity.
 *
 * Vitest links the global stylesheet to `document.styleSheets` in browser
 * mode only, so this test reads the file as plain text instead (the
 * pattern of apps.spec.ts). The text starts at the comment above the
 * second `@media (prefers-reduced-motion: reduce)` block and stops
 * before the next selector, because the `@import` lines at the top of
 * the file give jsdom a parse error. The path starts at the project
 * root, the working directory of the test run.
 */
describe('the second reduced-motion block of styles.css, next to .btn', () => {
  function reducedMotionRule(selectorText: string): CSSStyleRule | undefined {
    const cssText = readFileSync('src/styles.css', 'utf8');
    const blockStartMarker = 'A second copy of the reduced-motion query';
    const blockEndMarker = "input:not([type='checkbox'])";
    const blockText = cssText.slice(
      cssText.indexOf('@media', cssText.indexOf(blockStartMarker)),
      cssText.indexOf(blockEndMarker),
    );

    const style = document.createElement('style');
    style.textContent = blockText;
    document.head.appendChild(style);
    const mediaRule = Array.from(style.sheet?.cssRules ?? [])[0] as CSSMediaRule;
    const rules = Array.from(mediaRule?.cssRules ?? []);
    document.head.removeChild(style);

    for (const rule of rules) {
      const styleRule = rule as CSSStyleRule;
      const selectorParts = styleRule.selectorText?.split(',').map((part) => part.trim()) ?? [];
      if (selectorParts.includes(selectorText)) {
        return styleRule;
      }
    }
    return undefined;
  }

  it('gives .btn a transition of none, so it does not keep the 80ms press motion', () => {
    const rule = reducedMotionRule('.btn');
    expect(rule?.style.getPropertyValue('transition')).toBe('none');
  });

  it('gives .btn:active a transform of none, and keeps the control shadow', () => {
    const rule = reducedMotionRule('.btn:active');
    expect(rule?.style.getPropertyValue('transform')).toBe('none');
    expect(rule?.style.getPropertyValue('box-shadow')).toContain('--shadow-control');
  });
});
