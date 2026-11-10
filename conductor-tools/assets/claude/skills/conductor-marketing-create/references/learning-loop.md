<!-- Used by both modes of conductor-marketing-create. Direct and Team both read this. -->
# Learning loop: each run reads what worked, tests one thing, and writes down what it learned

A workspace keeps one playbook in the Knowledge Center. Every run starts by reading it, follows it,
tests one new thing and, once the Creative is committed, writes back what it learned. Nothing here
overrides brand rules, copy rules, banned copy, `voice.md` or the approval gate.

## Thresholds

The one place to tune the loop. These are practitioner rules of thumb, not proven constants.

- **Proven / Didn't work**: median view lift of 1.2 or more (Proven) or 0.8 or less (Didn't work),
  and `beatBaselineShare` of 0.67 or more in that direction, over 3 or more settled posts. Or a hook
  experiment that is DECIDED. A lift between 0.8 and 1.2 counts as no difference.
- **Resolving a hypothesis**: 3 or more settled test posts.
- **Exploration**: about 1 in 4 Creatives carries a test.
- **Decay**: 90 days without new evidence.
- **Settle windows** are the server's: TikTok, Instagram and YouTube after 72 hours, Facebook after
  7 days. Do not recompute them; `posts` and `settledPosts` already count only settled posts.

## What the numbers are

Tags are the join between a Creative and its results. Server-side Creative `tags` (an object with
optional keys `hookType`, `angle`, `format`, `archetype`, `hypothesis`) are accepted by
`create_creative`, `update_creative`, `preview_creative_draft` and `commit_creative_draft`, and
returned by `get_creative` and `list_creatives`.

`get_marketing_insights` returns a `byTag` breakdown. Each row has `tagKey`, `key`, `posts` (settled
posts only), `unsettledPosts`, `medianViewLift`, `beatBaselineShare`, `medianEngagementRate` and
`medianRetentionLift`. `get_creative` gives each variant `settledPosts`, `medianViewLift`,
`medianRetentionLift` and `beatBaselineShare`.

**Lift is the post against this account's own platform (and format) median over 90 days; 1.0 is
typical.** Judge against that baseline, never against raw views or totals. Name the metric on every
lesson.

- Hook lessons (`hookType`, `archetype`): `medianRetentionLift` first, `medianViewLift` when retention
  is missing.
- Angle and format lessons: `medianViewLift`, with `medianEngagementRate` as support.

## The playbook page

Path `marketing/playbook.md`. With several Brand Kits, one `## <kit name>` section per kit, each in
the template below with its headings one level down (`###`); read and write only the chosen kit's
section. Fixed template, at most about 80 lines per kit:

```
# Playbook
Updated <date> · results through <date> · N Creatives measured

## Proven
- [#<n> +<support>/−<contra> · <last evidence date>] <lesson> (<metric> <value>, n=<posts>; <display ids>)

## Leaning
- [#<n> +<support>/−<contra> · <last evidence date>] <lesson> (<metric> <value>, n=<posts>; <display ids>)

## Didn't work
- [#<n> +<support>/−<contra> · <last evidence date>] <lesson> (<metric> <value>, n=<posts>; <display ids>)

## Taste
- [#<n> · <date>] <the person's preference about this brand's look or voice>

## Open hypotheses
- H<n>: <one variable> · metric <metric> · win if ≥ 1.2, lose if ≤ 0.8 · needs 3 settled posts · tests: <display ids>

## Folded in
<last ~30 Creative display ids already counted, comma separated>
```

- **Proven**: meets the Proven threshold, or a DECIDED hook experiment. The line names its metric
  and value, n and the Creative display ids. `+support` counts settled posts that agree, `−contra`
  those that disagree.
- **Leaning**: 1 or 2 settled posts, a gap inside 0.8 to 1.2 that is not yet settled as "no
  difference", or posts that disagree. Never promote a row with fewer than 3 settled posts, or a
  lift inside 0.8 to 1.2, to Proven.
