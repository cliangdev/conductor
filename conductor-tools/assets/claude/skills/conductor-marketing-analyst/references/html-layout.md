# HTML layout

Structure and mechanics for the HTML page. Content rules live in `SKILL.md`; this file is layout only.

## Page skeleton

One `<main>` with ten `<section id="s1">` to `<section id="s10">`, each with an `<h2>`, in the order of the
skill. A slim sticky nav lists the ten anchors. Max width about 960px, 16px side gutter, no horizontal page
scroll at phone width (wide tables scroll inside their own wrapper `div` with `overflow-x:auto`).

Colours as CSS variables on `:root`, redefined under `@media (prefers-color-scheme: dark)` (guarded by
`:root:not([data-theme="light"])`) and under `:root[data-theme="dark"]`. Give `body` an explicit background.
Status colours (also say it in text, never colour alone):

| Status | Label | Variable |
|---|---|---|
| ON_TRACK | On target | `--ok` |
| WATCH | Close | `--warn` |
| OFF_TRACK | Off target | `--bad` |
| TOO_EARLY | Too early | `--muted` |
| NO_TARGET | No target | `--muted` |

## Sections

1. **Header.** `<h1>` title, a line "Window <from> to <to>, data complete through <date>" (the earliest
   `dataCompleteThrough` among connected sources; name any source that lags), and the summary paragraph.
2. **Tiles.** A grid of cards from `headline[]`: label, big value, "target X", status chip, change vs
   `previous` only if it is outside noise (see skill).
3. **Acquisition table.** One row per `acquisition[]` item. Final column "What it means": one or two
   sentences from the numbers. Unconnected channels show their status and dashes.
4. **Funnel.** Horizontal bars, one per stage, width proportional to `value`, `dropPct` printed between
   stages, the `biggestDrop` stage outlined in `--bad` with a short callout. State the `basis` (COHORT: each
   day counts people who first opened that day, followed to now).
5. **Daily chart.** Inline SVG (or d3 from cdnjs at a pinned version): bars for `storeImpressions` on the left
   axis, a line for `adSpend` on the right axis. Provisional days hatched or lighter and listed in a caption.
   Axis labels, a legend, and a text summary for screen readers (`<title>`/`<desc>`).
6. **Benchmarks table.** Measure, our value with counts (`sampleSize`), benchmark with `rangeText` and
   `segment`, the read ("at 61% of benchmark" from `ratio`; "too early to judge" when `judged` is false),
   source as a link to `sourceUrl` with "checked <date>". A `stale` row carries a "stale" tag. Caption:
   comparisons under 20 people are shown but not judged.
7. **Actions.** An ordered list. Each item: number, title, status chip (in progress / decided / done /
   blocked / waiting), "Why" with numbers, "Owner", "Review on", "Evidence" as source links. Items from
   `changes` carry their `displayId`.
8. **Scorecard table.** key, label, area, value, target, status chip, basis.
9. **Keyword table.** The columns from the skill; sort by spend; `judgeable: false` rows muted with "too
   early". Note beneath: only keywords with 20+ taps can be judged alone. If `totalRows.keywords` is set,
   say "top 25 of N by spend".
10. **About.** Sources list (connector, status, data through, last fetched), window and lags, small-number
    caveats, benchmark provenance, known data issues, glossary (`<dl>`).

## Numbers carry sources

Wrap every figure in `<button class="num" data-src="..." data-range="...">12.3%</button>`. On click (and on
keyboard activation) show a small popover or `<dialog>`: what the number is, the source (connector id, or the
benchmark `sourceName` and URL), and the day range (`window.from` to the source's `dataCompleteThrough`).
Build the `data-*` values from the payload; use one 15-line script for all buttons. Close on Escape or outside
click.

## Formatting

Rates as percentages to one decimal (two for rates under 1%); counts with thousands separators; money with the
payload's currency to 2 decimals for costs per unit, 0 for totals. Dashes for null. Dates as `YYYY-MM-DD`.
Tables: right-align numbers, tabular figures, sticky header on long tables.
