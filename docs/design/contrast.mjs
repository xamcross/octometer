/*
 * The contrast tool of issue #251. It computes the WCAG 2.x contrast ratio of
 * each pair of `docs/design/verification-2026-09-27.md`.
 *
 * Run it with `node docs/design/contrast.mjs`. It needs no dependency.
 *
 * The colour of each pair comes from `getComputedStyle` in a browser, in
 * Chromium 153.0.8010.12 through `playwright-core` 1.63.0 on 2026-09-27. A pair
 * with an `opacity` value below 1 goes through `composite` first. Keep the two
 * lists equal to the tables of the record. Add a row here when the UI adds a
 * text pair or a control edge.
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

// The disabled confirm button of the dialog carries `opacity: 0.55`.
const COetc = { alpha: 0.55, coral: '#ff6b6b', ink: '#241f3d', paper: '#ffffff' };
const DISABLED_FILL = composite(COetc.coral, COetc.alpha, COetc.paper);
const DISABLED_INK = composite(COetc.ink, COetc.alpha, DISABLED_FILL);

// [the page, the element kind, the text colour, the background, the px, the weight]
const TEXT = [
  ['each page', 'the skip link on focus', '#241f3d', '#ffd93d', 16, 400],
  ['each page', 'the wordmark on the band', '#241f3d', '#a78bfa', 25, 600],
  ['each page', 'the current nav pill', '#241f3d', '#ffd93d', 16, 600],
  ['each page', 'the other nav pill', '#241f3d', '#ffffff', 16, 600],
  ['each page', 'the breadcrumb link and text', '#241f3d', '#e6f3ff', 16, 400],
  ['each page', 'the breadcrumb current item', '#241f3d', '#e6f3ff', 16, 700],
  ['each page', 'the page title h1', '#241f3d', '#e6f3ff', 25, 600],
  ['each data page', 'the refresh note', '#4b4763', '#e6f3ff', 12.8, 400],
  ['apps', 'the retention note', '#241f3d', '#e6f3ff', 16, 400],
  ['each data page', 'the pause pill', '#241f3d', '#ffffff', 16, 600],
  ['each table', 'the panel caption', '#4b4763', '#ffffff', 12.8, 400],
  ['manage', 'the panel caption (bold)', '#4b4763', '#ffffff', 12.8, 700],
  ['each table', 'the table head cell', '#241f3d', '#bfdfff', 16, 600],
  ['each table', 'the cell on the paper row', '#241f3d', '#ffffff', 16, 400],
  ['each table', 'the row header on the paper row', '#241f3d', '#ffffff', 16, 700],
  ['each table', 'the link in the paper row', '#241f3d', '#ffffff', 16, 400],
  ['each table', 'the cell on the zebra row', '#241f3d', '#e6f3ff', 16, 400],
  ['each table', 'the link in the zebra row', '#241f3d', '#e6f3ff', 16, 700],
  ['each table', 'the time cell', '#241f3d', '#ffffff', 16, 400],
  ['apps', 'the badge OK', '#241f3d', '#6ee7b7', 12.8, 700],
  ['apps', 'the badge warn (sunny)', '#241f3d', '#ffd93d', 12.8, 700],
  ['apps', 'the badge bad (coral)', '#241f3d', '#ff6b6b', 12.8, 700],
  ['apps', 'the badge not polled', '#241f3d', '#bfdfff', 12.8, 700],
  ['users', 'the filter label', '#241f3d', '#e6f3ff', 16, 400],
  ['users', 'the filter button', '#241f3d', '#ffffff', 12.8, 600],
  ['users', 'the disabled pager button', '#4b4763', '#e6f3ff', 12.8, 600],
  ['users, sessions, not-found', 'the empty state sentence', '#241f3d', '#ffffff', 16, 400],
  ['sessions', 'the first-load sentence', '#241f3d', '#e6f3ff', 16, 400],
  ['manage', 'the Edit and Delete buttons', '#241f3d', '#ffffff', 12.8, 600],
  ['manage', 'the form heading h2', '#241f3d', '#ffffff', 20, 600],
  ['manage', 'the field label', '#241f3d', '#ffffff', 16, 700],
  ['manage', 'the field hint', '#4b4763', '#ffffff', 12.8, 400],
  ['manage', 'the hint example code', '#4b4763', '#ffffff', 12.8, 400],
  ['manage', 'the form error text', '#241f3d', '#ffffff', 16, 700],
  ['manage', 'the primary button', '#241f3d', '#ffd93d', 16, 600],
  ['manage', 'the status cell', '#241f3d', '#ffffff', 16, 400],
  ['dialog', 'the dialog heading h2', '#241f3d', '#ffffff', 20, 600],
  ['dialog', 'the dialog sentence', '#241f3d', '#ffffff', 16, 400],
  ['dialog', 'the dialog strong text', '#241f3d', '#ffffff', 16, 700],
  ['dialog', 'the Cancel button', '#241f3d', '#ffffff', 16, 600],
  ['dialog', 'the disabled confirm button (opacity 0.55)', DISABLED_INK, DISABLED_FILL, 16, 600],
];

// [the page, the edge or the ring, the colour, the neighbour]
const NONTEXT = [
  ['each page', 'the control edge on the page background', '#241f3d', '#e6f3ff'],
  ['each page', 'the control edge on the paper', '#241f3d', '#ffffff'],
  ['the band', 'the nav pill edge on the octo band', '#241f3d', '#a78bfa'],
  ['each page', 'the panel edge on the page background', '#241f3d', '#e6f3ff'],
  ['each table', 'the head cell edge on the head fill', '#241f3d', '#bfdfff'],
  ['apps', 'the badge edge on the badge fill (seaweed)', '#241f3d', '#6ee7b7'],
  ['apps', 'the badge edge on the badge fill (coral)', '#241f3d', '#ff6b6b'],
  ['apps', 'the badge edge on the badge fill (sunny)', '#241f3d', '#ffd93d'],
  ['each page', 'the focus ring: the ink halo on the page background', '#241f3d', '#e6f3ff'],
  ['each page', 'the focus ring: the ink halo on the paper', '#241f3d', '#ffffff'],
  ['the band', 'the focus ring: the ink halo on the octo band', '#241f3d', '#a78bfa'],
  ['each page', 'the focus ring: the sunny line on the ink halo', '#ffd93d', '#241f3d'],
  ['users', 'the disabled pager edge on the page background', '#4b4763', '#e6f3ff'],
  // Issue #438: the ring rule of styles.css and the five component style
  // files already paints `var(--coral-deep)` (#264), but this pair still
  // carried the pre-#264 literal `#ff6b6b` (2.8:1, below the 3:1 floor of
  // WCAG 1.4.11). The pair now names the real token value, `#d93a3a`.
  ['manage', 'the invalid field ring on the field fill', '#d93a3a', '#ffffff'],
];

/** True for a large text: 24 px, or 18.66 px at the weight 700. */
const big = (px, weight) => px >= 24 || (px >= 18.66 && weight >= 700);

