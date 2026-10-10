# The design of the Octometer monitor UI

`docs/components.md` lists each UI component of the monitor, with its purpose, its
anatomy, its states, its accessibility contract, and its rules. Read that document for
one component; read this page for the tokens and the six principles that each component
follows.

This page holds the cartoon look of the monitor UI (design decision D45). It lists the
tokens, the type scale, the page column, the six principles, the status badges, the focus
ring, and the contrast table. `docs/design/mockup.html` is the reference for the tokens and
the base rules.

## The tokens

| Token | Value | Role |
| --- | --- | --- |
| `--ink` | `#241F3D` | Outlines, text, hard shadows. |
| `--ink-soft` | `#4B4763` | Hint text. |
| `--paper` | `#FFFFFF` | The panel and control fill. |
| `--paper-raised` | `#FFFFFF` | The dialog fill, one step above the paper. |
| `--shallows` | `#E6F3FF` | The page background, the zebra row tint. |
| `--shallows-deep` | `#BFDFFF` | The table head, the neutral status fill. |
| `--coral` | `#FF6B6B` | The danger fill, the link underline. |
| `--coral-deep` | `#D93A3A` | The invalid field ring; keeps 3:1 or more on `--paper` and on `--shallows` (WCAG 1.4.11, issue #264 and issue #438). |
| `--coral-edge` | `#D93A3A` light, `#FF6B6B` dark | The `.form-error` and `.list-error` left border; keeps 3:1 or more on `--paper`, `--shallows`, and `--paper-raised` (WCAG 1.4.11, issue #383, issue #450, and issue #453). |
| `--sunny` | `#FFD93D` | The primary fill, the current nav pill, the focus ring, the warning fill. |
| `--seaweed` | `#6EE7B7` | The OK fill. |
| `--octo` | `#A78BFA` | The header band, the mascot body. Never a text background. |
| `--octo-head-band` | `#423A57` | The mascot head fill on the header band; keeps 3:1 or more on `--octo` (WCAG 1.4.11). |
| `--octo-face-band` | `#FFFFFF` | The mascot eye fill and smile stroke on the header band; keeps 10.6:1 on `--octo-head-band` (WCAG 1.4.11, issue #439, BLOCKER 1 of pull request #459). |
| `--on-bright` | `#241F3D` | The text and the edge on each bright fill. It stays dark in a dark theme. |
| `--backdrop` | `rgb(36 31 61 / 0.6)` | The dim layer behind a dialog. |
| `--radius-panel` | `20px` | A comic panel. |
| `--radius-control` | `12px` | A button, an input, a dialog edge. |
| `--radius-pill` | `999px` | A nav pill, a status badge. |
| `--line-panel` | `3px solid var(--ink)` | The outline of a panel. |
| `--line-control` | `2px solid var(--ink)` | The outline of a control. |
| `--shadow-panel` | `6px 6px 0 var(--ink)` | The hard shadow of a panel. |
| `--shadow-control` | `3px 3px 0 var(--ink)` | The hard shadow of a control. |
| `--focus-halo` | `var(--ink)` light, `#7B74B3` dark | The 7px halo of the focus ring; its own token, not plain `--ink` (issue #451). `.band` overrides it with `--on-bright` (pull request #462). |
| `--space-1` | `4px` | The space scale, a 4px base. |
| `--space-2` | `8px` | The space scale. |
| `--space-3` | `12px` | The space scale. |
| `--space-4` | `16px` | The space scale; the page gutter on a phone. |
| `--space-5` | `24px` | The space scale. |
| `--space-6` | `32px` | The space scale; the page gutter from 48rem up. |
| `--space-7` | `48px` | The space scale. |

A table cell keeps a radius of 0. `--ink`, `--ink-soft`, `--paper`, `--paper-raised`,
`--shallows`, `--shallows-deep`, `--octo`, and `--focus-halo` each take a different value in
the dark color scheme; see "The dark color scheme" below.

## The page column

`main` (`app.html`) keeps `max-width: 76rem`, `margin-inline: auto`, and a side gutter of
`--space-4` (16px) on a phone; a media query at `48rem` widens the gutter to `--space-6`
(32px). Each view sits inside that one element, so no component sets its own gutter or its
own column width.

## The type scale and the two families

| Token | Value |
| --- | --- |
| `--text-s` | `0.875rem` (14px) |
| `--text-m` | `1rem` (16px) |
| `--text-l` | `1.25rem` (20px) |
| `--text-xl` | `1.5625rem` (25px) |
| `--text-2xl` | `1.953rem` (31.25px) |
| `--text-3xl` | `2.441rem` (39.06px) |
| `--text-4xl` | `3.0518rem` (48.83px) |

The scale keeps a ratio of 1.25 on a 16px base, except for the small step at 0.875rem
(14px). Body text keeps a line height of 1.5; a heading keeps a line height of 1.15. The
dashboard text keeps a floor of 14 px (the audit finding D23).

- `Fredoka` 600 (`--display`): the wordmark, the page title, the panel caption, the nav
  pills, the button text. It gives the UI its cartoon voice.
- `Nunito` 400 and 700 (`--body`): the body text, the table, the forms, the hints. A number
  cell keeps `font-variant-numeric: tabular-nums`. No monospace face appears in the UI.

Each font ships as a `woff2` and a `woff` file inside the bundle (`@fontsource/fredoka` and
`@fontsource/nunito`, version `5.3.0`, the latin subset only). The page makes no request to
an outside font host.

## The six principles

1. An ink outline marks a container or a control. Nothing else has a border.
2. A bright fill carries ink text. White text never sits on a bright fill.
3. The header band, with the mascot, is the one bold element. Each table stays calm.
4. Motion answers an action, such as a press or a dialog open. Reduced motion removes it.
5. A status shows an icon, a word, and a color together.
6. Each asset ships in the bundle. The CSP stays as it is (D12).

## The status badges

`status-format.ts` gives the mark and the word for each status.

| Status | Fill | Mark and word |
| --- | --- | --- |
| OK | `--seaweed` | `✓ OK` |
| NEVER_POLLED | `--shallows-deep` | `– Not polled yet` |
| UNREACHABLE, UNAUTHORIZED, INVALID_DATA, ERROR | `--coral` | `⚠ <the word>` |
| OVERPRIVILEGED | `--sunny` | `⚠ Overprivileged` |

## The focus ring

The shared `:focus-visible` rule of `src/styles.css` gives a 3px sunny outline, a 2px
offset, and a 7px hard halo ring (`box-shadow: 0 0 0 7px var(--focus-halo)`), because the
sunny outline alone gives 1.2:1 on the shallows background. A control with its own
box-shadow, such as `.btn`, must repeat the 7px halo ring in its own `:focus-visible` rule
(`box-shadow: <its shadow>, 0 0 0 7px var(--focus-halo)`), or its own shadow replaces the
ring instead of adding to it.

`--focus-halo` keeps `--ink`'s value in the light scheme. Issue #451 gives it its own
literal value in the dark scheme, `#726BAB` at first, because plain `--ink` (`#ECE9FF`)
reads 1.2:1 against the sunny line, under the 3:1 floor of WCAG 1.4.11.

The correction round of pull request #462 raises that value to `#7B74B3`. `#726BAB` did
not clear 3:1 against the focused row fill (`--shallows-deep`, 2.89:1) nor against the
dialog fill (`--paper-raised`, 2.73:1). `#7B74B3` reads 3.1:1 against the sunny line, 4.3:1
against the dark page background, 4.0:1 against the dark paper, 3.3:1 against the focused
row fill, and 3.1:1 against the dialog fill.

`.band` reads none of this: a custom property resolves the `var()` inside it at the
element that declares it, so `--focus-halo: var(--ink)` at `:root` freezes to the `--ink`
of `:root`, and a focused band control once kept that frozen value on the band fill, at
1.44:1 in the dark scheme. `.band` now sets its own `--focus-halo`, `var(--on-bright)`, so
a focused band control reads 4.75:1 on the band fill (the same value `.band` used before
issue #451). The site's own dark `--focus-halo` (`site/src/styles/tokens.css`) keeps
`#726BAB`, a separate token with no row or dialog fill of its own.

## The contrast table

The implementer measured each pair with a Node script that computes the WCAG 2.x relative
luminance and the contrast ratio of the two hex values (`node -e`, run on 2026-09-27). Each
text pair stays at 4.5:1 or above, the floor of WCAG 1.4.3 for normal text. The
`--coral-deep` / `--shallows` row (issue #383) is a non-text indicator, the 3px left border
of a `.form-error` message on the sign-in page's body background; its floor is the 3:1
value of WCAG 1.4.11.

| Text or edge | Background | Ratio |
| --- | --- | --- |
| `--ink` | `--paper` | 15.7:1 |
| `--ink` | `--shallows` | 13.9:1 |
| `--ink` | `--shallows-deep` | 11.4:1 |
| `--ink` | `--sunny` | 11.4:1 |
| `--ink` | `--seaweed` | 10.3:1 |
| `--ink` | `--coral` | 5.7:1 |
| `--ink` | `--octo` | 5.8:1 |
| `--ink-soft` | `--paper` | 8.8:1 |
| `--coral-deep` | `--paper` | 4.5:1 |
| `--coral-deep` | `--shallows` | 4.0:1 |

`--on-bright` equals `--ink` (both `#241F3D`), so the four `--on-bright` pairs (on `--sunny`,
`--seaweed`, `--coral`, `--octo`) repeat the four `--ink` rows above; the table keeps the ten
rows only.

`docs/design/contrast.mjs` carries the invalid-field ring pair (the `box-shadow` of
`[aria-invalid='true']`, `--coral-deep` on `--paper`, 4.5:1). Until issue #438, that pair
still named the pre-#264 literal `#ff6b6b` (2.8:1, under the 3:1 floor of WCAG 1.4.11);
the script now names `--coral-deep`'s real value, `#d93a3a`, and the pair passes. In the
dark scheme (issue #435), `--coral-deep` keeps its light value and still passes: 3.70:1
on the dark `--paper` (`#1D1A33`) and 4.02:1 on the dark `--shallows` (`#14122A`).

The `.form-error` left border uses a separate token, `--coral-edge` (issue #383 and issue
#450). `--coral-edge` keeps `--coral-deep`'s light value, `#d93a3a`. It reads 4.55:1 on
`--paper`, 4.04:1 on `--shallows`, and 4.55:1 on the dialog fill `--paper-raised` in the
light scheme. In the dark scheme `--coral-edge` takes `#ff6b6b`. The pair then reads 6.06:1 on
`--paper`, 6.58:1 on `--shallows`, and 4.69:1 on `--paper-raised`, the fill of
`DeleteAppDialog` and `NewSecretKeyDialog`.

`.list-error` sets no `color` of its own (issue #453). Its text keeps `--ink` on the page
background, the `--ink` / `--shallows` pair above: 13.9:1 in the light scheme and 15.4:1 in
the dark scheme, each far above the 4.5:1 floor of WCAG 1.4.3. Its left border held the
plain `var(--coral)` value until issue #453: `#ff6b6b` on `--shallows` reads 2.46:1 in the
light scheme, under the 3:1 floor of WCAG 1.4.11. `--coral-edge` is the fix: 4.04:1 in the
light scheme, and 6.58:1 in the dark scheme (the dark value of `--coral-edge` equals the
dark value of `--coral`, so the dark-scheme ratio does not change).

## The dark color scheme

A `prefers-color-scheme: dark` block in `monitor/frontend/src/styles.css` gives the
dashboard shell a dark scheme (design decision D1, issue #361). `--coral`, `--sunny`,
`--seaweed`, and `--on-bright` keep their light value in the dark scheme too, so a bright
fill (a badge, a button, the band) keeps its ink text in each scheme.

| Token | Light value | Dark value |
| --- | --- | --- |
| `--ink` | `#241F3D` | `#ECE9FF` |
| `--ink-soft` | `#4B4763` | `#B9B4D6` |
| `--paper` | `#FFFFFF` | `#1D1A33` |
| `--paper-raised` | `#FFFFFF` | `#312B57` |
| `--paper-bright` | `#FFFFFF` | `#FFFFFF` |
| `--shallows` | `#E6F3FF` | `#14122A` |
| `--shallows-deep` | `#BFDFFF` | `#2C2850` |
| `--octo` | `#A78BFA` | `#9B7BE5` |
| `--focus-halo` | `var(--ink)` (`#241F3D`) | `#7B74B3` |

`.band` of `monitor/frontend/src/app/app.css` no longer sets `--ink: #241f3d;` and
`--paper: #ffffff;` as hex literals. It sets `--ink: var(--on-bright)` and
`--paper: var(--paper-bright)`. It also sets its own `--line-control`. Each rule keeps
the band text, the mascot's head fill, and the band edge the same in each scheme.

`--paper-bright` is a correction of pull request #435. `.band` once read
`--paper-raised`, and the dark value of `--paper-raised` (`#312B57`) then made the band
dark on dark: the nav pill label and the "Sign out" label read 1.21:1, under the floor of
4.5:1. `--paper-bright` keeps `#FFFFFF` in each scheme, so the band stays bright on dark.

The audit draft of issue #361 names `#5B45B0` for the dark `--octo`, with no dark value for
`--paper-raised`. Each measured under the WCAG 1.4.3 floor:

| Pair | Audit draft | Ratio | Raised value | Ratio |
| --- | --- | --- | --- | --- |
| `--on-bright` text on `--octo` (the band, `--text-s`) | `#5B45B0` | 2.18:1 | `#9B7BE5` | 4.75:1 |
| `--ink` text on `--paper-raised` (a dialog) | `#FFFFFF` (unset) | 1.19:1 | `#312B57` | 10.95:1 |

`docs/design/contrast.mjs` (issue #251) computes the full dark-scheme table, next to the
light-scheme table above; run `node docs/design/contrast.mjs`.

## The accepted exceptions

Issue #451 clears the four fails of `docs/design/contrast.mjs`. Issue #438 (pull request
#450) fixes the first one, the dashboard invalid-field ring. The dashboard and the site
focus ring (the sunny line on the halo) each get a token fix too: `--focus-halo` now takes
its own dark value (see "The focus ring" above and "The dark color scheme"). The
correction round of pull request #462 raises the dashboard's dark value to `#7B74B3`,
so the focused row fill and the dialog fill clear 3:1 too, and gives `.band` its own
`--focus-halo`, so the band keeps its 4.75:1 ring.

One pair stays an accepted exception: the site focus halo on the octo band, in the dark
scheme (1.8:1, under the 3:1 floor of WCAG 1.4.11). The hero preview bar
(`site/index.html:74-80`) holds `span` elements only; no focusable control sits on the
octo band today. The reviewer of pull request #441 confirmed this with a Tab sweep of
each focusable control of the site. `docs/design/contrast.mjs` carries the pair with an
`accepted` field and its reason, and the script prints it apart from `fails`.
