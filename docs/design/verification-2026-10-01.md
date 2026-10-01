# The hero contrast verification, 2026-10-01

This page records the contrast check of the hero of issue #288. The tool is
`node docs/design/contrast.mjs` (the WCAG 2.x formula); the script stays
unchanged (the correction of the brief review of #288). Three of the four
pairs already match an existing row of the script by colour, size, and
weight; the table below names that row. The fourth pair (the lead) shares
its colour pair with many existing rows, so its ratio is the same number.

## The result of each pair

| Hero element | Colour | Background | Size | Weight | Ratio | Floor | Result | Script row |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| The headline (`h1`) | `--ink` (`#241f3d`) | `--shallows` (`#e6f3ff`) | 48.83 px | 600 | 13.9:1 | 4.5:1 | pass | line 67, "the page title h1" |
| The lead | `--ink` (`#241f3d`) | `--shallows` (`#e6f3ff`) | 20 px | 400 | 13.9:1 | 4.5:1 | pass | the colour pair of line 67 and line 69 |
| The pilot line | `--ink-soft` (`#4b4763`) | `--shallows` (`#e6f3ff`) | 12.8 px | 400 | 7.8:1 | 4.5:1 | pass | line 68, "the refresh note" |
| The button text | `--on-bright` (`#241f3d`) | `--sunny` (`#ffd93d`) | 16 px | 600 | 11.4:1 | 4.5:1 | pass | line 95, "the primary button" |

Note on the method: the contrast ratio of a pair depends on the two
colours only, not on the font size or the weight. The size and the weight
decide the floor (3:1 for a large text, 4.5:1 for the rest); the issue
asks for 4.5:1 or above on each hero pair, so this table uses that floor
for every row.