const f1 = (x) => (Math.round(x * 10) / 10).toFixed(1);

let fails = 0;
console.log('The composite of the disabled confirm button: ' + DISABLED_INK + ' on ' + DISABLED_FILL);
console.log('');
console.log('| Page | Text | Colour | Background | Ratio | Floor | Result |');
console.log('| --- | --- | --- | --- | --- | --- | --- |');
for (const [page, kind, fg, bg, px, weight] of TEXT) {
  const r = ratio(fg, bg);
  const floor = big(px, weight) ? 3 : 4.5;
  const ok = r >= floor;
  if (!ok) fails += 1;
  console.log(
    `| ${page} | ${kind} (${px} px ${weight}) | \`${fg}\` | \`${bg}\` | ${f1(r)}:1 | ${floor}:1 | ${ok ? 'pass' : 'fail'} |`,
  );
}
console.log('');
console.log('| Page | Edge or ring | Colour | Neighbour | Ratio | Floor | Result |');
console.log('| --- | --- | --- | --- | --- | --- | --- |');
for (const [page, kind, a, b] of NONTEXT) {
  const r = ratio(a, b);
  const ok = r >= 3;
  if (!ok) fails += 1;
  console.log(`| ${page} | ${kind} | \`${a}\` | \`${b}\` | ${f1(r)}:1 | 3:1 | ${ok ? 'pass' : 'fail'} |`);
}
console.log('');
console.log('pairs=' + (TEXT.length + NONTEXT.length) + ' fails=' + fails);
