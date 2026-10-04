# Pre-publish compliance checklist

Run by the orchestrator in stage S5b against `brief.md`, `script.md`, `direction.md`,
`creative-spec.json` and the media provenance in `context/media.json`. The creative director uses
the veto questions (marked **Veto**) in its devil's-advocate pass. Every check is a yes/no
question with what to do and the exact publish option it sets. Record each answer in
`publish-pack.md` and the options in `publish-pack.json` (see `publish-pack-template.md`).

Option keys here are the keys `list_publish_targets` reports under a destination's `optionKeys`
(camelCase, set under `options` in `create_post` or `publishOptions` in `set_publish_targets`).
That list is the authority at publish time: if a key is not in a destination's `optionKeys`, do not
send it. This is guidance for a team that makes content, not legal advice; platform and regulator
rules change, so check current guidance before relying on it.

## 1. Disclosure (Veto)

**Is anyone in or behind this post paid, gifted, an employee or an affiliate?** (A creator, a
customer given free product, a staff member speaking about the employer, a partner brand, an
affiliate link.)

- **Yes**: the relationship is disclosed in the first seconds of any video and in the caption's
  opening line (for example "Paid partnership with ...", "Gifted by ...", "Affiliate link").
  Set TikTok `brandContentToggle: true`. TikTok refuses `brandContentToggle` on a post whose
  `privacyLevel` is `SELF_ONLY`, so the post must be visible.
- **No, this is the brand promoting its own product on its own account**: set TikTok
  `brandOrganicToggle: true`. No on-screen disclosure line is needed beyond being obviously the
  brand's own post.
- **Which toggle:** "Your brand" (`brandOrganicToggle`) is a business promoting itself.
  "Branded content" (`brandContentToggle`) is a paid partnership where a third party is promoting
  someone else's business. Never use `brandOrganicToggle` to cover a paid creator.
- Other platforms have no disclosure option in Conductor. Put the disclosure in the caption's first
  line and turn on the platform's own paid-partnership label by hand; list that as a manual step.
- Default when nothing in the request, brief or media points to a third party: the brand's own
  promotional post, so `brandOrganicToggle: true`.

Source: FTC Endorsement Guides FAQ (ftc.gov/business-guidance/resources/ftcs-endorsement-guides-what-people-are-asking):
disclose paid, gifted, employee and affiliate relationships; in video, disclose at the start.

## 2. AI label

**Is any realistic imagery, video or voice AI-generated or AI-altered?** Check the Creative's and
each media file's provenance (`aiGenerated` in `context/media.json`) and the script's visuals.

- **Yes**: set TikTok `isAigc: true` on a video post. Conductor does not send `isAigc` on a TikTok
  photo post, so for a photo post note "turn on TikTok's AI label by hand" as a manual step. Set
  YouTube `containsSyntheticMedia: true`. Meta has no option: note that Instagram's and Facebook's
  "AI info" label must be turned on in the app.
- **No**: text, motion graphics over a real photo, and real photos are exempt. So is content that
  is obviously unrealistic or animated. Say which of these applies.
- Never leave `isAigc` unset when provenance says `aiGenerated: true` for a realistic asset.

Source: TikTok, Meta and YouTube each require disclosure of realistic AI-generated or AI-altered
imagery, video or voice.

## 3. Music and sound (Veto, for TikTok)

**For each platform, what is the sound source and is it licensed for this account?** Name it in the
pack: original audio, a library track (with its recorded `licence`), the clip's own sound, or none.

- A business account cannot use most trending tracks. On TikTok a business account can only use
  the Commercial Music Library, which is licensed for TikTok only, so a track from it is not
  cleared for Reels or Shorts. Use the Commercial Music Library or original audio.
- Never name a trending song a business account cannot license. If the idea depends on one, the
  idea fails this check and the director may disqualify it.
- Instagram Reels `audioName` is an attribution label for a Reel's audio; set it only when the
  sound has a credit to give.
- A library track used in a MOTION Creative must have its licence recorded (the readiness checklist
  blocks otherwise).
- Photo posts on TikTok: `autoAddMusic` lets TikTok pick a soundtrack. Leave it unset unless the
  person chose it, because the track is not one the team vetted.

## 4. Claims (Veto)

**Is every factual or product claim confirmed?** A claim is anything a viewer could check: a
number, "first", "best", a result, a feature, a price, a comparison, a health or savings promise.

- Each must be confirmed by `brief.md`, a knowledge page in `context/knowledge.md` or an approved
  line in the Brand Kit. Quote where.
- If not confirmed: soften it ("can help" instead of "will"), or remove it. If the idea only works
  with the unconfirmed claim, it fails the check.

Source: the FTC's endorsement guidance: don't make claims the advertiser can't substantiate.

## 5. Third-party material (Veto, for parody and trademarks)

**List every third-party asset in the post:** music, footage, logos, memes, screenshots, likenesses
of real people, trademarks and product names, parodies.

- Each gets a row in the pack's third-party list: what it is, whose it is, and the permission or
  licence (own work, licensed, with consent, or "none, remove").
- Anything with no permission is removed or replaced.
- **Flag parody of another company's product or brand.** Even a joke needs a person to approve it;
  if it can't be fixed in copy (it is the whole premise), the idea fails.
- A person's likeness or voice, real or imitated, needs consent.

## 6. Representation and sensitivity (Veto)

**Does anything stereotype, exclude, mock or trade on a sensitive subject (health, money
hardship, grief, religion, politics, a protected group)?** A quick read of the hook, the visuals
and the caption.

- If yes and it can be fixed by changing words or the picture, fix it and note the change.
- If it can't be fixed, the idea fails.

## 7. Accessibility

**Does the post work for someone who cannot see it or cannot hear it?**

- Alt text for every image or card (at most 1,000 characters on Instagram). Carry it in the
  Creative's `altText` and in the pack. Instagram `altText` is an option only on a single feed
  image; cards in a carousel cannot each carry published alt text, so list it in the pack.
- Captions or on-screen text for everything spoken, so the post works with the sound off. Burned-in
  text is preferred over relying on a platform's auto-captions.
- Contrast and safe zones are already checked by the renderer; do not repeat them.

## Result

Write a table: check, answer (yes / no / not applicable), what was done, option set. A check that
fails and can't be fixed in copy goes back to the scriptwriter once (S5b step 4); if it still
fails, say so at the gate rather than hiding it.
