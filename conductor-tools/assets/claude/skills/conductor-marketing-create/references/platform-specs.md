# Platform specs for short-form posts

Every number on this page is a planning default taken from public platform guidance at the time of
writing. **Check current platform guidance before relying on any of it**; platforms change limits and
ranking signals without notice, and the brief's own platform list always wins.

## Craft that holds on every platform

- Hook in three layers, all inside the first second: what frame 1 shows, the text on screen at 0 s,
  and the first sound (`hooks.md`). The first frame is the thumbnail and the ad, so it is never a
  title card or a logo.
- A new beat or pattern interrupt every 3 to 5 seconds (a cut, a new line of text, a zoom, a
  reveal), then a payoff, then one call to action.
- Captions on screen so it works with the sound off. Decide sound design on purpose: sound-on
  platforms reward voice and music, but many people watch muted.
- Vertical 9:16 for TikTok, Reels, Stories and Shorts. Keep text out of the platform's own UI.
- Plan for retention, not reach: a shorter piece that is watched to the end beats a longer one that
  is abandoned.

## Per-platform format

Route the format by platform before choosing a layout.

| Platform | Organic format | Not this |
|---|---|---|
| TikTok | Photo slideshow or video | n/a |
| Instagram | Video (Reel) | Photo or carousel feed post |
| Facebook | Video (Reel) | Photo or carousel feed post |
| YouTube | Video (Short) | Photo post (YouTube has none) |

The reason, in one line: photo posts on Instagram and Facebook reach followers only, and a new
account has none. A slideshow bound for Instagram, Facebook or YouTube goes as its MP4 (the preview's
`slideshowVideoPath`; `carouselAs: "video"` on the destination), with a spoken first line once a
voice is available and, until then, an honest note that it has none. Photo posts to those platforms
stay possible when the person chooses them: say the reason once and offer the video form; the person
decides. Paid ads follow the ad's own placement rules and are not covered by this row.

## Safe zone (9:16, 1080x1920)

Use one zone for every 9:16 surface: Reels, TikTok, Shorts, Stories and slideshow videos. Keep text
and faces inside this box:

| Zone | At 1080x1920 | Share | What covers it |
|---|---|---|---|
| Top | text at y >= 290 | about 15% | Reels header (about 260 px measured on iOS), TikTok tabs and search |
| Bottom | text above y=1490 (430 px reserved) | about 22% | caption, username, music row |
| Right | nothing right of x=918 between y=576 and y=1632 | right 15% (140 to 162 px) | like, comment and share column |

Long captions and hashtags grow upward, so on a post with a long caption keep text above about
y=1250 (the bottom ~35%). That leaves a central band of roughly 1000 px for the hook, headline and
CTA. Shorts' channel and title strip takes comparable space (check current platform guidance). The
layout engine reserves the top 290 px on `9x16` and `story` and checks it; for finished images and
clips, `preview_creative_draft` reads their text with OCR and its overlay sheet shows the platform UI
on top of the frames.

## Platforms

| Platform | Format and aspect | Length | What the ranking rewards | Sound and captions |
|---|---|---|---|---|
| TikTok | Video 9:16 (photo carousels also exist: CAROUSEL Creatives use 9:16, up to 35 images) | Up to several minutes; short pieces of about 15 to 45 s are the planning default | Watch time and completion, rewatches, shares, comments; TikTok's Creative Codes frame a piece as hook, body, close | Built for sound on; on-screen captions still matter. Caption text up to about 2,200 characters, but only the first line is visible before "more" |
| Instagram Reels | Video 9:16 | Up to 3 minutes; about 15 to 30 s is the planning default | Watch time, likes and sends (shares by direct message); originality | Captions on screen; sound-off viewing is common. Caption up to 2,200 characters, about 125 shown before "more" |
| Instagram feed | Image or carousel 4:5 (also 1:1); finished-image CAROUSEL Creatives use 4:5, else 1:1 | Carousels 2 to 10 cards | Saves, sends, likes; carousel swipes | No sound; the first card carries the hook |
| Instagram Stories | 9:16, 2 to 7 beats | Each story card is short; sequences aim for 3 to 5 beats | Taps forward and replies; completion of the sequence | Text on screen; stickers and links sit in the UI zones |
| YouTube Shorts | Video 9:16 | Up to 3 minutes (older limits were 60 s) | Viewed vs swiped away, then average view duration | Sound on, captions on screen; title up to 100 characters |
| Facebook | Reels 9:16; feed image or video 1:1 | Reels are short vertical video; feed video varies | Watch time, shares, meaningful comments | Most feed video is watched muted: captions are required |

## Mapping to Conductor placements

| Placement key | Shape | Typical destinations |
|---|---|---|
| `9x16` | 1080x1920 vertical | TikTok, Instagram Reels, YouTube Shorts, Facebook Reels |
| `4x5` | portrait | Instagram feed |
| `1x1` | square | Facebook feed (also Instagram feed) |
| `story` | vertical, sequence frames | Instagram and Facebook Stories |
| `16x9` | landscape | YouTube (standard) |
| `1.91x1` | wide link-ad | link ads |
| `2x3` | tall pin | Pinterest |

The enabled placements come from the Brand Kit; a Creative can opt in to more via `placements`.
`attach_creative_to_post` does the placement to destination mapping later; this skill stops at the
committed Creative.

## Picking a format from the idea

- A single claim or quote: a STILL at `9x16` plus `4x5` and `1x1`.
- A sequence with a payoff on the last beat: a carousel (5 to 7 cards perform best, one shared
  ratio) or a story (3 to 5 beats).
- A beat-by-beat reveal or kinetic text: MOTION, 6 to 15 s, over a photo or a clip on hand.
- A person, demonstration or skit: `FILM`, vertical, shot to the safe zones above.
