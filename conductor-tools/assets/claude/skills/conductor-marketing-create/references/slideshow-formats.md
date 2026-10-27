# Slideshow formats

For organic posts: a native-looking photo slideshow (TikTok photo post, Instagram carousel) that
promotes an app or product by being worth watching first. Build it with `compose_slideshow`, then
post it as a CAROUSEL Creative. Ads use the brand layouts (STILL) instead; this is not the ad path.

Branding is light: no logo, badge or offer on the slides. The product appears as the payoff, not the
opener.

## Post anatomy

Aim for 5 to 7 slides. Each slide has one job.

1. **Slide 1, the hook.** A human tension or a result people want to keep. Never a feature, the
   product name or a price.
2. **Middle slides, the value.** The part people save or send: the list, the plan, the fix, the story
   beat. Saves and sends carry a post beyond followers.
3. **Slide N-1, the payoff.** The product in use on a real screenshot (a `screen` slide, optionally
   with `callouts`) plus one plain mention, such as "I keep mine in <App>".
4. **Last slide, the ask.** A save or follow ask: "Save this. Link in bio."

## Slide types

Every slide takes `type` (default `photo`), an optional background (`bg` path, `bgColor`, `blur`,
`grade`, `grain`, `dim`) and `texts`. Each text is `{t, y, fs, style}`; the first text of a slide
needs `y`, and later texts without `y` stack under the previous one.

| Type | For | Key fields | Use when |
|---|---|---|---|
| `photo` | A full-bleed photo with text on it | `bg`, `pos`, `grade`, `grain`, `dim`, `texts` | The default hook, list or story slide |
| `screen` | A real app screenshot in a phone frame | `shot`, `phoneY`, `pw`, `cropH`, `callouts [{x, y, label}]`, `bg`, `blur`, `texts` | The payoff slide (N-1); callouts point at what to notice |
| `notes` | A Notes-app page | `title`, `date`, `items` (strings or `{t, done, strike}`), `paras` | A plan, checklist or "what I do now" |
| `messages` | A chat thread | `bubbles [{from: "me"|"them", t}]` | Doubt or conflict: what someone said to you |
| `grid` | A wall of small photos | `cols`, `gridTop`, `photos [{src, x, badge, cap, restore}]` | "Everything you saved and never used"; one tile `restore`d for the close |
| `stack` | Photos stacked in a column | `photos [{src, label}]` | A before/after with `label`s ("Before", "After") or a short comparison |
| `tier` | A tier list | `rows [{k, c, items}]`, `ts` | Ranking, "what's worth it and what isn't" |
| `week` | A week planner | `days [{d, t, img, icon, fog}]` | A routine or plan across days |
| `receipt` | A till receipt | `head`, `sections [{h, items}]`, `count` | A tally or breakdown; `count` must be a real figure |
| `placard` | A museum-style label over a photo | `bg`, `ex`, `title`, `y`, `lines [{k, v}]` | A deadpan, collectible-feeling item card |

Text `style`: `box` (white rounded box, TikTok's own text look), `dark` (dark box), `stroke` (white
text, black outline), `sub` (smaller subline).

### Example spec

A five-slide "stop doing X" post. Paths are relative to the spec file or absolute.

```json
{
  "id": "stop-over-saving",
  "slides": [
    {"type": "photo", "bg": "photos/desk.jpg", "grade": true, "grain": true,
     "texts": [{"t": "Stop saving things you will never open.", "y": 30, "fs": 76}]},
    {"type": "messages",
     "bubbles": [{"from": "them", "t": "did you ever look at that thing you saved?"},
                 {"from": "me", "t": "define look"}],
     "texts": [{"t": "Be honest.", "y": 20}]},
    {"type": "notes", "title": "What I do now", "date": "Today",
     "items": ["Save one thing", {"t": "Open it within a day", "done": true}, "Delete the rest"]},
    {"type": "screen", "shot": "shots/home.png", "phoneY": 40, "pw": 600,
     "callouts": [{"x": 50, "y": 30, "label": "one tap"}],
     "texts": [{"t": "I keep mine in <App>.", "y": 18}]},
    {"type": "photo", "bg": "photos/desk.jpg", "dim": true,
     "texts": [{"t": "Save this.", "y": 32, "fs": 80}, {"t": "Link in bio.", "style": "sub"}]}
  ]
}
```

