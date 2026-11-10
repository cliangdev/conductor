<!-- Used by both modes of conductor-marketing-create. Direct and Team both read this. -->
# Learning loop: each run reads what worked, tests one thing, and writes down what it learned

A workspace keeps one playbook in the Knowledge Center. Every run starts by reading it, follows it,
tests one new thing and, once the Creative is committed, writes back what it learned. Nothing here
overrides brand rules, copy rules, banned copy, `voice.md` or the approval gate.

Tags are the join between a Creative and its results. Server-side Creative `tags` (an object with
optional keys `hookType`, `angle`, `format`, `archetype`, `hypothesis`) are accepted by
`create_creative`, `update_creative`, `preview_creative_draft` and `commit_creative_draft`, and
returned by `get_creative` and `list_creatives`. `get_marketing_insights` returns a `byTag`
breakdown: rows of `{tagKey, key, ...group metrics, noise, smallSample}`.

## The playbook page

Path `marketing/playbook.md`. With several Brand Kits, one `## <kit name>` section per kit, each in
the template below; read and write only the chosen kit's section. Fixed template, at most about 80
lines per kit:

```
# Playbook
Updated <date> · results through <date> · N Creatives measured

## Proven
- <lesson> (<metric>, n=<posts>, <window>; <display ids>)

## Leaning
- <lesson> (<metric>, n=<posts>, <window>; <display ids>)

## Didn't work
- <lesson> (<metric>, n=<posts>, <window>; <display ids>)

## Taste
- <the person's preference about this brand's look or voice> (<date>)

## Open hypotheses
- H<n>: <what is tested>. Tested by <display id>. Result due <date, 72 h after posting>.

## Folded in
<last ~30 Creative display ids already counted, comma separated>
```

- **Proven**: 3 or more measured posts, none flagged `noise` or `smallSample`, or a hook experiment
  that is DECIDED. Every line carries its evidence: the metric, n, the window and the Creative
  display ids.
- **Leaning**: 1 or 2 posts, or any result flagged `noise` or `smallSample`. Never promote a noise
  or smallSample result to Proven.
- **Didn't work**: same evidence rule; say what was tried and what happened.
- **Taste**: the person's own feedback on drafts, dated. Constraints, not statistics.
- **Folded in**: lets the retro skip Creatives it has already counted, so nothing is counted twice.
- **Prune** on every write: merge duplicate lines, and drop the oldest Leaning lines when the
  section is over the cap.

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
2. Skip the rest when the playbook was updated less than 24 hours ago and no open hypothesis is due.
3. Otherwise `get_marketing_insights` (window `30d`, `90d` if empty) and read `byTag`, `byFormat`
   and `creatives`. Ignore Creatives already in Folded in.
4. For each due hypothesis (72 hours or more since its Creative posted), `get_creative` on its
   Creative: its performance and its experiment.
5. Apply the thresholds above. Promote a Leaning line that now has 3 or more clean posts. Demote a
   Proven line a later clean result contradicts. Resolve a hypothesis: move it into Proven, Leaning
   or Didn't work with its evidence, and remove it from Open hypotheses.
6. Name what could not be measured (no data yet, a platform that reports no views, a Creative with no
   tags). Do not guess a result.

The retro changes the page only at capture, after the commit.

## Bootstrap (no playbook yet)

1. Glob local `.conductor/content-runs/*/run.json`. For each with a `creativeId` and `tags`:
   `get_creative` for its current `version`, then `update_creative` with the `tags`. On a 409, reread
   with `get_creative` and retry once.
2. Run the retro above to build the first playbook from `byTag` and the Creatives. With no data
   yet, the first playbook has empty sections and the first hypothesis.

## Apply

- Proven and Taste lines act as constraints on the idea, the hook, the format and the look.
- Avoid a Didn't work line, unless you are deliberately retesting it as this run's hypothesis.
- Leaning lines are hints, never constraints.

## Explore: one test per run

1. Take the oldest open hypothesis that has no Creative testing it, or write a new one from a gap
   in the evidence (a hookType, angle, format or archetype with no results, or a Leaning line that
   needs more posts). A new one gets the next `H<n>`.
2. Test it in exactly one place. Team mode: one idea, marked as the test. Direct mode: the headline
   against an alternate hook, or the format.
3. Set `tags.hypothesis` on the Creative.
4. When both hooks get posted, suggest a lettered variant (`variantOf`) and `create_experiment`.
5. A test never overrides brand rules, copy rules, banned copy, voice checks or the approval gate.
   If the test would break one, pick another test.

## Capture (after `commit_creative_draft`)

1. Distil 0 to 3 lessons from the preview loop (requested changes, rejections, what got approved)
   and from the retro's changes.
2. Taste lessons are about this brand's look or voice, generalised one step: "keep text off faces",
   not "move CR-14's headline". Date them. Banned copy stays on the Brand Kit as today
   (`references/direct.md`, step 1).
3. Add the new hypothesis to Open hypotheses with this Creative's display id and the due date
   (72 hours after it is expected to post), and add the Creative's id to Folded in.
4. Update the header and prune.
5. `write_knowledge_pages` with `baseVersion` from the read (omit it to create the page when it is
   new). On `conflict: true`, merge your changes into `currentContent` and write once more.

## When writing is not possible

The Knowledge Center is off, or the write fails twice: say so once, carry on, and put the lessons in
the final message. A missing read means no playbook and no retro; the run still goes ahead.

## Final message

Up to 3 `Learned:` lines (one lesson each, with its evidence when it has any), then one line naming
the hypothesis this Creative tests. With nothing learned, write "Learned: nothing new this run."
