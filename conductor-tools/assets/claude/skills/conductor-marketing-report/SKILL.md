---
name: conductor-marketing-report
description: Writes the whole-funnel marketing report for any app or product from Conductor's report payload: fetches get_marketing_report, researches and records missing or stale industry benchmarks from sources read in the session, ranks next actions by users lost, and renders a ten-section HTML report. Offers to record each action as a PLANNED Change Work Item. Use for a marketing, growth or acquisition report, a funnel review, or "how does this compare to the industry". Entry point: /conductor:marketing report.
user-invocable: true
allowed-tools: mcp__conductor__*, AskUserQuestion, Skill, WebSearch, WebFetch, Read, Write, Glob, Grep
---

# Conductor Marketing Report

You turn one payload of numbers into a report a decision-maker can act on. The product can be any app,
site or service; nothing here assumes a vertical. The report is only as good as its traceability: every
sentence rests on a number in the payload or a source you read this session.

## Step 1 — Fetch

Take the window from the request: `7d` or `28d`, default `28d`. Never ask. Call `get_marketing_report` with it. Percent values are
fractions (0.12 = 12%). The payload drops null and empty fields, except `window.to` (null when no source has data),
`changes` and the section arrays, which are always present. Top 25 keywords, 20 ads (`totalRows.ads` gives the full ad count when capped). The `sources` list carries `dataCompleteThrough`; `changes` has `open`, `dueForReview`,
`reviewedInWindow`; each `benchmarks[]` row has ratio, `judged` and `stale`. Note, per source,
`dataCompleteThrough` and `lastFetchedAt`: a source that is
`STALE`, `NOT_CONNECTED`, or behind the others is named in the report with its lag. If `window.to` is
null or `sources` are all unconnected, say so and stop: there is nothing to report on yet.

Statuses: `ON_TRACK`, `WATCH` (close), `OFF_TRACK`, `TOO_EARLY` (no value, or a rate on fewer than 20
observations), `NO_TARGET`. Rows with `derived` or a `note` carry a caveat: repeat it.

## Step 2 — Benchmarks

A benchmark compares a rate with the industry. Read `benchmarks[]` and the scorecard/acquisition rates.
Research a benchmark for every rate (store conversion, tap-through, install rate, trial start, trial to
paid, retention and the like) that has none, or whose entry is `stale` (checked more than 90 days ago).

1. **Know the product.** You need its category, store or channel, country and monetisation model
   (subscription, one-off, ads, freemium). Look in `get_brand_kit`, `search_knowledge` and the report's
   own labels first. If still unknown, ask the person once with `AskUserQuestion`; do not guess.
2. **Search the web.** Prefer platform-published peer data (a store's or ad platform's own benchmarks),
   then reputable industry reports. Match category, store, country and model; a mismatch on any of them
   is a caveat, written in `segment` or `notes`. Open the page (`WebFetch`) and read the figure; a search
   snippet alone is not a source.