## Hook archetypes

Pick one per post and name it in the idea. Change one thing per post so results are comparable.

| Archetype | Slide 1 | Slides |
|---|---|---|
| Saved-but-never-used guilt | "Camera-roll graveyard." | `grid` (one tile `restore`d at the end), `photo`, `screen`, ask |
| Doubt or conflict | What someone said to you (needs a true story) | `messages`, `photo`, `notes`, `screen`, ask |
| Stop doing X | "Stop doing X." | `photo` with the reason per slide, `screen`, ask |
| Numbered list | "5 things worth saving" | `photo` per item, `screen` last, ask |
| Tier list | "Ranking everything I tried" | `tier`, `photo` close-ups, `screen`, ask |
| Notes-app plan | "My plan for the week" | `notes`, `week`, `screen`, ask |
| POV | "POV: you finally did it" | `photo` (hands or a real scene), `screen`, `photo`, ask |
| Before / after | "Week 1 vs week 4" | `stack` with `label`s, `photo`, `screen`, ask |

## Text rules

- Native box text (`box`) or white text with a black stroke (`stroke`); centred, bold, sans.
- At most 8 words a line and 3 lines a box.
- Keep text between about y 16 and y 75 (percent of the 9:16 frame). That survives the 4:5 Instagram
  cut and the platform UI (top bar, bottom caption, right button column).
- The first text of every slide needs `y`.
- Read the composer's `checks`; an `error` severity blocks the preview.

## Resolution

Slides are 1080x1920 (and 1080x1350 for Instagram). Never upscale a background photo: the full-bleed
scale is `max(1080/w, 1920/h)` for a photo of w x h (use `max(1080/w, 1350/h)` for the 4:5 cut). A 1200x1200
photo full-bleed at 9:16 uses 675x1200 source pixels, a 1.6x upscale. Check every `bg` and
`photos[].src` before composing and refuse a full-bleed use above 1.0x. The fixes are: generate at
the largest size in that aspect (`aspect: "9:16"`), use a different photo, or use a slide type that
shows the photo framed at or below native size (such as `grid` or `stack`).

## Honesty and brand

- No logo, badge or offer on the slides. The offer, if any, goes in the caption.
- No invented numbers or claims. A placeholder count (a receipt `count`, "312 saved") must be
  replaced with a real figure, or the line rewritten without one.
- Real screenshots only for `screen` slides; never mock up the product.
- A generated photo needs the platform's AI label turned on. Turn on the platform's branded-content
  or "your brand" setting for a brand's own promotional post.
- A conflict or testimonial story must be true, and anyone shown or quoted must be real and agree.

## Getting the download

Captions on TikTok and Instagram cannot hold clickable links, so do not rely on one:

- Name the app in the caption and on slide N-1, so it can be searched ("search <App> in the App
  Store").
- Pin a comment that says where to find it.
- Keep the link in the profile bio.
- Repost the post to Instagram Stories with a link sticker.

## Flow

1. Write the spec (`slideshow-spec.json`); call `compose_slideshow({specPath})`.
2. If `passed` is false, fix every `error` check and compose again. Warnings are advisory.
3. `preview_creative_draft({kind: "CAROUSEL", slidePaths, caption, slideAltText})` using the
   returned `slidePaths` (`9x16` for TikTok, `4x5` for Instagram). Look at the contact sheet and show
   it.
   With music (`audio: {source: "track", trackId}`, a library track from
   `list_creative_media({kind: "AUDIO"})`; a new file is uploaded there first with its source and
   licence), the preview also renders the slideshow video and returns `slideshowVideoPath`: watch it
   with the person. No platform accepts a chosen track on photos, so a destination sends the video
   (`carouselAs: "video"`) or the silent photos.
4. Only after the person approves, `commit_creative_draft`.
