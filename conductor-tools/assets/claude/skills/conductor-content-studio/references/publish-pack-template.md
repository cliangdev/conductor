# Publish pack

Two files in the run folder, written by the orchestrator in stage S5b from the scriptwriter's
`## Publish copy` section in `script.md`, the art director's `## Publish assets` section in
`direction.md`, and the compliance results (`compliance-checklist.md`). The pack is what the
publisher uses later to pre-fill the Post, so it holds only what the Post needs. A run never
creates a Post.

Short-form craft this encodes: the search keyword sits in the caption's opening words, on-screen
text and speech (TikTok search reads all three); a hook swap is the standard creative iteration;
hook rate is 3-second plays over impressions and hold rate is completions over 3-second plays;
posts work with the sound off.

## Hook types

Tag every hook, in ideas and in the pack, with one of: `question`, `bold-claim`,
`pattern-interrupt`, `relatable-pain`, `how-to`, `number`, `curiosity-gap`, `story`,
`social-proof`. Use another short kebab-case word only when none fits.

## publish-pack.md (for people)

One section per platform the brief asks for. Omit a platform it does not.

```
# Publish pack: <title>

Run: <runId>   Creative: <display id, or "not yet committed">   Tags: hook <type>, angle <angle>, format <format>

## <Platform> (<feed | reel | story>, as the destination's formats allow)
- **Caption:** <final text; the search keyword is in the first line, within the first ~125
  characters; within the platform's limit>
- **Hashtags:** <3 to 5>
- **First comment (post manually):** <text, or "none">. Conductor cannot post comments.
- **Cover frame:** <video: timestamp in seconds and what is on it; carousel: card 1; still: n/a>
- **Alt text:** <one line per image or card, in order>
- **On-screen text placement:** <where each line sits, inside the safe zone>
- **Sound:** <source and licence: original audio | Commercial Music Library track "<name>" |
  library track id and its licence | clip's own sound | none>
- **Posting window:** <best hour and weekday from get_marketing_insights byHour/byWeekday for this
  platform, with the window it came from, or "no data yet">
- **Options:** <the option keys and values this platform gets, from publish-pack.json>

## Alternate hooks (for variantOf hook experiments)
1. <hook line> (<hookType>)
2. <hook line> (<hookType>)
3. <optional>

## Compliance
<the table from compliance-checklist.md: check, answer, what was done, option set>

## Third-party assets
| Asset | Whose | Permission or licence |
|---|---|---|
| <music, footage, logo, meme, likeness, trademark, parody> | ... | ... |
(Write "none" if the post uses only the brand's own and generated-by-Conductor material.)
```

## publish-pack.json (for the publisher)

```json
{
  "creativeId": null,
  "platforms": [
    {
      "platform": "tiktok",
      "format": "feed",
      "captionOverride": "<final caption>",
      "options": { "brandOrganicToggle": true, "isAigc": false, "videoCoverTimestampMs": 1500 }
    },
    {
      "platform": "instagram",
      "format": "feed",
      "captionOverride": "<final caption>",
      "options": { "altText": "<alt text for a single feed image>" }
    }
  ],
  "altText": ["<card 1 alt text>", "<card 2 alt text>"],
  "alternateHooks": [{ "hook": "<line>", "hookType": "question" }],
  "tags": { "hookType": "bold-claim", "angle": "utility-education", "format": "carousel" }
}
```

Rules:

- `creativeId` is null until the person approves the commit (S6, On Approve), then it is the
  Creative's id. A `FILM` run leaves it null: the publisher is pointed at the pack by the person.
- `platforms[]` items are shaped to drop straight into `create_post` targets or
  `set_publish_targets` targets: `platform`, `format` (`feed`, `reel` or `story`, and only one the destination's `formats` offers: at the time
  of writing TikTok and YouTube are feed only, Facebook and Instagram offer all three),
  `captionOverride`, and `options`. Name `options` `publishOptions` for `set_publish_targets`. They
  carry no account: the publisher resolves the account (`account` or `connectionId`) from
  `list_publish_targets`. `captionOverride` is ignored on a story.
- `captionOverride` is the **complete text that gets posted**: the caption, then a blank line, then that
  platform's hashtags. The publisher posts it as-is, so hashtags listed only in `publish-pack.md` never
  reach the platform. The first comment is the one thing that stays out: it is posted by hand.
- `options` holds real option keys only, in the camelCase the destination reports under
  `optionKeys`. Never add a key that is not in this table, and never `privacyLevel` (the person
  picks it from the account's allowed levels at publish time):

  | Platform | Keys the pack may set | Applies to |
  |---|---|---|
  | TikTok | `brandContentToggle`, `brandOrganicToggle` | video and photo posts |
  | TikTok | `isAigc` | video posts only |
  | TikTok | `videoCoverTimestampMs` (milliseconds, from the cover frame) | video posts only |
  | TikTok | `disableComment` | video and photo posts |
  | TikTok | `disableDuet`, `disableStitch` | video posts only |
  | Instagram | `altText` (at most 1000 characters) | a single feed image only |
  | Instagram | `audioName`, `shareToFeed`, `collaborators` (at most 3, not on a carousel) | reels only |
  | YouTube | `containsSyntheticMedia` | video |

  Set only what the compliance check or the plan decided: leave a key out rather than writing a
  default. Keys that need a Post's own asset id (`coverAssetId`, `thumbnailAssetId`,
  `photoCoverIndex`) are not known until media is attached to a Post, so the pack does not carry
  them; the cover frame is in `publish-pack.md` for the publisher to act on.
- `altText` is one string per image or card, in order. It is also the source for the Creative's
  own `altText` field.
- `alternateHooks` and `tags` are for learning and experiments, not for publishing. `tags` is also
  copied into `run.json`.
- `tags.angle` is the ideator's angle: `story-emotion`, `utility-education` or `trend-humor`.
  `tags.format` is the idea's format (`still`, `carousel`, `story`, `motion`, `clip` or `film`).
- Valid JSON, no comments.
