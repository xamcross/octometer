# The accessibility verification of the cartoon look, 2026-09-27

This page records the checks of issue #251 on the new look. It covers the contrast, the
keyboard path, the zoom to 200 percent, the 360 px width, `forced-colors: active`, and
`prefers-reduced-motion: reduce`.

## The target and the tools

| Item | Value |
| --- | --- |
| The build under test | `main` at `837e320`, the merge of #246 to #250 |
| The server | the local monitor `http://127.0.0.1:7431/`, prod mode, version `0.1.0` |
| The data | one app `demo` with 125 clicks, 5 users, 25 sessions, status OK |
| The served stylesheet | `styles-W6GJBH64.css`, `--radius-panel: 20px` |
| The browser | Chromium 153.0.8010.12 through `playwright-core` 1.63.0 |
| The second browser | the session Chrome, for the first-load screenshot and the first keyboard pass |
| The contrast tool | `getComputedStyle` for each colour, then a Node script with the WCAG 2.x relative luminance formula |
| The error states | a dev server of the same commit on `http://127.0.0.1:4307/`, with a proxy to the monitor |

Notes on the method:

- The colour of each pair comes from the rendered element. The script composites an
  ancestor background and an `opacity` value before it computes the ratio.
- The zoom of 200 percent uses a viewport of 640 x 400 CSS px with the device scale
  factor 2. That equals a browser zoom of 200 percent in a window of 1280 x 800.
- The three badge kinds that the data does not show got one temporary `span` each. The
  script read the computed colours, then it removed the span.
- The error state of the add form needs a failed answer. The browser answered the
  `POST /api/apps` with a local 400. The monitor received no write.
- No check registered an app, no check deleted an app, and no check called the erasure
  route.

## The result of each check

| Page | Width | Check | Result | Date |
| --- | --- | --- | --- | --- |
| each of the 7 views | 1280 px | the contrast of each text pair | pass, 41 pairs; 1 pair below the floor (see the defects) | 2026-09-27 |
| each of the 7 views | 1280 px | the contrast of each edge and each ring | pass, 14 pairs; 1 pair below the floor (see the defects) | 2026-09-27 |
| apps | 1280 px | the keyboard path with Tab and Shift+Tab | pass, 8 stops, a ring on each stop, no trap | 2026-09-27 |
| apps | 1280 px | the skip link, then Enter | pass, the focus moves to `main#main-content` | 2026-09-27 |
| apps | 1280 px | Space on the pause pill | pass, `aria-pressed` goes false, true, false; the focus stays | 2026-09-27 |
| users | 1280 px, 360 px | the keyboard path, the filter, the pager, the table region | pass, 12 stops, no clipped ring | 2026-09-27 |
| manage | 1280 px | Enter on Edit, then Tab to Save and Cancel | pass, the focus moves to the name field | 2026-09-27 |
| manage | 1280 px | Escape in the edit form | the form stays open; Cancel closes it and returns the focus to Edit | 2026-09-27 |
| manage | 1280 px | the delete dialog with the keyboard | pass, the focus moves to Cancel, Tab cycles inside, Escape returns the focus to Delete | 2026-09-27 |
| apps, users, elements, first pages, sessions, manage, not found | 1280 px | no sideways scroll, no clipped text, no overlap | pass | 2026-09-27 |
| apps, users, elements, first pages, sessions, manage, not found | 200 percent zoom | no sideways scroll, no clipped text, no overlap | pass | 2026-09-27 |
| apps, first pages, sessions, manage, not found | 360 px | no sideways scroll, no clipped text, no overlap | pass | 2026-09-27 |
| users, elements | 360 px | no clipped text | fail, the panel caption runs past the panel edge | 2026-09-27 |
| apps | 1280 px | `forced-colors: active`, the control edges | pass, each control keeps an edge | 2026-09-27 |
| apps | 1280 px | `forced-colors: active`, the badge word | pass, the badge shows `✓ OK` with an edge | 2026-09-27 |
| apps | 1280 px | `forced-colors: active`, the current pill | pass, the pill keeps its underline | 2026-09-27 |
| apps | 1280 px | `forced-colors: active`, the focus ring | pass, a 3 px `Highlight` outline | 2026-09-27 |
| manage | 1280 px | `prefers-reduced-motion: reduce`, the dialog open | pass, no transition, no animation | 2026-09-27 |
| manage | 1280 px | `prefers-reduced-motion: reduce`, a button press | fail, the press keeps the 80 ms transition and the 3 px offset | 2026-09-27 |
| users | 1280 px | the filter with no match | pass, one sentence in a panel, 15.7:1 | 2026-09-27 |
| manage | 1280 px | the add form with an empty field | pass, the browser blocks the submit; the focus moves to the first invalid field | 2026-09-27 |
| manage | 1280 px | the add form after a 400 answer | pass, the error text, `aria-invalid`, and the `aria-describedby` chain | 2026-09-27 |
| sessions | 1280 px | the first-load row | pass, one sentence, 13.9:1 | 2026-09-27 |
| sessions, not found | 1280 px, 360 px | the empty state | pass, the mascot and one sentence in a panel | 2026-09-27 |
| apps | any | the gap note | not run, the data shows no gap | 2026-09-27 |
| any | any | a placeholder text | not run, no field of the UI has a placeholder | 2026-09-27 |

