# The components of the monitor UI

This document lists each UI component of the monitor, in the cartoon look of D45. It
gives the purpose, the anatomy, the states, the accessibility contract, and the rules of
each one. A later implementer or reviewer reads this document in place of the templates.
`docs/design.md` holds the tokens; this document names the file and the line that use
each token, on `main` at commit `837e320`. A token keeps its `var()` form; no line below
repeats a hex value.

## The index

| Component | Selector or class | File | Issue |
| --- | --- | --- | --- |
| The band | `.band` | `monitor/frontend/src/app/app.css` | #247 |
| The wordmark | `.wordmark` (styled through `.brand`) | `monitor/frontend/src/app/app.html`, `app.css` | #247 |
| The mascot mark | `app-octo-mark` | `monitor/frontend/src/app/octo-mark/` | #247 |
| The nav pill | `a` under `nav[aria-label="Main"]` | `monitor/frontend/src/app/main-nav/` | #247 |
| The skip link | `.skip-link` | `monitor/frontend/src/app/app.css` | #247 |
| The banner strip | `.banner-text`, `.banner-row` | `monitor/frontend/src/app/banner/` | #247 |
| The breadcrumb | `nav[aria-label="Breadcrumb"]` | `monitor/frontend/src/app/breadcrumb-nav/` | #247 |
| The refresh line and the pause pill | `app-refresh-bar`, `app-pause-refresh-button` | `monitor/frontend/src/app/refresh-bar/`, `poll/pause-refresh-button.*` | #247 |
| The button | `.btn`, `.btn-primary`, `.btn-danger`, `.btn-small` | `monitor/frontend/src/styles.css` | #248 |
| The input and the textarea | `input`, `textarea` | `monitor/frontend/src/styles.css` | #248 |
| The field | `.field` | `monitor/frontend/src/styles.css` | #248, #385 |
| The hint | `.form-hint` | `monitor/frontend/src/styles.css` | #248, #385 |
| The error | `.form-error` | `monitor/frontend/src/app/manage/add-app-form.css` | #248, #383 |
| The panel and the scroll region | `.panel`, `.table-scroll` | `monitor/frontend/src/styles.css` | #249 |
| The table | `table`, `thead th`, `tbody tr:nth-child(even)` | `monitor/frontend/src/styles.css` | #249 |
| The status badge | `.badge` | `monitor/frontend/src/styles.css`, `status-format.ts` | #249 |
| The note under a badge | `.gap-text`, `.data-from`, `.error-text` | `monitor/frontend/src/styles.css` | #249 |
| The pager | `.pager` | `monitor/frontend/src/app/users/users.css` | #249 |
| The dialog | `dialog` | `monitor/frontend/src/styles.css` (the base rule, #385), `monitor/frontend/src/app/manage/delete-app-dialog.*` (`::backdrop`) | #248, #385 |
| The empty state | `app-empty-state` | `monitor/frontend/src/app/empty-state/` | #250 |
| The first-load row | `.first-load-row` | `monitor/frontend/src/app/app.css` | #250 |
| The keys panel | `.keys-panel`, `.key-row` | `monitor/frontend/src/app/manage/add-app-form.*` | #296 |
| The new-secret-key confirmation | `app-new-secret-key-dialog` | `monitor/frontend/src/app/manage/new-secret-key-dialog.*` | #296 |
| The sign-in card | `.signin-card` | `monitor/frontend/src/app/signin/signin.*` | #349 |

## The band

**Purpose.** The band shows the wordmark, the mascot, and the main nav as the one bold
element of each page (design principle 3, `docs/design.md:75`).

**Anatomy.**
- `<header class="band">` holds `.band-row` and `.account-menu` (`app.html:5-25`).
- `.band-row` holds `.brand` (the mark and the wordmark) and `<app-main-nav>`.
- `.account-menu` holds `.account-email` and the "Sign out" button, in its own
  row below `.band-row` (issue #360, `app.html:17-24`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Octo fill, ink text, a 3px ink border under the band | `var(--octo)`, `var(--ink)`, `var(--line-panel)` (`app.css:52-56`) |

**Accessibility.**
- Role: `banner` (the native role of `<header>` at the top level).
- Accessible name: none; the band carries no `aria-label`.
- Second cue beside color: the 3px ink border, so the band reads in forced-colors mode too.
- Target size: not applicable; the band itself is not a control.

**Rules.**
- Do keep ink text on the octo fill, so the band follows principle 2, ink text on each
  bright fill (`app.css:52-55`).
- Do not give `.band` a shadow or an outline. Only the bottom border marks it. The band
  stays the one bold element, and the table stays calm (`app.css:56`).

## The wordmark

**Purpose.** The wordmark names the product next to the mascot, inside the link to `/apps`.

**Anatomy.**
- `<span class="wordmark">Octometer</span>` inside `a.brand` (`app.html:7-10`).
- No `.wordmark` rule exists; the text takes the type rule of `.brand` (Fredoka 600 at
  `var(--text-xl)`) by inheritance (`app.css:76-85`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Fredoka 600 ink text, no underline | `var(--display)`, `var(--ink)` (`app.css:80-84`) |

**Accessibility.**
- Role: none of its own; it sits inside the link `a.brand`, whose accessible name is
  "Octo­meter" (the mark is `aria-hidden`, so only the text names the link).
- Second cue beside color: not applicable; the wordmark carries no state.
- Target size: not applicable to the link itself; the nav pill sets the 44px height of
  the band row (`main-nav.css:14-15`).

**Rules.**
- Do style the wordmark through `.brand`'s type rule; one place holds the font, the
  weight, and the size for the mark and the text together (`app.css:76-85`).
- Do not add a separate `.wordmark` rule; a second rule for the same text would give one
  control two places for its type (`app.css:76-85`).

## The mascot mark

**Purpose.** The octopus mark carries the cartoon voice at three sizes: 28px in the band,
48px on the first load, and 96px in an empty state (`docs/design.md:75`, an open finding
of `minor_issues/259.md`).

**Anatomy.**
- `<svg>` with a `size` input for the width and the height, a `.head` circle, two `.eye`
  circles, and eight arm paths (`octo-mark.html:1-15`, `octo-mark.ts:16-19`).
- The default size is `28` (`octo-mark.ts:18`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Octo head, ink strokes and eyes | `var(--octo)`, `var(--ink)` (`octo-mark.css:11-25`) |

**Accessibility.**
- Role: none; `aria-hidden="true"` and `focusable="false"` remove the mark from the
  accessibility tree and from the tab order (`octo-mark.html:5-6`).
- Accessible name: none; the mark carries no meaning of its own.
- Second cue beside color: not applicable; the mark is decorative.

**Rules.**
- Do keep `aria-hidden="true"` and `focusable="false"` on the `<svg>`, so a screen reader
  skips a mark with no meaning (`octo-mark.html:5-6`).
- Do not give the octo head its own fill inside the band. `styles.css` gives it the paper
  fill there instead. The octo fill would vanish on the octo background
  (`monitor/frontend/src/styles.css:370-372`).

## The nav pill

**Purpose.** The nav pill links to the Apps view or the Manage view, and marks the
current section.

**Anatomy.**
- `<nav aria-label="Main"><ul><li><a>...` for each of the two links (`main-nav.html:1-28`).
- The Apps link binds `[attr.aria-current]="appsCurrent()"`; the Manage link uses
  `routerLinkActive="current-section"` with `ariaCurrentWhenActive="page"`
  (`main-nav.html:4-8`, `:13-25`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Paper fill, ink border, pill radius | `var(--paper)`, `var(--line-control)`, `var(--radius-pill)` (`main-nav.css:11-24`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring | `var(--sunny)` (`monitor/frontend/src/styles.css:131-135`) |
| Current | Sunny fill, ink text, a 3px underline | `var(--sunny)`, `var(--on-bright)` (`main-nav.css:30-36`) |

**Accessibility.**
- Role: `link` (native `<a>`).
- Accessible name: the link text, "Apps" or "Manage apps".
- Second cue beside color: the underline on the current pill (`main-nav.css:33-35`).
- Target size: `min-width: 24px; min-height: 44px` (`main-nav.css:14-15`).
- `aria-*` that changes: `aria-current` — `"page"` on the exact `/apps` view, `"true"` on
  a view below it, absent elsewhere (`main-nav.ts:37-44`).

**Rules.**
- Do give the current pill a second cue beside color, an underline next to the sunny
  fill, per WCAG 1.4.1 (`main-nav.css:30-36`).
- Do not mark a descendant page with `aria-current="page"`. Use `"page"` for the exact
  page only. Use `"true"` for a page below it in the section, so the token stays accurate
  (`main-nav.ts:37-44`).

## The skip link

**Purpose.** The skip link moves keyboard focus straight to the main content, before the
band.

**Anatomy.**
- `<a class="skip-link" href="#main-content" (click)="onSkipLinkActivated($event)">`, the
  first element of `app.html` (`app.html:1-3`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Off-screen by a transform, not by `display` or `visibility` | (transform only; `app.css:23-38`) |
| Focus | A sunny pill, 8px clear of the top-left corner | `var(--sunny)`, `var(--on-bright)` (`app.css:32-36`, `:40-44`) |

**Accessibility.**
- Role: `link`.
- Accessible name: "Skip to the main content" (`app.html:2`).
- Second cue beside color: the ink border of the pill (`app.css:35`).
- Target size: `min-width: 24px; min-height: 24px` (`app.css:30-31`).

**Rules.**
- Do move the link off-screen with `transform: translateY(-100%)`, never with
  `display: none` or `visibility: hidden`, so a screen reader still reaches it
  (`app.css:23-38`).
- Do not place the focused link at `top: 0; left: 0`. Give it an 8px offset from each
  edge. The outer focus ring then does not clip at the page corner (fixed finding of
  `minor_issues/254.md`, `app.css:40-44`).

## The banner strip

**Purpose.** The banner reports a failed monitor API call across the whole app.

**Anatomy.**
- `.banner-slot` keeps no reserved height (issue #375, the audit finding D7).
  `.banner-text` shows a `⚠` mark and the message inside `.banner-row`, in the
  normal page flow, when `apiError()` holds a value (`banner.html:1-10`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Empty | No fill, no reserved height | (no token; `banner.css:6-9`) |
| Shown | Full-width coral fill, ink text and border, in the flow above the breadcrumb, text on the page column | `var(--coral)`, `var(--on-bright)`, `var(--line-control)` (`banner.css:17-25`) |

**Accessibility.**
- Role: none of its own; the text sits in a plain `<p>`, outside each landmark (an open
  point of `minor_issues/256.md`).
- Accessible name: not applicable; the text is the content.
- Second cue beside color: the `⚠` mark, `aria-hidden`, next to the ink text
  (`banner.html:5`).
- Live region: the one `role="status"` region of `app.html:31` announces the transition
  through `Announcer` (`app.ts:114-123`), not the banner itself.

**Rules.**
- Do keep the coral fill full width, but keep the mark and the text on the same 76rem
  column as the band row and `main` (rule: "a full-width strip keeps its text on the page
  column", `banner.css:14-24`).
- Do not give the banner its own live region. The one status region of the shell already
  announces each transition. A second region would announce the same fact twice
  (`app.html:31`, `app.ts:114-123`).
- Do keep the banner in the normal flow (no `position: absolute` on `.banner-text`), so
  a banner that shows pushes the breadcrumb and the page content down by its own
  height, and the breadcrumb stays reachable and keeps its focus ring at all times
  (issue #375, the audit finding D7, the correction round of pull request #404).
- Do give `.banner-text` a maximum height, with `overflow-y: auto`, so a long message
  scrolls inside the banner instead of growing over the page (`banner.css:17-25`).

## The breadcrumb

**Purpose.** The breadcrumb shows the trail of the active route, under the band.

**Anatomy.**
- `<nav aria-label="Breadcrumb"><ol><li>` for each item; the last item is a plain `<span
  aria-current="page">`, an earlier item with a path is a link, else a plain span
  (`breadcrumb-nav.html:1-19`).
- No `nav` element at all when the trail holds one item with `path: null`: the current
  page alone, which would only repeat the `<h1>` below it (issue #346). The apps page,
  the manage page, and the not-found page show no breadcrumb; a users view, a sessions
  view, a first-pages view, and an elements view keep theirs.
- The app-name item (issue #270): the second item of a level 2 or a level 3 trail shows
  the app name, not the app id. `app-names.ts` holds the name of each app id, and the
  shell (`app.ts`) resolves the label of this item through the store, with the fallback
  text "App <id>" until the name arrives. The item links to the users view of that app
  (`/apps/<id>/users`), except on the users view itself, where the item is the current
  page and carries no link. A Sessions item of the elements trail links to the sessions
  view with the query parameter `anonymous=true` (`breadcrumb-nav.html:9`, the
  `[queryParams]` binding beside `[routerLink]`).
- The not-found case of a level 2 or a level 3 view (issue #348): a 404 answer of the
  app-name request drops the app-crumb item, in place of the fallback label "App <id>".
  The users view then shows no breadcrumb at all, because the trail falls to one item
  (`app.ts`, `withResolvedName`, `breadcrumbItems`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Ink-soft text, `›` separator | `var(--ink-soft)` (`breadcrumb-nav.css:25-30`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring, on a link item | `var(--sunny)` (`monitor/frontend/src/styles.css:131-135`) |
| Current | Bold ink text, no link | `var(--ink)` (`breadcrumb-nav.css:39-42`) |

**Accessibility.**
- Role: `navigation`, named "Breadcrumb".
- Accessible name of the current item: the item text; `aria-current="page"` on a plain
  `<span>`, not on a link (`breadcrumb-nav.html:6-7`).
- Second cue beside color: the bold weight of the current item (`breadcrumb-nav.css:39-42`).
- Target size: `min-width: 24px; min-height: 24px` on each link (`breadcrumb-nav.css:32-37`).

**Rules.**
- Do keep the pseudo-element separator's empty alternative text, `content: '\203a' /
  ''`, so the separator stays out of the accessibility tree (rule: "a pseudo-element
  separator keeps the `/ ''` alternative text", `breadcrumb-nav.css:25-30`).
- Do not give the current item a link; a plain `<span aria-current="page">` marks the
  page without a control that goes nowhere (`breadcrumb-nav.html:6-7`).

## The refresh line and the pause pill

**Purpose.** The refresh line shows the poll interval and lets the user pause or resume
the automatic refresh.

**Anatomy.**
- `<app-pause-refresh-button>` then `<p>{{ intervalText() }}</p>`, in one flex row that
  wraps (`refresh-bar.html:1-2`, `refresh-bar.css:6-16`).
- The button: `<button [attr.aria-pressed]="pressed()" (click)="toggle()">Pause
  refresh</button>` (`pause-refresh-button.html:1`).
- The host keeps a bottom margin of `var(--space-4)` (16px), so a gap stays above the
  panel of the view (`refresh-bar.css:6-16`, #271).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Paper pill, a hidden check mark that holds its space | `var(--paper)` (`pause-refresh-button.css:9-26`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring | `var(--sunny)` (`monitor/frontend/src/styles.css:131-135`) |
| Pressed | Sunny fill, visible check mark | `var(--sunny)`, `var(--on-bright)` (`pause-refresh-button.css:29-36`) |

**Accessibility.**
- Role: `button`.
- Accessible name: "Pause refresh" (the label stays the same text in both states).
- Second cue beside color: the check mark `'\2713\00a0' / ''`, present only when pressed
  (`pause-refresh-button.css:22-26`, `:34-36`).
- Target size: `min-width: 24px; min-height: 44px` (`pause-refresh-button.css:12-13`).
- Live region: a state change announces "Refresh paused" or "Refresh resumed" through
  the one status region (`refresh-bar.ts:57`).
- `aria-*` that changes: `aria-pressed` — `"true"` or `"false"` (`pause-refresh-button.html:1`).

**Rules.**
- Do keep the check-mark cue of the pause button in both states, so the pressed state
  never relies on the sunny fill alone (rule: "the pause button keeps its check-mark
  cue", `pause-refresh-button.css:22-26`).
- Do not expect the shared `.btn` class of #248 to reach this button. The pill needs its
  own scoped rule. Angular's component scope keeps this rule out of reach, so a later
  shared rule cannot correct it (`pause-refresh-button.css:1-8`).

## The button

**Purpose.** `.btn` gives every clickable control of the manage view and the dialog the
same cartoon shape.

**Anatomy.**
- `.btn`, plus `.btn-primary`, `.btn-danger`, and `.btn-small` for the variants; `[disabled]`
  for the disabled state (`monitor/frontend/src/styles.css:293-336`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Paper fill, ink border, hard shadow | `var(--paper)`, `var(--line-control)`, `var(--shadow-control)` (`styles.css:293-301`) |
| Focus | Its own shadow plus the 7px ink ring, combined | `var(--shadow-control)` (`styles.css:338-342`) |
| Active | Moved 3px, no shadow | (no token; `styles.css:308-311`) |
| Disabled | Shallows fill, ink-soft text, a dashed border, no shadow, no pointer | `var(--shallows)`, `var(--ink-soft)` (`styles.css:341-347`) |

**Accessibility.**
- Role: `button` (native `<button>`).
- Accessible name: the button text ("Add app", "Save", "Cancel", "Delete this app", …).
- Second cue beside color: the ink border and the hard shadow, on every fill
  (`styles.css:299-301`); the dashed border, on the disabled fill (`styles.css:341-347`).
- Target size: `min-height: 44px` (`styles.css:295`).

**Rules.**
- Do give `.btn:focus-visible` a combined `box-shadow`: its own control shadow, then the
  7px ink ring. A control with its own shadow then does not replace the shared ring
  (rule: "a state rule that sets `box-shadow` repeats the focus ring", `styles.css:338-342`).
- Do keep the dashed border of `.btn[disabled]`. It carries the disabled state without
  the hue, also in forced colors (fixed finding of `minor_issues/258.md`,
  `styles.css:341-347`).

## The input and the textarea

**Purpose.** The input and the textarea collect the fields of the add-app form, the
edit-app form, the delete confirmation, and the user filter.

**Anatomy.**
- The shared rule targets `input:not([type='checkbox']):not([type='radio'])`, `textarea`,
  and `select` (`monitor/frontend/src/styles.css:344-355`).
- The connection-string field keeps `type="password"` and `autocomplete="new-password"`,
  so the value never shows in plain text and the browser stores no guess of it
  (`add-app-form.html:9-14`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Paper fill, ink border | `var(--paper)`, `var(--line-control)` (`styles.css:349-354`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring | `var(--sunny)` (`styles.css:131-135`) |
| Invalid | An inset coral ring, plus the ink ring on focus | `var(--coral)` (`add-app-form.css:17-31`) |
| Invalid, forced colors | A 3px dashed outline, a 3px solid outline on focus | `currentColor` (`add-app-form.css:33-50`, issue #266) |

**Accessibility.**
- Role: `textbox` (native `input`/`textarea`).
- Accessible name: the `<label for="...">` of the field.
- Second cue beside color: the inset coral ring keeps its own shape, next to the
  `aria-invalid` state (not color alone).
- Target size: `min-height: 44px` (`styles.css:349`).
- `aria-*` that changes: `aria-invalid` — `"true"` once an error shows, else absent
  (`add-app-form.html:21`); `aria-describedby` — adds the error id once an error shows
  (`add-app-form.html:16-20`).

**Rules.**
- Do give `[aria-invalid='true']:focus-visible` both shadows: the coral inset ring, then
  the 7px ink ring. The invalid rule's higher specificity would otherwise replace the
  shared ring (rule: "a state rule that sets `box-shadow` repeats the focus ring",
  `add-app-form.css:21-31`).
- Do not let the shared `width: 100%` rule stand alone in a flex-wrapped row. The filter
  input then pushes its label and its button to a line of their own (open finding of
  `minor_issues/258.md`, `styles.css:344-349` with `users.css:74`).

## The hint

**Purpose.** The hint gives an example under a field, so the user fills it without a
separate guide.

**Anatomy.**
- `<p id="..." class="form-hint">{{ ... }}</p>`, tied to its field by `aria-describedby`
  (`add-app-form.html:19`).
- The rule is global (`monitor/frontend/src/styles.css`, issue #385); no component style
  file holds its own copy.
- The connection-string hint text lives once in `field-hints.ts`, so the add form and the
  edit form show the same words for the field they share (`manage/field-hints.ts:1-17`).
  Issue #299 (D5, D40) brings the route pattern hint back, under the editable field (see
  "The route patterns field" below); it ends the read-only, published-list form of design
  decision D48 (issue #277).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Ink-soft text, small size | `var(--ink-soft)`, `var(--text-s)` (`styles.css`) |

**Accessibility.**
- Role: none; a plain `<p>`.
- Accessible name: not applicable; the hint names no control of its own.
- Live region: none; the hint is static text, read once through `aria-describedby`.

**Rules.**
- Do set the margin in the hint's own rule, `margin: 4px 0 0`, because the base sheet
  resets no `<p>` margin (rule: "a paragraph inside a new rule sets its margin",
  `styles.css`).

## The error

**Purpose.** The error names why a form submit failed, next to the field it blames.

**Anatomy.**
- `<p id="..." class="form-error">⚠ {{ message }}</p>`, shown only while `errorMessage()`
  holds a value (`add-app-form.html:22-24`). The leading glyph is plain text, not CSS
  generated content, so a screen reader and a `textContent` read both carry it
  (issue #383).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Shown | A 3px coral-deep left border, bold ink text, a leading "⚠" glyph | `var(--coral-deep)`, `var(--ink)` (`add-app-form.css`) |

**Accessibility.**
- Role: none; a plain `<p>`.
- Accessible name: not applicable; the text is the content.
- Second cue beside color: the bold weight and the "⚠" glyph, next to the coral-deep
  border (3:1 or more on `--paper` and on `--shallows`, WCAG 1.4.11, issue #383). The
  text itself stays ink, not coral, because coral text on paper measures 2.78:1, under
  the WCAG 1.4.3 floor. This is the accepted departure of the #254 design review.
- `aria-*` that changes: the field's `aria-describedby` adds this paragraph's id once it
  shows (`add-app-form.html:14-18`).

**Rules.**
- Do keep the error text in ink, not coral, so the message stays above 4.5:1 on the
  paper fill (`add-app-form.css`, the accepted departure of the #254 design review).
- Do not omit the margin reset when a shared rule changes. `.form-error` sets
  `margin: 8px 0 0` in its own rule, because the base sheet resets no `<p>` margin (rule:
  "a paragraph inside a new rule sets its margin", `add-app-form.css`).
- Do use `var(--coral-deep)` for the left border, never `var(--coral)`: `--coral` on
  `--shallows` (the sign-in page background) measures 2.46:1, under the 3:1 floor of
  WCAG 1.4.11 (issue #383).

## The route patterns field

**Purpose.** The edit form lets the owner type the route pattern list of the app, so
`/orders/1` and `/orders/2` count as one page with no kit (issue #299, D5, D40; it ends the
read-only, published-list form of design decision D48, issue #277).

**Anatomy.**
- A `<div class="field">` under the origins field: a `<label>` "Route patterns (one per
  line)", a `<textarea>`, and a `<p class="form-hint">` with the example and the default
  rule (`edit-app-form.html:31-41`).
- The hint text lives in `ROUTE_PATTERNS_HINT` (`manage/field-hints.ts`), the example text
  of issue #248 (`/orders/:id`, `/users/:userId/settings`) and the sentence "A path outside
  the list gets the default rule: a number, a UUID, or a hex segment becomes `:id`."
- The field reads `GET /api/apps/{id}` when the form opens, the same request that fills the
  name field and the origins field (`edit-app-form.ts`, `open()`).
- The save order is the origins, then the route patterns, then the name (the correction of
  the brief review of 2026-10-01, MAJOR 2 of #323): a rename never ships ahead of a
  rejected list. `PUT /api/apps/{id}/routes` carries the typed list, `{"patterns": [...]}`.
  An empty field clears the list; the collector's default rule then applies to every path.

**States.**

| State | Cue |
| --- | --- |
| A stored list | The textarea holds one pattern per line |
| No list yet | The textarea is empty |
| A failed read | The field stays empty; a `.form-error` message shows next to the form,
  and Save stays enabled (the save skips its own route pattern request, so an empty field
  never clears the app's real list) |
| A rejected list | The textarea keeps the typed text; a `.form-error` message names the
  index and the rule, never the pattern text (issue #226) |

**Accessibility.**
- Role: none beyond the native `<textarea>` and `<p>` roles.
- Accessible name: the `<label>` text, through `[for]`.
- Live region: none; the field re-renders with the rest of the form on open.

**Rules.**
- Do send the typed list through text interpolation only, no `[innerHTML]`, no
  `[attr.*]`, and no `DomSanitizer.bypass*` (design decision D40): a kept `*` segment can
  hold an escaped markup text.

## The panel and the scroll region

**Purpose.** The panel frames one data table or one form in a comic panel; the scroll
region lets a keyboard or a touch user scroll a wide table sideways.

**Anatomy.**
- `<section class="panel">` wraps `<div class="table-scroll" tabindex="0" role="region"
  aria-labelledby="...">`, which wraps the `<table>` (`apps.html:23-24`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Paper fill, ink border, hard shadow, rounded corner | `var(--paper)`, `var(--line-panel)`, `var(--shadow-panel)`, `var(--radius-panel)` (`monitor/frontend/src/styles.css:186-192`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring, on the scroll region | `var(--sunny)` (`styles.css:131-135`) |

**Accessibility.**
- Role: `region`, named by the table caption id (`apps.html:24`).
- Accessible name: the caption text, through `aria-labelledby`.
- Target size: the whole scroll region is the tab stop; it carries no size floor of its
  own.

**Rules.**
- Do not set `overflow: hidden` on `.panel`. That container holds `.table-scroll`, a
  focusable child that sits at its edge. The clip would remove the child's focus ring
  (fixed BLOCKER of the #257 and the #258 design reviews; rule: "no `overflow: hidden` on
  a container with a focusable child at its edge", `monitor/frontend/src/styles.css:186-192`).
- Do give `.table-scroll` `border-radius: inherit` and `position: relative`, so the rows
  still clip to the panel corner and a `.visually-hidden` child does not escape it
  sideways (`styles.css:200-204`, `apps.css:57-60`).

## The table

**Purpose.** The table lists the rows of one view: the apps, the users, the manage list,
and the rest.

**Anatomy.**
- `<table><caption>...</caption><thead>...<tbody>...</tbody></table>`, with `scope="col"`
  and `scope="row"` on each header cell (`apps.html:26-45`).
- `.num` marks a right-aligned, tabular-figure cell (`apps.html:32-37`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest (odd row) | No fill | `var(--paper)`, inherited |
| Alternate (even row) | A light tint on every cell of the row | `var(--shallows)` (`monitor/frontend/src/styles.css:236-238`) |
| Hover or focus-within | A deeper tint on every cell of the row | `var(--shallows-deep)` (`apps.css:73-76`) |
| Header | A deeper fill, Fredoka 600 | `var(--shallows-deep)`, `var(--display)` (`styles.css:225-230`) |

**Accessibility.**
- Role: `table` (native, no `display` value on any table element).
- Accessible name: the `<caption>` text.
- Second cue beside color: none needed. The zebra and the hover tints carry no meaning
  on their own; they mark a visual group only.

**Rules.**
- Do target the row hover at `tr:hover > *` and `tr:focus-within > *`, because the zebra
  rule already paints a background on each cell above the row (rule: "a zebra tint on the
  cells needs the hover on the cells", `apps.css:67-76`, `styles.css:236-238`).
- Do not assert a shared class by name alone when a test needs the real value. A test
  that reads a token from a computed style names the exact value, for example
  `toBe('var(--shallows-deep)')` (rule: "a global class is asserted by name in a unit
  test, a computed value needs a component rule"; open finding of `minor_issues/254.md`,
  `apps.spec.ts:604`).

## The status badge

**Purpose.** The status badge shows the health of one app: a mark, a word, and a color,
together.

**Anatomy.**
- `<span class="badge" [class]="statusBadgeClass(row.status)"><span
  aria-hidden="true">{{ mark }}</span>{{ word }}</span>` (`apps.html:73-76`).
- `status-format.ts` gives the mark, the word, and the badge class of each of the seven
  status values (`status-format.ts:4-34`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| OK | `✓` mark, "OK" word, seaweed fill | `var(--seaweed)` (`status-format.ts:16`, `:27`, `styles.css:258-260`) |
| Not polled (`badge-none`) | `–` mark, "Not polled yet" word, shallows-deep fill | `var(--shallows-deep)` (`styles.css:277-280`) |
| Overprivileged (`badge-warn`) | `⚠` mark, "Overprivileged" word, sunny fill | `var(--sunny)` (`styles.css:262-264`) |
| Unreachable, unauthorized, invalid data, error (`badge-bad`) | `⚠` mark, the status word, coral fill | `var(--coral)` (`styles.css:266-268`) |

**Accessibility.**
- Role: none; a plain `<span>`.
- Accessible name: not applicable; the word is the content, read after the hidden mark.
- Second cue beside color: the mark plus the word, next to the fill, on every status
  (rule: "a status is a mark plus a word plus a color", `status-format.ts:4-23`).
- Target size: not applicable; the badge is not a control.

**Rules.**
- Do give every status a mark, a word, and a color together, never the color alone
  (rule: "a status is a mark plus a word plus a color", `apps.html:73-76`).
- Do not give the mark any meaning of its own. `aria-hidden="true"` keeps it out of the
  accessibility tree, so a screen reader reads the word only (`apps.html:74`).
- Do reuse `badge badge-ok` for a plain inline success message outside a status cell,
  for example the sign-in page's "Check your mail." text (issue #301, `signin.html`).

## The note under a badge

**Purpose.** The note gives one extra line under a status badge: a data gap, the time of
the last success, or the last error text.

**Anatomy.**
- `<p class="gap-text">`, `<p class="data-from">`, or `<p class="error-text">`, each
  after the badge span, inside the same status cell (`apps.html:74-90`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Ink-soft text, small size, a top margin | `var(--ink-soft)`, `var(--text-s)` (`monitor/frontend/src/styles.css:282-289`) |

**Accessibility.**
- Role: none; a plain `<p>`.
- Accessible name: not applicable; the text is the content, read after the badge.

**Rules.**
- Do give the shared note rule its own margin, `margin: 4px 0 0`, so the browser default
  bottom margin never returns (rule: "a paragraph inside a new rule sets its margin",
  `styles.css:282-289`).
- Do not leave `.error-text` outside the shared note rule. Today it keeps its own line
  spacing, while `.gap-text` and `.data-from` share one rule. One status cell can then mix
  two note styles (open finding of `minor_issues/257.md`, `apps.css:50-53` against
  `styles.css:283-289`).

## The pager

**Purpose.** The pager moves the user list one page back or forward.

**Anatomy.**
- `<nav aria-label="Pages of the user list">` holds a "Previous" button and a "Next"
  button, each `class="btn btn-small"` (`users.html:68`, `:77`), plus a plain
  `<span>Page X of Y</span>`, with no `aria-current` attribute (`users.html:66-84`).
- The `@if` block shows the pager only when the list holds more than one page
  (`users.ts`, `first-pages.ts`, `sessions.ts`, D15 of the UI audit of 2026-10-09, issue
  #387). A one-page result shows no pager and no pair of disabled buttons. The
  maintainer's decision on the review of pull request #405 widens the rule to the
  anonymous-sessions view, the third pager of the dashboard.

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | Small paper button | `var(--paper)` (`styles.css:293-301`), small size (`styles.css:327-330`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring | `var(--sunny)` (`monitor/frontend/src/styles.css:131-135`) |
| Disabled (at a boundary) | Ink-soft text and border, no pointer, still reachable by Tab | `var(--ink-soft)`, `var(--shallows)` (`users.css:100-112`) |

**Accessibility.**
- Role: `navigation`, named "Pages of the user list"; each button keeps the native
  `button` role.
- Accessible name: "Previous" and "Next".
- Second cue beside color: the lower-contrast text and border of the disabled state,
  next to the missing pointer cursor.
- Target size: `min-width: 24px; min-height: 24px` on each button (`users.css:83-91`).
- Live region: the one `role="status"` region announces the row count plus "Page X of Y"
  after each page change. The focus stays on the button that the user pressed. The
  announced text is then the only feedback for a screen reader (rule: "one place for one
  control", D33; `users.ts:309-318`, fixed finding of PR #159 and PR #216 per the module
  brief). The page part stays out of the announced text for a one-page answer, so the
  spoken text agrees with the hidden pager (MAJOR 3 of the review of pull request #405).

**Rules.**
- Do keep `aria-disabled`, not the native `disabled` attribute, on a boundary pager
  button, so it stays in the tab order (`users.css:107-112`, `users.html:71-72`).
- Do not open a second live region for the pager. The one status region of `app.html:31`
  carries the row count and the page text. A second region would repeat the
  announcement (rule: "one place for one control", `users.ts:314-318`).

## The dialog

**Purpose.** The dialog confirms a delete of one app, over a dimmed backdrop.

**Anatomy.**
- `<dialog #dialogEl [attr.aria-labelledby]="headingId">` with a heading, a confirmation
  paragraph, a name field, and an action row (`delete-app-dialog.html:5-44`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest (open) | Paper-raised fill over a dimmed backdrop, hard shadow | `var(--paper-raised)`, `var(--backdrop)`, `var(--shadow-panel)` (the base rule in `styles.css`, #385; `::backdrop` in `delete-app-dialog.css`) |
| Focus | The shared 3px sunny outline plus the 7px ink ring | `var(--sunny)` (`monitor/frontend/src/styles.css:131-135`) |
| Disabled (confirm button) | Shallows fill, ink-soft text, a dashed border, until the typed name matches | `var(--shallows)`, `var(--ink-soft)` (`styles.css:341-347`) |

**Accessibility.**
- Role: `dialog`, `aria-modal="true"` implied by the native `<dialog>` element.
- Accessible name: the heading text, "Delete `<app>`?" (`aria-labelledby`).
- Second cue beside color: the ink border and the hard shadow, next to the dimmed
  backdrop (the base `dialog` rule, `styles.css`, #385).
- Target size: `min-height: 44px` on the trigger and on each dialog button
  (`delete-app-dialog.css:46-48`, `styles.css:293-336`).

**Rules.**
- Do give the row trigger its own scoped `min-height: 44px` copy, because vitest links
  no global sheet in a unit test (rule: "a global class is asserted by name in a unit
  test, a computed value needs a component rule", `delete-app-dialog.css:46-48`). The
  confirm button takes its 44px from the global `.btn` rule instead (`styles.css:295`).
- Do not add `.btn-danger` to the row Delete trigger. The coral fill stays on the one
  confirm action. A status badge and a row action then never share the same color for
  two different meanings (fixed MAJOR of the #258 design review,
  `delete-app-dialog.html:1-3`).

## The keys panel

**Purpose.** The panel shows the app key and the secret key one time, right after a
create or a rotation, so the owner can copy the secret key before it stops showing
(issue #296, design decision D51).

**Anatomy.**
- `<section class="panel keys-panel">` with two `<div class="field">` blocks (the app
  key, the secret key), each a `<div class="key-row">` of a readonly `<input>` plus a
  "Copy" button, then the sentence "Copy the secret key now. It shows one time.", then
  the tracker script tag in a `<pre><code>` block (`add-app-form.html:33-62`).
- The "Copy" button calls the Clipboard API (`navigator.clipboard.writeText`); the
  readonly, selectable `<input>` is the fallback when the API is absent or denied
  (`add-app-form.ts`).
- The script tag reads `octometer.publicUrl` through `Me.publicUrl` (`GET /api/me`),
  or `location.origin` when that value is blank (`add-app-form.ts`).

**States.**

| State | Cue |
| --- | --- |
| Before a create | The panel is absent |
| After a create or a rotation | The panel shows the app key and the secret key |
| After a copy | The button text reads "Copied" in place of "Copy" |

**Accessibility.**
- Role: none beyond the native `<input>`, `<button>`, and `<pre>` roles.
- Live region: `aria-live="polite"` on the panel, so a screen reader announces the new
  keys once they render (`add-app-form.html:33`).
- Target size: `min-height: 44px` on each "Copy" button, through the global `.btn`
  rule.

**Rules.**
- Do keep the secret key out of every log line and every error text (design decision
  D15, D58); the sentinel test of `ConfigLoaderTest.kt` proves it for one real
  registration.
- Do not add a second route or a second request to read the secret key again; the
  create and the rotation answers are the one and only time it exists in a response
  body.

## The new-secret-key confirmation

**Purpose.** The confirmation asks before it replaces the secret key of one app,
because the old key stops working at once (issue #296, design decision D51); the
dialog pattern is the one of "The dialog" above, with a yes/no question in place of a
typed name.

**Anatomy.**
- `<dialog #dialogEl [attr.aria-labelledby]="headingId">`, with the confirm question
  and its actions, or, after a confirm, the new secret key in the same `.key-row` form
  as the keys panel, plus a "Done" button (`new-secret-key-dialog.html`).

**States.**

| State | Cue |
| --- | --- |
| The confirm question | The heading "Replace the secret key of `<app>`?" with a warning sentence and the Cancel and "Replace the secret key" actions |
| The new key | The heading "New secret key for `<app>`", the key-row field, and "Done" |

**Accessibility.**
- Role: `dialog`, the same as "The dialog" above.
- Accessible name: the heading text of the active state, through `aria-labelledby`.
- Target size: `min-height: 44px` on the trigger (`new-secret-key-dialog.css`).

**Rules.**
- Do default the dialog's focus to Cancel on open, never to the confirm action (the
  same destructive-action rule as "The dialog" above).
- Do not reuse the trigger's accessible name ("New secret key") for the confirm
  button inside the dialog; "Replace the secret key" keeps the two controls distinct
  for an assistive technology and for a test.

## The sign-in card

**Purpose.** The card puts the sign-in form into the same comic panel as every other
form of the dashboard, and tells the visitor what the "Send me a link" button does
(issue #349).

**Anatomy.**
- `<section class="panel signin-card">` wraps the sentence "We send you a mail with a
  link. The link works for 15 minutes.", then the email form, the sent-state badge, and
  the form error (`signin.html`).
- `.signin-card` adds the padding and the 28rem width, the same way `.add-app` adds them
  to the manage panel (`manage.css`); `.panel` is the global class (`styles.css`).
- The title "Sign in" and the skip-link target stay outside the card, above it
  (`signin.html`).

**States.**

| State | Cue |
| --- | --- |
| The plain form | The email field and the "Send me a link" button, inside the card |
| After a submit | The badge "Check your mail.", inside the card, in place of the form |
| A failed submit | The form stays, with `.form-error` under the field, inside the card |

**Accessibility.**
- Role: none beyond the native `<section>`, `<form>`, `<input>`, and `<button>` roles.
- The `h1` keeps `tabindex="-1"`, so a route change still moves focus to the page title,
  above the card (`signin.ts`).

**Rules.**
- Do keep every text of the form, the sent state, and the error state; the card changes
  the layout, not the words (issue #349).
- Do not put the confirm-token view (the "Sign in" button after a visitor opens the
  mail link) into this card; that view is a different step of the flow.

## The empty state

**Purpose.** The empty state shows the mascot and one sentence, in place of a table with
no row.

**Anatomy.**
- `<app-octo-mark [size]="96" />` then one `<p class="empty-text">` with an optional link
  inside the sentence (`empty-state.html:1-10`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | 96px mark, ink text, a left-aligned column | `var(--ink)`, `var(--space-4)`, `var(--space-5)` (`empty-state.css:5-16`) |

**Accessibility.**
- Role: none of its own; the mark is `aria-hidden`, so a screen reader reads the sentence
  and the link only.
- Accessible name of the link: `linkText()`, inside the sentence.
- Target size: `min-width: 24px; min-height: 24px` on the link (`empty-state.css:31-36`).

**Rules.**
- Do wrap the empty state in `<section class="panel">` in every view, so the mascot never
  floats on the page background alone (fixed BLOCKER of the #259 design review,
  `manage.html:5-7`).
- Do not give `.empty-text a` an unbounded `inline-flex` box. A label over about 32
  characters can overflow the paragraph at 360px, because an atomic inline box does not
  wrap (open finding of `minor_issues/259.md`, `empty-state.css:31-36`).

## The first-load row

**Purpose.** The first-load row shows the mascot with a loading sentence, before the
first route renders.

**Anatomy.**
- `<div class="first-load-row"><app-octo-mark [size]="48" /><p class="first-load">Octometer
  is loading…</p></div>`, shown while `!appReady()` (`app.html:21-26`, `app.ts:44-45`,
  `:77-79`).

**States.**

| State | Cue | Token |
| --- | --- | --- |
| Rest | 48px mark before the text, one flex row | `var(--space-3)` (`app.css:2-7`) |

**Accessibility.**
- Role: none; a plain `<div>` and `<p>`.
- Accessible name: not applicable; the sentence is the content.
- Live region: none of its own; the row disappears once the router outlet activates a
  view, before any status region would need to announce it.

**Rules.**
- Do keep the mark at 48px before the text, in one row, so the loading state uses the
  same mascot as the band and the empty state (`app.css:2-7`).
- Do not use a literal like `1rem` for the row's padding where a space token exists. The
  rule keeps `var(--space-4)` instead (open finding of `minor_issues/259.md`,
  `app.css:6`).
