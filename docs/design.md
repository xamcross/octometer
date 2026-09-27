# The design of the Octometer monitor UI

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
| `--coral` | `#FF6B6B` | The danger fill, the error edge, the link underline. |
| `--coral-deep` | `#D93A3A` | The invalid field ring; keeps 3:1 or more on `--paper` (WCAG 1.4.11). |
| `--sunny` | `#FFD93D` | The primary fill, the current nav pill, the focus ring, the warning fill. |
| `--seaweed` | `#6EE7B7` | The OK fill. |
| `--octo` | `#A78BFA` | The header band, the mascot body. Never a text background. |
| `--on-bright` | `#241F3D` | The text and the edge on each bright fill. It stays dark in a dark theme. |
| `--backdrop` | `rgb(36 31 61 / 0.6)` | The dim layer behind a dialog. |
| `--radius-panel` | `20px` | A comic panel. |
| `--radius-control` | `12px` | A button, an input, a dialog edge. |
| `--radius-pill` | `999px` | A nav pill, a status badge. |
| `--line-panel` | `3px solid var(--ink)` | The outline of a panel. |
| `--line-control` | `2px solid var(--ink)` | The outline of a control. |
| `--shadow-panel` | `6px 6px 0 var(--ink)` | The hard shadow of a panel. |
| `--shadow-control` | `3px 3px 0 var(--ink)` | The hard shadow of a control. |
| `--space-1` | `4px` | The space scale, a 4px base. |
| `--space-2` | `8px` | The space scale. |
| `--space-3` | `12px` | The space scale. |
| `--space-4` | `16px` | The space scale; the page gutter on a phone. |
| `--space-5` | `24px` | The space scale. |
| `--space-6` | `32px` | The space scale; the page gutter from 48rem up. |
| `--space-7` | `48px` | The space scale. |

A table cell keeps a radius of 0.

## The page column

`main` (`app.html`) keeps `max-width: 76rem`, `margin-inline: auto`, and a side gutter of
`--space-4` (16px) on a phone; a media query at `48rem` widens the gutter to `--space-6`
(32px). Each view sits inside that one element, so no component sets its own gutter or its
own column width.

## The type scale and the two families

| Token | Value |
| --- | --- |
| `--text-s` | `0.8rem` (12.8px) |
| `--text-m` | `1rem` (16px) |
| `--text-l` | `1.25rem` (20px) |
| `--text-xl` | `1.5625rem` (25px) |
| `--text-2xl` | `1.953rem` (31.25px) |
| `--text-3xl` | `2.441rem` (39.06px) |

The scale keeps a ratio of 1.25 on a 16px base. Body text keeps a line height of 1.5; a
heading keeps a line height of 1.15.

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
offset, and a 7px hard ink ring (`box-shadow: 0 0 0 7px var(--ink)`), because the sunny
outline alone gives 1.2:1 on the shallows background. A control with its own box-shadow,
such as a future `.btn`, must repeat the 7px ink ring in its own `:focus-visible` rule
(`box-shadow: <its shadow>, 0 0 0 7px var(--ink)`), or its own shadow replaces the ring
instead of adding to it.

## The contrast table

The implementer measured each pair with a Node script that computes the WCAG 2.x relative
luminance and the contrast ratio of the two hex values (`node -e`, run on 2026-09-27). Each
pair stays at 4.5:1 or above, the floor of WCAG 1.4.3 for normal text.

| Text | Background | Ratio |
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

`--on-bright` equals `--ink` (both `#241F3D`), so the four `--on-bright` pairs (on `--sunny`,
`--seaweed`, `--coral`, `--octo`) repeat the four `--ink` rows above; the table keeps the
eight rows only.
