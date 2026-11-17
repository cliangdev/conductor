# Video timeline (`compose_video`)

Builds one 1080x1920 MP4 on this machine from a timeline: trimmed clips and still images joined by
hard cuts, timed text on screen from the first frame, optional music, voice and clip sound. Nothing is
sent to Conductor until the person approves. It is posted as a CLIP.

## When to use it

- A short-form video that must open on something moving, then carry on with more clips or slides.
- A clip that needs trimming to the beat, or text on it at set times.
- Not for a photo post (`compose_slideshow`, posted as a CAROUSEL), not for a brand ad (MOTION or
  STILL with the Brand Kit layouts), and not for a finished video that is already right (post it as
  a CLIP as it is).

Use whatever moving footage exists first: a phone clip beats a generated one. A generated clip
(`generate_creative_video`, only when the person asked for motion) is trimmed to 2 to 4 s and used as
the hook. Stills can be the body.

## The spec

```
{ id?, segments: [...], texts: [...], audio?: {...} }
```

- `segments`, in order, 1 to 20, 3 to 90 s in all. Paths are local files.
  - `{ clip, startSec?, endSec?, mute? }`: trimmed to `startSec` to `endSec` (default the whole clip),
    centre-cropped to fill the frame. A segment is at least 0.5 s.
  - `{ image, durationSec, zoom? }`: a still held for 1 to 10 s; `zoom: true` adds a slow push-in.
  - Either takes `transition: "fade"` for a 0.5 s cross-fade into that segment (needs 1 s or more on
    each side). The default is a hard cut: use it.
- `texts`, up to 40 beats: `{ fromSec, toSec, t, y, fs?, style? }`, on the output timeline. `y` is a
  percent of the frame height; `style` is `box`, `dark`, `stroke` or `sub`, the same text and look as
  `compose_slideshow` (read `references/slideshow-formats.md`, "Text rules", for sizes and line length). Beats
  that are on screen together must not share rows of the frame; stack them with different `y`.
- `audio`: `{ clipAudio: "keep" | "mute", track?, voicePath?, duck? }`. `track` is a music file, looped to the
  length; `voicePath` a voice file mixed over it; `duck` (default true) lowers the music under clip sound
  and voice. Local files only: a library track has to be saved to disk first.

## The hook-first recipe

1. Segment 1 is a **moving** clip, 2 to 4 s, trimmed to the beat (the moment, not the lead-in). A still
   opening is allowed but weaker.
2. The first text beat starts at `fromSec: 0` and lasts past 1 s. The words are the hook (see
   `references/hooks.md`): plain text, no brand label, no logo, no product name.
3. Then the body: more clips or stills with a visible change every 3 to 5 s, either a cut or a new text
   beat. Cuts are hard; save fades for a real change of place or time.
4. Keep text out of the top 290 px, the bottom 430 px and the right 162 px of the 1080x1920 frame.
5. End on the payoff or the ask, held for 2 to 3 s.

Example, one instance of the shape (a 3.4 s hook clip, then three stills):

```
segments: [ {clip: "hook.mp4", startSec: 0.4, endSec: 3.8},
            {image: "a.jpg", durationSec: 3, zoom: true}, {image: "b.jpg", durationSec: 3, zoom: true},
            {image: "c.jpg", durationSec: 3} ]
texts:    [ {fromSec: 0,   toSec: 3.4,  t: "The hook line",  y: 30, style: "box"},
            {fromSec: 3.4, toSec: 6.4,  t: "The turn",       y: 45, style: "stroke"},
            {fromSec: 6.4, toSec: 12.4, t: "The payoff",     y: 50, style: "dark"} ]
audio:    { track: "music.mp3" }
```

## Voice

A human voice in the first 3 s held viewers about 25% better to second 10 than music alone, and a
narration-free slideshow reads as inauthentic content on YouTube. So every video bound for Instagram,
Facebook or YouTube opens on a spoken line (TikTok: add one when the hook is a statement, skip it for
a trend-sound post).

1. Write the line to be said, not read: at most 8 words per 3 s, the same words as the first text beat.
2. `generate_voice_line({text})` makes a WAV on the person's Google Cloud project (fractions of a cent;
   it only asks to confirm above $0.05). It needs `conductor config set-vertex-project` and the
   Text-to-Speech API enabled; if it returns `VOICE_API_DISABLED`, tell the person the `gcloud` command
   in the error and do not run it for them.
3. Put `audioPath` in `audio.voicePath` with `duck: true`, and paste `captionBeats` into `texts`. They
   are `{fromSec, toSec, t, y, style}` chunks of 4 words or fewer at `y: 62`, clear of the platform UI.
   Add your own hook beats at other `y` values: beats on screen together must not share rows.
4. The first word must be heard inside 0.5 s and every spoken word must be on screen as a caption.
   If the voice starts later in the video, pass `offsetSec` so the beats move with it.
5. `timing: "estimated"` means word times are spread over the measured length by letter count, close
   but not exact: look at the contact sheet and move a beat if a caption lands on the wrong word.
6. The voice is synthetic: apply the AI label rules in `references/compliance-checklist.md`, section 2.
   The audio's `.provenance.json` records it.

Narration longer than about 60 words is not supported: cut the script instead.

## The checks

The result has `checks` and `passed`. An error means fix the spec and call again before showing anything.

| Check | Severity | Meaning |
|---|---|---|
| `firstSecondText` | error | No text beat covers 0 to 1 s |
| `firstSecondMotion` | error / warning | Nothing moves between 0 s and 0.8 s (error); the opening is a still image (warning: a moving clip hooks better) |
| `textOverlap` | error | Two beats on screen together cover the same part of the frame |
| `safeZone`, `spill` | error | Text under the platform UI or outside the frame |
| `wordsPerLine`, `textSize` | warning | Lines over 8 words, text under 40 px |
| `staticStretch` | warning | More than 5 s with no cut and no text change (the time ranges are listed) |
| `safeZoneText` | error / warning | OCR found text in a platform UI area of the finished video (top zone before 3 s is an error) |
| `sourceResolution` | warning / error | A source smaller than 1080x1920 is upscaled; over 2x is refused |

Spec-level errors stop the tool before encoding (`rendered: false`), so they cost nothing.

## Preview and commit

1. `compose_video` returns `videoPath`, `frame0Path`, a contact sheet of 0 to 3 s (frames every half
   second; look at it, the first three seconds are the hook) and the timeline.
2. `preview_creative_draft({kind: "CLIP", clipPath: videoPath})` adds the platform-UI overlay and the
   safe-zone check. Show the overlay and the contact sheet, and say the total length.
3. Only after the person approves: `upload_creative_media` for the file, then `create_creative` with
   kind `CLIP` and `clipMedia: {"9x16": mediaId}`. A CLIP has no `commit_creative_draft` step. If an input
   was AI-generated, the video's provenance sidecar says so; keep the AI label on.
4. To change it, edit the spec and run `compose_video` again; the earlier folder stays under
   `.conductor/videos/`.