## The contrast of the text pairs

Each ratio comes from the WCAG 2.x formula. The floor is 4.5:1, and 3:1 for a text of
24 px or more.

| Page | Text | Colour | Background | Ratio | Floor | Result |
| --- | --- | --- | --- | --- | --- | --- |
| each page | the skip link on focus (16 px 400) | `#241f3d` | `#ffd93d` | 11.4:1 | 4.5:1 | pass |
| each page | the wordmark on the band (25 px 600) | `#241f3d` | `#a78bfa` | 5.8:1 | 3:1 | pass |
| each page | the current nav pill (16 px 600) | `#241f3d` | `#ffd93d` | 11.4:1 | 4.5:1 | pass |
| each page | the other nav pill (16 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| each page | the breadcrumb link and text (16 px 400) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| each page | the breadcrumb current item (16 px 700) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| each page | the page title h1 (25 px 600) | `#241f3d` | `#e6f3ff` | 13.9:1 | 3:1 | pass |
| each data page | the refresh note (12.8 px 400) | `#4b4763` | `#e6f3ff` | 7.8:1 | 4.5:1 | pass |
| apps | the retention note (16 px 400) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| each data page | the pause pill (16 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| each table | the panel caption (12.8 px 400) | `#4b4763` | `#ffffff` | 8.8:1 | 4.5:1 | pass |
| manage | the panel caption (12.8 px 700) | `#4b4763` | `#ffffff` | 8.8:1 | 4.5:1 | pass |
| each table | the table head cell (16 px 600) | `#241f3d` | `#bfdfff` | 11.4:1 | 4.5:1 | pass |
| each table | the cell on the paper row (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| each table | the row header on the paper row (16 px 700) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| each table | the link in the paper row (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| each table | the cell on the zebra row (16 px 400) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| each table | the link in the zebra row (16 px 700) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| each table | the time cell (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| apps | the badge OK (12.8 px 700) | `#241f3d` | `#6ee7b7` | 10.3:1 | 4.5:1 | pass |
| apps | the badge warn (12.8 px 700) | `#241f3d` | `#ffd93d` | 11.4:1 | 4.5:1 | pass |
| apps | the badge bad (12.8 px 700) | `#241f3d` | `#ff6b6b` | 5.7:1 | 4.5:1 | pass |
| apps | the badge not polled (12.8 px 700) | `#241f3d` | `#bfdfff` | 11.4:1 | 4.5:1 | pass |
| users | the filter label (16 px 400) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| users | the filter button (12.8 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| users | the disabled pager button (12.8 px 600) | `#4b4763` | `#e6f3ff` | 7.8:1 | 4.5:1 | pass |
| users, sessions, not found | the empty state sentence (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| sessions | the first-load sentence (16 px 400) | `#241f3d` | `#e6f3ff` | 13.9:1 | 4.5:1 | pass |
| manage | the Edit and Delete buttons (12.8 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| manage | the form heading h2 (20 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| manage | the field label (16 px 700) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| manage | the field hint (12.8 px 400) | `#4b4763` | `#ffffff` | 8.8:1 | 4.5:1 | pass |
| manage | the hint example code (12.8 px 400) | `#4b4763` | `#ffffff` | 8.8:1 | 4.5:1 | pass |
| manage | the form error text (16 px 700) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| manage | the primary button (16 px 600) | `#241f3d` | `#ffd93d` | 11.4:1 | 4.5:1 | pass |
| manage | the status cell (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| dialog | the dialog heading h2 (20 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| dialog | the dialog sentence (16 px 400) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| dialog | the dialog strong text (16 px 700) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| dialog | the Cancel button (16 px 600) | `#241f3d` | `#ffffff` | 15.7:1 | 4.5:1 | pass |
| dialog | the disabled confirm button, opacity 0.55 (16 px 600) | `#875f70` | `#ffaeae` | 3.1:1 | 4.5:1 | fail |

## The contrast of the edges and the rings

The floor is 3:1 for each pair of this table.

| Page | Edge or ring | Colour | Neighbour | Ratio | Result |
| --- | --- | --- | --- | --- | --- |
| each page | the control edge on the page background | `#241f3d` | `#e6f3ff` | 13.9:1 | pass |
| each page | the control edge on the paper | `#241f3d` | `#ffffff` | 15.7:1 | pass |
| the band | the nav pill edge on the octo band | `#241f3d` | `#a78bfa` | 5.8:1 | pass |
| each page | the panel edge on the page background | `#241f3d` | `#e6f3ff` | 13.9:1 | pass |
| each table | the head cell edge on the head fill | `#241f3d` | `#bfdfff` | 11.4:1 | pass |
| apps | the badge edge on the seaweed fill | `#241f3d` | `#6ee7b7` | 10.3:1 | pass |
| apps | the badge edge on the coral fill | `#241f3d` | `#ff6b6b` | 5.7:1 | pass |
| apps | the badge edge on the sunny fill | `#241f3d` | `#ffd93d` | 11.4:1 | pass |
| each page | the focus ring, the ink halo on the page background | `#241f3d` | `#e6f3ff` | 13.9:1 | pass |
| each page | the focus ring, the ink halo on the paper | `#241f3d` | `#ffffff` | 15.7:1 | pass |
| the band | the focus ring, the ink halo on the octo band | `#241f3d` | `#a78bfa` | 5.8:1 | pass |
| each page | the focus ring, the sunny line on the ink halo | `#ffd93d` | `#241f3d` | 11.4:1 | pass |
| users | the disabled pager edge on the page background | `#4b4763` | `#e6f3ff` | 7.8:1 | pass |
| manage | the invalid field ring on the field fill | `#ff6b6b` | `#ffffff` | 2.8:1 | fail |

The eight rows of the contrast table of `docs/design.md` keep their values. Each measured
value equals the value of #246, so that table stands with no change.

## The defects

| Defect | Where it goes |
| --- | --- |
| The press of a button keeps its 80 ms transition and its 3 px offset under `prefers-reduced-motion: reduce`. | issue #263 |
| The invalid field ring measures 2.8:1 on the field fill. | issue #264 |
| The panel caption runs past the panel edge at 360 px, on the users view by 7 px and on the elements view by 117 px. | the open MINOR row of `minor_issues/257.md` (the design lead, the five templates) |
| The disabled confirm button measures 3.1:1. WCAG 1.4.3 exempts an inactive component, and the rule of #251 does not. | the open MINOR row of `minor_issues/258.md` (`src/styles.css:219-223`) |
| `main#main-content` takes the full 7 px ink ring after the skip link. | the open MINOR row of `minor_issues/254.md`, which names #251 as the owner of the fix |
| The route pattern textarea names `add-app-error` in `aria-describedby` with no `aria-invalid`. | the open MINOR row of `minor_issues/258.md` (`add-app-form.html:45-47`) |
| The nav pills wrap under the wordmark and sit at the right edge at 360 px. | the open MINOR row of `minor_issues/256.md` (`app.css:79-81`), which asks #251 for the 360 px confirmation |

## The keyboard path in detail

The order of the stops follows the visual order on each page. Each stop shows the ring of
`docs/design.md`: a 3 px sunny outline, a 2 px offset, and a 7 px ink halo.

1. apps: the skip link, the wordmark, the Apps pill, the Manage apps pill, the pause
   pill, the table region, the app link, the first-pages link.
2. users: the same band stops, then the breadcrumb link, the pause pill, the filter
   field, the Filter button, the table region, and each user link.
3. manage: the same band stops, then the table region, Edit, Delete, the four fields of
   the add form, and the Add app button.
4. The edit form: the name field, the connection string field, the route pattern field,
   Save, Cancel.
5. The delete dialog: Cancel, the confirm field, Cancel. The cycle stays inside the
   dialog. No control behind the dialog takes the focus.

Shift+Tab gives the reverse order on each page. No stop traps the focus outside the
dialog. The ring of a link inside the table region stays complete at 360 px and at
1280 px.