- **Didn't work**: the same evidence rule in the other direction; say what was tried.
- **Taste**: only the person's own feedback on drafts, dated. Constraints, not statistics. The retro
  never demotes, edits or removes one, and decay does not apply.
- **Folded in**: a Creative whose posts are all settled and counted. Added only when a retro counts
  it, never at commit.
- **Lesson IDs** (`#<n>`) never change and are never reused, even when a lesson moves section.

## Editing by delta

The retro and capture produce a list of operations and apply only those to the page text. Never
rewrite or reword a line no operation touched.

- **ADD** a lesson with the next free `#<n>`.
- **UPDATE** a line's counts, value, evidence date or display ids.
- **MOVE** a line to another section, keeping its ID.
- **REMOVE** a line.

Merge duplicates by keeping the older ID and summing the counts. Drop the oldest Leaning lines when
a section is over the cap (a REMOVE). The `Learned:` lines in the final message are these operations
in plain words ("#7 moved to Proven: number hooks hold 1.4x typical retention, n=4").

## Tag vocabulary

Tag every Creative the same way in both modes, so results stay comparable.

- `hookType`: from `voice.md`'s "Shapes, and the hookType each one is tagged" table. One of
  `question`, `bold-claim`, `pattern-interrupt`, `relatable-pain`, `how-to`, `number`,
  `curiosity-gap`, `story`, `social-proof`.
- `angle`: `story-emotion`, `utility-education` or `trend-humor`. Set it only when one applies; Direct
  mode usually has none.
- `format`: `still`, `carousel`, `story`, `motion`, `clip` or `film`, as
  `references/publish-pack-template.md` maps them.
- `archetype`: a slideshow's archetype from `references/slideshow-formats.md` ("Hook archetypes"),
  in kebab-case. Slideshows only.
- `hypothesis`: `H<n>`, the open hypothesis this Creative tests. At most one per Creative.

`hooks.md`'s Direct-mode types map to `hookType` as:

| Direct type | hookType | Why |
|---|---|---|
| Situation / POV | `relatable-pain` | `voice.md` tags POV with a real scenario this way |
| Mechanism | `how-to` | how it works in one line |
| Enemy | `relatable-pain` | `voice.md` tags an enemy with attitude this way |
| Exact result | `number` | a specific number or time |
| Concept | `pattern-interrupt` | the image carries a metaphor that breaks the pattern |

A line that is plainly a question, a quote or a social-proof line takes that `hookType` instead. Use
the shape's row in `voice.md` when the line fits one.

## Retro (the start of every run)

1. `read_knowledge_pages` for `marketing/playbook.md` and `marketing/what-works.md`. A missing
   playbook means bootstrap (below).
2. Skip the rest when the playbook was updated less than 24 hours ago: the last retro is recent
   enough.
3. Otherwise `get_marketing_insights` (window `30d`, `90d` if empty) and read `byTag`, `byFormat`
   and `creatives`. Ignore Creatives already in Folded in.
4. For each open hypothesis, `get_creative` on each Creative in its `tests:` list and read
   `settledPosts` and the lift fields. A Creative that has not posted, or has no settled post yet,
   adds nothing.
