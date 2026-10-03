# Platform specs for short-form posts

Every number on this page is a planning default taken from public platform guidance at the time of
writing. **Check current platform guidance before relying on any of it**; platforms change limits and
ranking signals without notice, and the brief's own platform list always wins.

## Craft that holds on every platform

- Hook in the first 0 to 3 seconds, both spoken and on screen. The first frame is the thumbnail
  and the ad.
- A new beat or pattern interrupt every 3 to 5 seconds (a cut, a new line of text, a zoom, a
  reveal), then a payoff, then one call to action.
- Captions on screen so it works with the sound off. Decide sound design on purpose: sound-on
  platforms reward voice and music, but many people watch muted.
- Vertical 9:16 for TikTok, Reels, Stories and Shorts. Keep text out of the platform's own UI.
- Plan for retention, not reach: a shorter piece that is watched to the end beats a longer one that
  is abandoned.

## Safe zone (9:16, 1080x1920)

Use the Reels zone as the default for every vertical placement: keep text and faces out of the top
~14%, the bottom ~35% and ~6% at each side. That leaves a central band of roughly 1000 px of height
for the hook, headline and CTA. TikTok's right-hand button column and caption area, and the Shorts
channel and title strip, take comparable space (check current platform guidance).

## Platforms

| Platform | Format and aspect | Length | What the ranking rewards | Sound and captions |
|---|---|---|---|---|
| TikTok | Video 9:16 (photo carousels also exist) | Up to several minutes; short pieces of about 15 to 45 s are the planning default | Watch time and completion, rewatches, shares, comments; TikTok's Creative Codes frame a piece as hook, body, close | Built for sound on; on-screen captions still matter. Caption text up to about 2,200 characters, but only the first line is visible before "more" |
| Instagram Reels | Video 9:16 | Up to 3 minutes; about 15 to 30 s is the planning default | Watch time, likes and sends (shares by direct message); originality | Captions on screen; sound-off viewing is common. Caption up to 2,200 characters, about 125 shown before "more" |
| Instagram feed | Image or carousel 4:5 (also 1:1) | Carousels 2 to 10 cards | Saves, sends, likes; carousel swipes | No sound; the first card carries the hook |
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
