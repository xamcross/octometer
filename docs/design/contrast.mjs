/*
 * The contrast tool of issue #251. It computes the WCAG 2.x contrast ratio of
 * each pair of `docs/design/verification-2026-09-27.md`, the record of the
 * dashboard at `http://127.0.0.1:7431/`.
 *
 * Run it with `node docs/design/contrast.mjs`. It needs no dependency.
 *
 * The colour of each pair comes from `getComputedStyle` in a browser, in
 * Chromium 153.0.8010.12 through `playwright-core` 1.63.0 on 2026-09-27. A pair
 * with an `opacity` value below 1 goes through `composite` first. Keep the two
 * lists equal to the tables of the record. Add a row here when the UI adds a
 * text pair or a control edge.
 *
 * Issue #429 adds a dark row below each light row, and a site section below
 * the dashboard section (the correction round of pull request #441, MAJOR 3).
 * Each row now names a token, not a hex value (MAJOR 1 of that round): the
 * `TOKENS` map below holds the light and the dark value of each token, so a
 * row reads the right pair for its own scheme. `--ink` and `--on-bright`
 * share one light hex value but differ in the dark scheme; the old map by
 * hex value could not tell them apart.
 *
 * The dashboard carries no dark theme yet (issue #361), so its dark rows are
 * a prediction: the page label of each one ends in "(dark, prediction)". The
 * site dark rows are not a prediction; the site has shipped its dark scheme
 * since issue #366, so each one ends in "(dark)" alone.
 */

/** The linear value of one sRGB channel. */
const lin = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
};

/** The three channels of a `#rrggbb` value. */
const rgb = (hex) => {
  const h = hex.replace('#', '');
  return [
    parseInt(h.slice(0, 2), 16),
    parseInt(h.slice(2, 4), 16),
    parseInt(h.slice(4, 6), 16),
  ];
};

/** The WCAG 2.x relative luminance of a `#rrggbb` value. */
const lum = (hex) => {
  const [r, g, b] = rgb(hex);
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
};