5. Build the operations list:
   - Hypotheses: below 3 settled test posts, add a one-line progress note ("progress: 2 of 3") and
     leave it open. At 3 or more it resolves as a win, a loss or no difference by its own metric and
     thresholds. Remove it from Open hypotheses and ADD or UPDATE a lesson with the result (a win to
     Proven, a loss to Didn't work, no difference to Leaning with the note "no difference").
   - Rows: UPDATE counts and evidence dates on lessons the `byTag` rows touch. MOVE a Leaning line
     to Proven or Didn't work when it now meets the threshold; MOVE a Proven line back to Leaning
     when later posts contradict it.
   - Decay: a lesson whose last evidence date is over 90 days old moves Proven to Leaning, and
     Leaning is removed. Taste lines are exempt.
   - Folded in: add each Creative whose posts are all settled and counted.
6. A lesson in Proven, Leaning or Didn't work comes only from metrics or a decided experiment. Your
   own opinion of a draft is never recorded as one.
7. Name what could not be measured (no data yet, a platform that reports no views, a Creative with
   no tags). Do not guess a result.

The retro changes the page only at capture, after the commit.

## Bootstrap (no playbook yet)

1. Glob local `.conductor/content-runs/*/run.json`. For each with a `creativeId` and `tags`:
   `get_creative` for its current `version`, then `update_creative` with the `tags`. On a 409, reread
   with `get_creative` and retry once.
2. Run the retro above to build the first playbook from `byTag` and the Creatives, as ADD
   operations. With no settled data yet, the first playbook has empty sections and the first
   hypothesis.

## Apply

- Proven and Taste lines act as constraints on the idea, the hook, the format and the look.
- Avoid a Didn't work line, unless you are deliberately retesting it as this run's hypothesis.
- Leaning lines are hints, never constraints.

## Explore: a test, about one run in four

A hypothesis is written before the post goes out, in one line:
`H<n>: <one variable, e.g. hookType=question vs the account's usual> · metric <medianRetentionLift|medianViewLift|medianEngagementRate> · win if ≥ 1.2, lose if ≤ 0.8 · needs 3 settled posts · tests: <display ids>`.
Change exactly one variable.

1. Repeating an open hypothesis that needs more posts to reach 3 takes priority over opening a new
   one. A new one comes from a gap in the evidence (a hookType, angle, format or archetype with no
   results, or a Leaning line that needs more posts) and gets the next `H<n>`.
2. Test it in exactly one place. Direct mode: the headline against an alternate hook, or the
   format. Team mode: one idea, marked `Tests: H<n>`.
3. The cap. Direct mode: before adding a test, `list_creatives` (most recent about 8) and check
   `tags.hypothesis`. Add a test only if fewer than about 1 in 4 carry one, or if an open
   hypothesis needs more posts. Team mode: one idea may be marked every run, since it only ships if
   it wins; count shipped winners toward the same cap, and skip the mark when the cap is already met.
4. Set `tags.hypothesis` on the Creative that tests it, and append its display id to the
   hypothesis's `tests:` list at capture.
5. When both hooks get posted, suggest a lettered variant (`variantOf`) and `create_experiment`.
6. A test never overrides brand rules, copy rules, banned copy, voice checks or the approval gate.
   If the test would break one, pick another test.

## Capture (after `commit_creative_draft`)

1. Take the operations from the retro. Add Taste lessons from the person's own feedback in the
   preview loop (requested changes, rejections): about this brand's look or voice, generalised one
   step ("keep text off faces", not "move CR-14's headline"), dated. A contradiction of an earlier
   Taste line is an UPDATE citing both dates; only an explicit instruction from the person changes
   or removes one. Banned copy stays on the Brand Kit as today (`references/direct.md`, step 1).
   Never record your own judgment of a draft.
2. Add this run's hypothesis to Open hypotheses (or append this Creative to the `tests:` list of the
   one it repeats). Do not add the Creative to Folded in: it has no settled results yet.
3. Update the header and apply the operations to the page text, nothing else.
4. `write_knowledge_pages` with `baseVersion` from the read (omit it to create the page when it is
   new). On `conflict: true`, reapply your operations to `currentContent` and write once more.

## When writing is not possible

The Knowledge Center is off, or the write fails twice: say so once, carry on, and put the lessons in
the final message. A missing read means no playbook and no retro; the run still goes ahead.

## Final message

The operations in plain words as `Learned:` lines, at most 3 (the rest are on the page), then one
line naming the hypothesis this Creative tests. With none, write "Learned: nothing new this run."
