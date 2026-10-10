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
 * Issue #429 adds a site section below the dashboard section (the correction
 * round of pull request #441, MAJOR 3): one row for each text-on-fill pair
 * and each edge of `site/src/styles/site.css`, in the light scheme and in
 * the dark scheme (live since issue #366). The `SITE_TOKENS` map holds the
 * light and the dark value of each site token; a site row names a token,
 * not a hex value, so it reads the right pair for its own scheme (MAJOR 1
 * of that round, for the site rows that this merge adds; the dashboard rows
 * below come from issue #361, now merged, with their own measured values).
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

// The disabled confirm button of the dialog (issue #384, #410): a
// `--shallows` fill and an `--ink-soft` text, with no opacity rule
// (`composite` stays exported for a future pair that does carry one).
const DISABLED_FILL = '#e6f3ff';
const DISABLED_INK = '#4b4763';

// The dark color scheme (issue #361). `--coral`, `--sunny`, `--seaweed`,
// and `--on-bright` keep their light value in the dark scheme too.
const DARK_DISABLED_FILL = '#14122a';
const DARK_DISABLED_INK = '#b9b4d6';

// [the page, the element kind, the text colour, the background, the px, the weight]
const TEXT = [
  ['each page', 'the skip link on focus', '#241f3d', '#ffd93d', 16, 400],
  ['each page', 'the wordmark on the band', '#241f3d', '#a78bfa', 25, 600],
  ['each page', 'the current nav pill', '#241f3d', '#ffd93d', 16, 600],
  ['each page', 'the other nav pill', '#241f3d', '#ffffff', 16, 600],
  // Issue #454: the hovered nav pill pair, outside pull request #445.
  // `--shallows-bright` keeps `#e6f3ff` in each scheme.
  ['each page', 'the hovered nav pill', '#241f3d', '#e6f3ff', 16, 600],
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
  // Issue #453: `.list-error` sets no `color` of its own, so the text
  // keeps `--ink` on the page background in each of the five views.
  ['apps, users, sessions, first-pages, manage', 'the list-error text', '#241f3d', '#e6f3ff', 16, 700],
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
  ['dialog', 'the disabled confirm button', DISABLED_INK, DISABLED_FILL, 16, 600],
];

// [the page, the edge or the ring, the colour, the neighbour]
const NONTEXT = [
  ['each page', 'the control edge on the page background', '#241f3d', '#e6f3ff'],
  ['each page', 'the control edge on the paper', '#241f3d', '#ffffff'],
  ['the band', 'the nav pill edge on the octo band', '#241f3d', '#a78bfa'],
  ['the band', 'the mascot head fill on the octo band', '#423a57', '#a78bfa'],
  // BLOCKER 1 of the review of pull request #459: the eye fill and the
  // smile stroke read `--on-bright` on the plain head, but the band
  // head fill is too dark for that pair. The band gives the eye and
  // the smile the `--octo-face-band` fill instead, white on the band
  // head fill.
  ['the band', 'the mascot eye and smile on the octo band head', '#ffffff', '#423a57'],
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
  // Issue #383 and MAJOR 3 of the review of pull request #450: the
  // `.form-error` left border uses its own token, `--coral-edge`. The
  // light value equals `--coral-deep`'s hex, `#d93a3a`.
  ['manage, signin', 'the form-error edge on the page background', '#d93a3a', '#e6f3ff'],
  ['manage', 'the form-error edge on the paper and on the dialog fill', '#d93a3a', '#ffffff'],
  // Issue #453: `.list-error` moves from the plain `var(--coral)` border
  // (2.46:1 on the page background, under the 3:1 floor of WCAG 1.4.11)
  // to `--coral-edge`, the token that already fixes `.form-error`.
  ['apps, users, sessions, first-pages, manage', 'the list-error edge on the page background', '#d93a3a', '#e6f3ff'],
];

/*
 * The dark color scheme (issue #361, the audit finding D1), `prefers-color-scheme:
 * dark`. `--coral`, `--sunny`, `--seaweed`, and `--on-bright` keep the light value of
 * the lists above; `--ink`, `--ink-soft`, `--paper`, `--paper-raised`, `--shallows`,
 * `--shallows-deep`, and `--octo` take the dark value of `styles.css`.
 *
 * `.band` and its mascot read none of those dark values (the correction round of
 * pull request #435). `.band` sets its own `--ink`, `--paper`, and `--line-control`;
 * each one stays its light-scheme value in the dark scheme too. The rows below for
 * the nav pill, the "Sign out" button, the nav pill edge, and the mascot's eye model
 * `.band`'s own fill and text, not the global dark tokens.
 */
const DARK_TEXT = [
  ['each page', 'the skip link on focus', '#241f3d', '#ffd93d', 16, 400],
  ['each page', 'the wordmark on the band', '#241f3d', '#9b7be5', 25, 600],
  ['each page', 'the current nav pill', '#241f3d', '#ffd93d', 16, 600],
  ['each page', 'the other nav pill', '#241f3d', '#ffffff', 16, 600],
  // Issue #454: the hovered nav pill pair, outside pull request #445.
  // `--shallows-bright` keeps `#e6f3ff` in each scheme.
  ['each page', 'the hovered nav pill', '#241f3d', '#e6f3ff', 16, 600],
  ['each page', 'the "Sign out" button', '#241f3d', '#ffffff', 16, 600],
  ['each page', 'the breadcrumb link and text', '#ece9ff', '#14122a', 16, 400],
  ['each page', 'the breadcrumb current item', '#ece9ff', '#14122a', 16, 700],
  ['each page', 'the page title h1', '#ece9ff', '#14122a', 25, 600],
  ['each data page', 'the refresh note', '#b9b4d6', '#14122a', 12.8, 400],
  ['apps', 'the retention note', '#ece9ff', '#14122a', 16, 400],
  ['each data page', 'the pause pill', '#ece9ff', '#1d1a33', 16, 600],
  // Issue #454: `--shallows-hover` is the new fill of the hovered pill,
  // in the dark scheme only (the light value stays `#e6f3ff`).
  ['each data page', 'the hovered pause pill', '#ece9ff', '#3d3570', 16, 600],
  ['each table', 'the panel caption', '#b9b4d6', '#1d1a33', 12.8, 400],
  ['manage', 'the panel caption (bold)', '#b9b4d6', '#1d1a33', 12.8, 700],
  ['each table', 'the table head cell', '#ece9ff', '#2c2850', 16, 600],
  ['each table', 'the cell on the paper row', '#ece9ff', '#1d1a33', 16, 400],
  ['each table', 'the row header on the paper row', '#ece9ff', '#1d1a33', 16, 700],
  ['each table', 'the link in the paper row', '#ece9ff', '#1d1a33', 16, 400],
  ['each table', 'the cell on the zebra row', '#ece9ff', '#14122a', 16, 400],
  ['each table', 'the link in the zebra row', '#ece9ff', '#14122a', 16, 700],
  ['each table', 'the time cell', '#ece9ff', '#1d1a33', 16, 400],
  ['apps', 'the badge OK', '#241f3d', '#6ee7b7', 12.8, 700],
  ['apps', 'the badge warn (sunny)', '#241f3d', '#ffd93d', 12.8, 700],
  ['apps', 'the badge bad (coral)', '#241f3d', '#ff6b6b', 12.8, 700],
  ['apps', 'the badge not polled', '#ece9ff', '#2c2850', 12.8, 700],
  ['users', 'the filter label', '#ece9ff', '#14122a', 16, 400],
  ['users', 'the filter button', '#ece9ff', '#1d1a33', 12.8, 600],
  ['users', 'the disabled pager button', '#b9b4d6', '#14122a', 12.8, 600],
  ['users, sessions, not-found', 'the empty state sentence', '#ece9ff', '#1d1a33', 16, 400],
  ['sessions', 'the first-load sentence', '#ece9ff', '#14122a', 16, 400],
  // Issue #453: the dark `--ink` value on the dark page background.
  ['apps, users, sessions, first-pages, manage', 'the list-error text', '#ece9ff', '#14122a', 16, 700],
  ['manage', 'the Edit and Delete buttons', '#ece9ff', '#1d1a33', 12.8, 600],
  ['manage', 'the form heading h2', '#ece9ff', '#1d1a33', 20, 600],
  ['manage', 'the field label', '#ece9ff', '#1d1a33', 16, 700],
  ['manage', 'the field hint', '#b9b4d6', '#1d1a33', 12.8, 400],
  ['manage', 'the hint example code', '#b9b4d6', '#1d1a33', 12.8, 400],
  ['manage', 'the form error text', '#ece9ff', '#1d1a33', 16, 700],
  ['manage', 'the primary button', '#241f3d', '#ffd93d', 16, 600],
  ['manage', 'the status cell', '#ece9ff', '#1d1a33', 16, 400],
  ['dialog', 'the dialog heading h2', '#ece9ff', '#312b57', 20, 600],
  ['dialog', 'the dialog sentence', '#ece9ff', '#312b57', 16, 400],
  ['dialog', 'the dialog strong text', '#ece9ff', '#312b57', 16, 700],
  ['dialog', 'the Cancel button', '#ece9ff', '#1d1a33', 16, 600],
  ['dialog', 'the disabled confirm button', DARK_DISABLED_INK, DARK_DISABLED_FILL, 16, 600],
];

// [the page, the edge or the ring, the colour, the neighbour]
const DARK_NONTEXT = [
  ['each page', 'the control edge on the page background', '#ece9ff', '#14122a'],
  ['each page', 'the control edge on the paper', '#ece9ff', '#1d1a33'],
  ['the band', 'the nav pill edge on the octo band', '#241f3d', '#9b7be5'],
  ['main', "the mascot's eye on the octo head (the first-load mark)", '#241f3d', '#9b7be5'],
  ['the band', 'the mascot head fill on the octo band', '#423a57', '#9b7be5'],
  // BLOCKER 1 of the review of pull request #459: the band's own
  // `--octo-face-band` stays white in each color scheme, so this pair
  // equals the light-scheme pair above.
  ['the band', 'the mascot eye and smile on the octo band head', '#ffffff', '#423a57'],
  ['each page', 'the panel edge on the page background', '#ece9ff', '#14122a'],
  ['each table', 'the head cell edge on the head fill', '#ece9ff', '#2c2850'],
  ['apps', 'the badge edge on the badge fill (seaweed)', '#241f3d', '#6ee7b7'],
  ['apps', 'the badge edge on the badge fill (coral)', '#241f3d', '#ff6b6b'],
  ['apps', 'the badge edge on the badge fill (sunny)', '#241f3d', '#ffd93d'],
  ['each page', 'the focus ring: the ink halo on the page background', '#ece9ff', '#14122a'],
  ['each page', 'the focus ring: the ink halo on the paper', '#ece9ff', '#1d1a33'],
  ['the band', 'the focus ring: the ink halo on the octo band', '#241f3d', '#9b7be5'],
  ['each page', 'the focus ring: the sunny line on the ink halo', '#ffd93d', '#ece9ff'],
  ['users', 'the disabled pager edge on the page background', '#b9b4d6', '#14122a'],
  // Issue #438: this dark-scheme row still named the pre-#264 literal
  // too (#435 copied the light-scheme mistake). `--coral-deep` keeps
  // its light value in the dark scheme, so the real pair is the same
  // hex, on the dark field fill.
  ['manage', 'the invalid field ring on the field fill', '#d93a3a', '#1d1a33'],
  // Issue #383 and BLOCKER 1 of the review of pull request #450: the
  // dark value of `--coral-edge`, `#ff6b6b`, reads 3:1 or more on each
  // fill the `.form-error` border paints, the dialog fill included.
  ['manage, signin', 'the form-error edge on the page background', '#ff6b6b', '#14122a'],
  ['manage', 'the form-error edge on the paper', '#ff6b6b', '#1d1a33'],
  ['dialog', 'the form-error edge on the dialog fill', '#ff6b6b', '#312b57'],
  // Issue #453: `.list-error` always sat at 6.58:1 in the dark scheme
  // (`var(--coral)` and `--coral-edge` share the dark value `#ff6b6b`);
  // the fix only raises the light-scheme value.
  ['apps, users, sessions, first-pages, manage', 'the list-error edge on the page background', '#ff6b6b', '#14122a'],
];

/*
 * The site section (issue #429, MAJOR 3 of the correction round of pull
 * request #441): one row for each text-on-fill pair and each edge of
 * `site/src/styles/site.css`, so the site itself has cover, not only the
 * dashboard record above. `SITE_TOKENS` holds the light and the dark hex
 * of each site token (`site/src/styles/tokens.css`); the site's own `--ink`
 * and `--on-bright` do differ in the dark scheme, so each row below names
 * the token, not a hex value (MAJOR 1 of that round).
 */
const SITE_TOKENS = {
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
  linkUnderline: { light: '#d93f3f', dark: '#ff6b6b' },
  focusRing: { light: '#ffd93d', dark: '#ffd93d' },
  focusHalo: { light: '#241f3d', dark: '#f4f1ff' },
};

/** The hex value of a site token, in one scheme ('light' or 'dark'). */
const siteHex = (token, scheme) => SITE_TOKENS[token][scheme];

// [the page, the element kind, the text token, the background token, the px, the weight]
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

// [the page, the edge or the ring, the colour token, the neighbour token]
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

console.log('The disabled confirm button (#410): ' + DISABLED_INK + ' on ' + DISABLED_FILL);
console.log('');
console.log('## The dashboard, the light scheme');
console.log('');
console.log('| Page | Text | Colour | Background | Ratio | Floor | Result |');
console.log('| --- | --- | --- | --- | --- | --- | --- |');
for (const [page, kind, fg, bg, px, weight] of TEXT) {
  const r = ratio(fg, bg);
  const floor = big(px, weight) ? 3 : 4.5;
  const ok = r >= floor;
  pairs += 1;
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
  pairs += 1;
  if (!ok) fails += 1;
  console.log(`| ${page} | ${kind} | \`${a}\` | \`${b}\` | ${f1(r)}:1 | 3:1 | ${ok ? 'pass' : 'fail'} |`);
}
console.log('');
console.log('## The dashboard, the dark scheme (issue #361, prefers-color-scheme: dark)');
console.log(
  'The disabled confirm button (#410): ' + DARK_DISABLED_INK + ' on ' + DARK_DISABLED_FILL,
);
console.log('');
console.log('| Page | Text | Colour | Background | Ratio | Floor | Result |');
console.log('| --- | --- | --- | --- | --- | --- | --- |');
for (const [page, kind, fg, bg, px, weight] of DARK_TEXT) {
  const r = ratio(fg, bg);
  const floor = big(px, weight) ? 3 : 4.5;
  const ok = r >= floor;
  pairs += 1;
  if (!ok) fails += 1;
  console.log(
    `| ${page} | ${kind} (${px} px ${weight}) | \`${fg}\` | \`${bg}\` | ${f1(r)}:1 | ${floor}:1 | ${ok ? 'pass' : 'fail'} |`,
  );
}
console.log('');
console.log('| Page | Edge or ring | Colour | Neighbour | Ratio | Floor | Result |');
console.log('| --- | --- | --- | --- | --- | --- | --- |');
for (const [page, kind, a, b] of DARK_NONTEXT) {
  const r = ratio(a, b);
  const ok = r >= 3;
  pairs += 1;
  if (!ok) fails += 1;
  console.log(`| ${page} | ${kind} | \`${a}\` | \`${b}\` | ${f1(r)}:1 | 3:1 | ${ok ? 'pass' : 'fail'} |`);
}

/** Prints one site TEXT table, in one scheme, with one page-label suffix. */
function printSiteText(scheme, suffix) {
  console.log('| Page | Text | Colour | Background | Ratio | Floor | Result |');
  console.log('| --- | --- | --- | --- | --- | --- | --- |');
  for (const [page, kind, fgToken, bgToken, px, weight] of SITE_TEXT) {
    const fg = siteHex(fgToken, scheme);
    const bg = siteHex(bgToken, scheme);
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

/** Prints one site NONTEXT table, in one scheme, with one page-label suffix. */
function printSiteNontext(scheme, suffix) {
  console.log('| Page | Edge or ring | Colour | Neighbour | Ratio | Floor | Result |');
  console.log('| --- | --- | --- | --- | --- | --- | --- |');
  for (const [page, kind, aToken, bToken] of SITE_NONTEXT) {
    const a = siteHex(aToken, scheme);
    const b = siteHex(bToken, scheme);
    const r = ratio(a, b);
    const ok = r >= 3;
    pairs += 1;
    if (!ok) fails += 1;
    console.log(`| ${page}${suffix} | ${kind} | \`${a}\` | \`${b}\` | ${f1(r)}:1 | 3:1 | ${ok ? 'pass' : 'fail'} |`);
  }
}

console.log('');
console.log('## The site, the light scheme');
console.log('');
printSiteText('light', '');
console.log('');
printSiteNontext('light', '');
console.log('');
console.log('## The site, the dark scheme (live: issue #366)');
console.log('');
printSiteText('dark', ' (dark)');
console.log('');
printSiteNontext('dark', ' (dark)');
console.log('');
console.log('pairs=' + pairs + ' fails=' + fails);