/** The WCAG 2.x contrast ratio of two `#rrggbb` values. */
export const ratio = (a, b) => {
  const l1 = lum(a);
  const l2 = lum(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
};

/**
 * The flat colour of `front` at the opacity `alpha` over `back`. The browser
 * paints an element with an `opacity` value this way.
 */
export const composite = (front, alpha, back) => {
  const f = rgb(front);
  const b = rgb(back);
  const mix = f.map((v, i) => Math.round(v * alpha + b[i] * (1 - alpha)));
  return '#' + mix.map((v) => v.toString(16).padStart(2, '0')).join('');
};

/**
 * Each token of the cartoon look, light hex and dark hex (issue #429,
 * MAJOR 1 of the correction round of pull request #441). A bright fill
 * token (`coral`, `sunny`, `seaweed`, `octo`, `onBright`) keeps one value
 * in both schemes (`site/src/styles/tokens.css`, `monitor/frontend/src/
 * styles.css`). `bandInk` and `bandPaper` hold the light value in both
 * schemes too: the dashboard's own `.band` rule
 * (`monitor/frontend/src/app/app.css:51-57`) redefines `--ink` and
 * `--paper` to their light value inside the band, "so the text and the
 * mark stay dark on the octo fill", in the dark scheme too. `focusHalo`
 * is the site's own token (`site/src/styles/tokens.css`), not the
 * dashboard's: the dashboard's own halo reads plain `--ink`
 * (`monitor/frontend/src/styles.css:132-135`), already correct per
 * scheme, so it uses the `ink` token below, not a dashboard `focusHalo`.
 */
const TOKENS = {
  ink: { light: '#241f3d', dark: '#f4f1ff' },
  inkSoft: { light: '#4b4763', dark: '#b3add4' },
  paper: { light: '#ffffff', dark: '#241f3d' },
  shallows: { light: '#e6f3ff', dark: '#17142b' },
  shallowsDeep: { light: '#bfdfff', dark: '#232b45' },
  coral: { light: '#ff6b6b', dark: '#ff6b6b' },
  sunny: { light: '#ffd93d', dark: '#ffd93d' },
  seaweed: { light: '#6ee7b7', dark: '#6ee7b7' },
  octo: { light: '#a78bfa', dark: '#a78bfa' },
  onBright: { light: '#241f3d', dark: '#241f3d' },
  bandInk: { light: '#241f3d', dark: '#241f3d' },
  bandPaper: { light: '#ffffff', dark: '#ffffff' },
  linkUnderline: { light: '#d93f3f', dark: '#ff6b6b' },
  focusRing: { light: '#ffd93d', dark: '#ffd93d' },
  focusHalo: { light: '#241f3d', dark: '#f4f1ff' },
};

/** The hex value of a token, in one scheme ('light' or 'dark'). */
const hex = (token, scheme) => TOKENS[token][scheme];

// The disabled confirm button of a dashboard dialog carries `opacity: 0.55`
// over its coral fill (the composite of issue #251). No opacity rule backs
// this today (`monitor/frontend/src/styles.css:341-344` uses a solid
// `--shallows` fill and `--ink-soft` text instead), so the row is already
// stale in the light scheme; it stays, unchanged, out of the scope of this
// correction round. Its dark row keeps the same composite form, with each
// field read from `TOKENS` for the dark scheme.
const disabledFill = (scheme) => composite(hex('coral', scheme), 0.55, hex('paper', scheme));
const disabledInk = (scheme) => composite(hex('ink', scheme), 0.55, disabledFill(scheme));

// [the page, the element kind, the text token, the background token, the px, the weight]
const DASHBOARD_TEXT = [
  ['each page', 'the skip link on focus', 'onBright', 'sunny', 16, 400],
  ['each page', 'the wordmark on the band', 'bandInk', 'octo', 25, 600],
  ['each page', 'the current nav pill', 'onBright', 'sunny', 16, 600],
  ['each page', 'the other nav pill', 'bandInk', 'bandPaper', 16, 600],
  ['each page', 'the breadcrumb link and text', 'ink', 'shallows', 16, 400],
  ['each page', 'the breadcrumb current item', 'ink', 'shallows', 16, 700],
  ['each page', 'the page title h1', 'ink', 'shallows', 25, 600],
  ['each data page', 'the refresh note', 'inkSoft', 'shallows', 12.8, 400],
  ['apps', 'the retention note', 'ink', 'shallows', 16, 400],
  ['each data page', 'the pause pill', 'ink', 'paper', 16, 600],
  ['each table', 'the panel caption', 'inkSoft', 'paper', 12.8, 400],
  ['manage', 'the panel caption (bold)', 'inkSoft', 'paper', 12.8, 700],
  ['each table', 'the table head cell', 'ink', 'shallowsDeep', 16, 600],
  ['each table', 'the cell on the paper row', 'ink', 'paper', 16, 400],
  ['each table', 'the row header on the paper row', 'ink', 'paper', 16, 700],
  ['each table', 'the link in the paper row', 'ink', 'paper', 16, 400],
  ['each table', 'the cell on the zebra row', 'ink', 'shallows', 16, 400],
  ['each table', 'the link in the zebra row', 'ink', 'shallows', 16, 700],
  ['each table', 'the time cell', 'ink', 'paper', 16, 400],
  ['apps', 'the badge OK', 'onBright', 'seaweed', 12.8, 700],
  ['apps', 'the badge warn (sunny)', 'onBright', 'sunny', 12.8, 700],
  ['apps', 'the badge bad (coral)', 'onBright', 'coral', 12.8, 700],
  ['apps', 'the badge not polled', 'ink', 'shallowsDeep', 12.8, 700],
  ['users', 'the filter label', 'ink', 'shallows', 16, 400],
  ['users', 'the filter button', 'ink', 'paper', 12.8, 600],
  ['users', 'the disabled pager button', 'inkSoft', 'shallows', 12.8, 600],
  ['users, sessions, not-found', 'the empty state sentence', 'ink', 'paper', 16, 400],
  ['sessions', 'the first-load sentence', 'ink', 'shallows', 16, 400],
  ['manage', 'the Edit and Delete buttons', 'ink', 'paper', 12.8, 600],
  ['manage', 'the form heading h2', 'ink', 'paper', 20, 600],
  ['manage', 'the field label', 'ink', 'paper', 16, 700],
  ['manage', 'the field hint', 'inkSoft', 'paper', 12.8, 400],
  ['manage', 'the hint example code', 'inkSoft', 'paper', 12.8, 400],
  ['manage', 'the form error text', 'ink', 'paper', 16, 700],
  ['manage', 'the primary button', 'onBright', 'sunny', 16, 600],
  ['manage', 'the status cell', 'ink', 'paper', 16, 400],
  ['dialog', 'the dialog heading h2', 'ink', 'paper', 20, 600],
  ['dialog', 'the dialog sentence', 'ink', 'paper', 16, 400],
  ['dialog', 'the dialog strong text', 'ink', 'paper', 16, 700],
  ['dialog', 'the Cancel button', 'ink', 'paper', 16, 600],
];

// [the page, the edge or the ring, the colour token, the neighbour token]
const DASHBOARD_NONTEXT = [
  ['each page', 'the control edge on the page background', 'ink', 'shallows'],
  ['each page', 'the control edge on the paper', 'ink', 'paper'],
  ['the band', 'the nav pill edge on the octo band', 'bandInk', 'octo'],
  ['each page', 'the panel edge on the page background', 'ink', 'shallows'],
  ['each table', 'the head cell edge on the head fill', 'ink', 'shallowsDeep'],
  ['apps', 'the badge edge on the badge fill (seaweed)', 'onBright', 'seaweed'],
  ['apps', 'the badge edge on the badge fill (coral)', 'onBright', 'coral'],
  ['apps', 'the badge edge on the badge fill (sunny)', 'onBright', 'sunny'],
  ['each page', 'the focus ring: the ink halo on the page background', 'ink', 'shallows'],
  ['each page', 'the focus ring: the ink halo on the paper', 'ink', 'paper'],
  ['the band', 'the focus ring: the ink halo on the octo band', 'bandInk', 'octo'],
  ['each page', 'the focus ring: the sunny line on the ink halo', 'sunny', 'ink'],
  ['users', 'the disabled pager edge on the page background', 'inkSoft', 'shallows'],
  ['manage', 'the invalid field ring on the field fill', 'coral', 'paper'],
];

/*
 * The site section (issue #429, MAJOR 3 of the correction round of pull
 * request #441): one row for each text-on-fill pair and each edge of
 * `site/src/styles/site.css`, so the site itself has cover, not only the
 * dashboard record. The site's dark scheme is live (issue #366), so its
 * dark row carries no "prediction" label.
 */
const SITE_TEXT = [
  ['topbar', 'the "How it works" and "Privacy" links', 'ink', 'paper', 16, 400],
  ['hero preview', 'the preview bar brand text on the octo band', 'onBright', 'octo', 16, 600],
  ['hero preview', 'the inactive tab text', 'ink', 'paper', 14, 600],
  ['hero preview', 'the active tab text', 'onBright', 'sunny', 14, 600],
  ['hero preview', 'the table head cell', 'ink', 'shallowsDeep', 14, 700],
  ['hero preview', 'the table cell', 'ink', 'paper', 14, 400],
  ['hero preview', 'the status OK badge', 'onBright', 'seaweed', 14, 700],
  ['hero preview', 'the status slow badge (sunny)', 'onBright', 'sunny', 14, 700],
  ['hero preview', 'the caption', 'inkSoft', 'paper', 14, 400],
  ['steps', 'the step number marker', 'onBright', 'sunny', 16, 600],
  ['waitlist status', 'the ok status text', 'onBright', 'seaweed', 16, 700],
  ['waitlist status', 'the bad status text', 'onBright', 'coral', 16, 700],
];

const SITE_NONTEXT = [
  ['hero preview', 'the preview window edge on the page background', 'ink', 'shallows'],
  ['hero preview', 'the inactive tab edge on its own paper fill', 'ink', 'paper'],
  ['hero preview', 'the active tab edge on the sunny fill', 'onBright', 'sunny'],
  ['hero preview', 'the status OK edge on the seaweed fill', 'onBright', 'seaweed'],
  ['hero preview', 'the status slow edge on the sunny fill', 'onBright', 'sunny'],
  ['steps', 'the step marker edge on the sunny fill', 'onBright', 'sunny'],
  ['waitlist status', 'the ok edge on the seaweed fill', 'onBright', 'seaweed'],
  ['waitlist status', 'the bad edge on the coral fill', 'onBright', 'coral'],
  ['the panel', 'the panel edge on the page background', 'ink', 'shallows'],
  ['the panel', 'the panel edge on the paper', 'ink', 'paper'],
  ['topbar', 'the link underline on the paper', 'linkUnderline', 'paper'],
  ['topbar', 'the link underline on the page background', 'linkUnderline', 'shallows'],
  ['each page', 'the focus ring: the line on the halo', 'focusRing', 'focusHalo'],
  ['each page', 'the focus ring: the halo on the page background', 'focusHalo', 'shallows'],
  ['each page', 'the focus ring: the halo on the paper', 'focusHalo', 'paper'],
  ['the band', 'the focus ring: the halo on the octo band', 'focusHalo', 'octo'],
];

/** True for a large text: 24 px, or 18.66 px at the weight 700. */
const big = (px, weight) => px >= 24 || (px >= 18.66 && weight >= 700);

const f1 = (x) => (Math.round(x * 10) / 10).toFixed(1);

let fails = 0;
let pairs = 0;

/** Prints one TEXT table, in one scheme, with one page-label suffix. */
function printText(rows, scheme, suffix) {
  console.log('| Page | Text | Colour | Background | Ratio | Floor | Result |');
  console.log('| --- | --- | --- | --- | --- | --- | --- |');
  for (const [page, kind, fgToken, bgToken, px, weight] of rows) {
    const fg = hex(fgToken, scheme);
    const bg = hex(bgToken, scheme);
    const r = ratio(fg, bg);
    const floor = big(px, weight) ? 3 : 4.5;
    const ok = r >= floor;
    pairs += 1;
    if (!ok) fails += 1;
    console.log(
      `| ${page}${suffix} | ${kind} (${px} px ${weight}) | \`${fg}\` | \`${bg}\` | ${f1(r)}:1 | ${floor}:1 | ${ok ? 'pass' : 'fail'} |`,
    );
  }
}

/** Prints one NONTEXT table, in one scheme, with one page-label suffix. */
function printNontext(rows, scheme, suffix) {
  console.log('| Page | Edge or ring | Colour | Neighbour | Ratio | Floor | Result |');
  console.log('| --- | --- | --- | --- | --- | --- | --- |');
  for (const [page, kind, aToken, bToken] of rows) {
    const a = hex(aToken, scheme);
    const b = hex(bToken, scheme);
    const r = ratio(a, b);
    const ok = r >= 3;
    pairs += 1;
    if (!ok) fails += 1;
    console.log(`| ${page}${suffix} | ${kind} | \`${a}\` | \`${b}\` | ${f1(r)}:1 | 3:1 | ${ok ? 'pass' : 'fail'} |`);
  }
}

console.log('The composite of the disabled confirm button: ' + disabledInk('light') + ' on ' + disabledFill('light'));
console.log('');
console.log('## The dashboard, the light scheme');
console.log('');
printText(DASHBOARD_TEXT, 'light', '');
console.log('');
printNontext(DASHBOARD_NONTEXT, 'light', '');
// The disabled confirm button, unchanged, out of the scope of this round.
{
  const fg = disabledInk('light');
  const bg = disabledFill('light');
  const r = ratio(fg, bg);
  const ok = r >= 4.5;
  pairs += 1;
  if (!ok) fails += 1;
  console.log(
    `| dialog | the disabled confirm button (opacity 0.55) (16 px 600) | \`${fg}\` | \`${bg}\` | ${f1(r)}:1 | 4.5:1 | ${ok ? 'pass' : 'fail'} |`,
  );
}
console.log('');
console.log('## The dashboard, the dark scheme (a prediction: issue #361 has not shipped it)');
console.log('');
printText(DASHBOARD_TEXT, 'dark', ' (dark, prediction)');
console.log('');
printNontext(DASHBOARD_NONTEXT, 'dark', ' (dark, prediction)');
{
  const fg = disabledInk('dark');
  const bg = disabledFill('dark');
  const r = ratio(fg, bg);
  const ok = r >= 4.5;
  pairs += 1;
  if (!ok) fails += 1;
  console.log(
    `| dialog (dark, prediction) | the disabled confirm button (opacity 0.55) (16 px 600) | \`${fg}\` | \`${bg}\` | ${f1(r)}:1 | 4.5:1 | ${ok ? 'pass' : 'fail'} |`,
  );
}
console.log('');
console.log('## The site, the light scheme');
console.log('');
printText(SITE_TEXT, 'light', '');
console.log('');
printNontext(SITE_NONTEXT, 'light', '');
console.log('');
console.log('## The site, the dark scheme (live: issue #366)');
console.log('');
printText(SITE_TEXT, 'dark', ' (dark)');
console.log('');
printNontext(SITE_NONTEXT, 'dark', ' (dark)');
console.log('');
console.log('pairs=' + pairs + ' fails=' + fails);