3. **Record each** with `set_marketing_benchmark`: `metricRef` = `{source.channel}.{key}` from the scorecard row (e.g. `STORE.conversion_rate`; the row's `key` is the bare metric name) or `PRODUCT.{key}`, `value` as a fraction
   for rates, `rangeText` if a range was published, `segment`, `sourceName`, the page's `sourceUrl`, and
   `checkedOn` = today. Then re-call `get_marketing_report` once to read the stored ratios back.
4. **Never state a benchmark you did not read this session.** Not from memory, not from a prior report.
   If nothing credible is found, leave the row "no benchmark found" and say what you searched.

Ratios are only a read: "at 61% of benchmark". A benchmark with `judged: false` (fewer than 20 people) is
shown but not judged: write "too early to judge".

## Step 3 — Changes

`changes.dueForReview` are experiments whose review date has passed; `changes.open` are PLANNED or LIVE;
`changes.reviewedInWindow` are done with a verdict and lesson. These feed "What to do next" alongside new
recommendations from the numbers. For a review that is due, say what was expected (hypothesis, baseline)
and what the numbers now show, and let the person decide the verdict.

## Step 4 — Decide what to do next

Rank actions by how many users each step loses, largest first:

1. the funnel's `biggestDrop` and the stages around it;
2. then acquisition efficiency: cost per install by channel, keywords that spend without installs
   (only if `judgeable`, 20 or more taps), low-converting listing or ads;
3. then everything else.

Each action: a number-backed "why", an owner (use the change's assignee, else leave "unassigned"), a
review date, and evidence (the payload keys or source URLs). One action per cause; at most seven.
An action whose evidence is a rate on under 20 people is "collect more data", not a fix.

## Step 5 — Writing rules

- Every sentence traces to a number in the payload or a cited source. If it cannot, cut it.
- A rate on fewer than 20 people reads "too early". Show it, do not judge it. A keyword under 20 taps is
  not judged alone.
- At small volume one person moves a rate by a visible amount (with about 30 installs, roughly 3 points).
  Do not call week-over-week moves under about 30% a trend; call them noise.
- Label derived numbers (for example organic search = store search downloads minus paid installs) as
  derived and say what they are derived from. Repeat the row's `note` when a total spans different days.
- Name lagging or stale sources with their lag ("ad data complete through 2026-10-18").
- Do not explain a cause you have no data for. "Because of the new icon" needs evidence; "the drop sits
  between X and Y" does not.
- Plain language, short sentences, no jargon without the glossary entry.
- Money in the currency the payload gives; do not convert.

## Step 6 — Render

The report has ten sections, in this order. Section markup, the chart recipe and the source popover are
in `references/html-layout.md`; read it before writing.

1. Title, window, data date, a 1-2 sentence summary naming the bottleneck.
2. Overall health: headline tiles with value, target and status.
3. Where installs come from: channel, status, reach, actions, installs, cost, "what it means".
4. What happens after the install: the funnel with the biggest drop highlighted.
5. Daily chart: store impressions as bars, ad spend as a line.
6. How we compare to the industry: measure, our value with counts, benchmark, the read, source with
   checked date. Note that comparisons under 20 people are shown but not judged.
7. What to do next, in priority order: numbered, status chip (in progress, decided, done, blocked,
   waiting), why with numbers, owner, review date, evidence.
8. Full scorecard: key, metric, area, now, target, status, basis.
9. Ads by keyword: keyword, ad group, status, max bid, impressions, taps, tap rate, installs, install
   rate, spend, cost per install. Note that only keywords with 20+ taps can be judged alone.
10. About these numbers: sources, window and lags; small-number caveats; where the industry data came
    from ("every benchmark on this page was read from the linked source on its checked date"); known data
    issues from the payload; a short glossary of the terms used.

Every number carries its source: clicking it shows the source (connector or benchmark URL) and day range.

**Output.** If you have an Artifact tool, load the `artifact-design` skill (and `dataviz` for the chart if
available), then publish an HTML artifact. Otherwise write one self-contained HTML file (inline CSS and JS,
no network at view time except an optional pinned d3) to the working directory as
`marketing-report-<YYYY-MM-DD>.html`. Light and dark themes via CSS variables. Phone-width layout.

## Step 7 — Offer to record actions

Never create anything without a yes. After the report, offer to record each new recommended action as a
Change Work Item: `create_work_item` with `workflow` `MARKETING_CHANGES` (confirm with
`list_workflows({kind:"LIFECYCLE"})`), a `type` from that workflow (for example LISTING, ADS, PRICING,
PRODUCT, CONTENT, BASELINE, OTHER), a `title`, `fields` `{hypothesis, baseline, evidence}` and `dueOn`
as the review date. It starts PLANNED. Verify with `get_work_item`.

For each change in `dueForReview`, offer to fill `outcome`, `verdict` (worked, did_not_work,
inconclusive) and `lesson` with `update_work_item`, then move it to REVIEWED with `transition_work_item`
(`get_available_transitions` first; that move may need a reviewer's approval, so report what happened).
Use the person's wording for the verdict; propose, do not decide.

## Checks before you finish

- Every benchmark shown was fetched and recorded this session or is in the payload with its source and
  date; none is stale without saying so.
- No rate under 20 people is judged. No small-volume trend claim under about 30%.
- Every derived number is labelled. Every lagging source is named with its lag.
- The funnel's biggest drop is the first action unless the data says it is too early to act on.
- You offered the Changes; you did not create any unprompted.

## Finish

Give the person the path or link to the report and a three-line summary: the headline finding, the
biggest funnel drop, and the first recommended action. Then make the offer to record the actions as
Changes. Nothing is written to Conductor beyond benchmarks (which carry their source) unless the
person says yes.
