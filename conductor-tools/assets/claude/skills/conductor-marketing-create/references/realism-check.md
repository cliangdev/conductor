# Make it real, then check it

This is about image quality, not concealment. The `aiGenerated: true` provenance and the platform's
AI label still apply to every generated photo, however real it looks.

A realistic photo comes from the prompt first and the check second. Write the prompt with the rules
below, then check the result at the size it will be seen.

## Write the prompt

These rules apply to every `generate_creative_image` call, from the art director and from the
orchestrator.

- **Shoot it like a home cook's phone photo, not an ad.**
  - Light: ordinary kitchen or window light. Normal dynamic range, a slightly off white balance, a
    little sensor noise.
  - Banned prompt words: cinematic, stunning, perfect, hyper-detailed, 8k, award-winning, golden
    hour, dramatic lighting, professional food photography, bokeh, glossy, studio.
- **Every repeated item is different.** When a dish has several of something (stuffed peppers,
  tacos, cookies, dumplings, slices), the prompt names how they differ:
  - size, shape and lean;
  - how each is cut or carved;
  - char and blistering on one side only, a split skin on one;
  - filling heaped on one and sunk on another;
  - cheese melted unevenly, with one spot browner;
  - one slightly collapsed.

  Example for jack-o'-lantern stuffed peppers: "four peppers of different sizes and shades of
  orange, one leaning against another, each with a differently carved face (one lopsided, one with a
  missing tooth, one with small round eyes), the tallest has blistered skin on one side, filling
  spilling over the rim of one, cheese melted unevenly."
- **Texture over shine.** Food that is matte in life stays matte. Sauces are a little broken or
  dried at the edges. There are crumbs, drips and a smear on the dish rim. No steam wisps, no perfect
  cheese pulls, no scattered garnish confetti.
- **A real setting, lightly used.** Choose things a home has:
  - a sheet pan or baking dish with scorch marks, or a scratched enamel pot;
  - a worn wooden board, a laminate counter, a dish towel, a fork already in it.
  - Do not stage it. Something is slightly cut off at the frame edge.
- **Off-center and imperfect framing.** No symmetrical hero composition. A slight tilt, an ordinary
  handheld angle (overhead or about 45 degrees, not low and dramatic).
- **Vary across a batch.** Each image in a set gets its own surface, angle and light; only the
  realism and the absence of on-image text are shared. One prompt with the dish name swapped in is a
  defect.
- **No people's faces, hands only if needed.** No text, logos or packaging brands in the image.
- **A generated person is b-roll**: hands, a kitchen, an action. Never a generated person reacting
  to, endorsing or speaking about the product: it reads as a fake testimonial, and fully generated
  testimonial and emotional content takes the largest engagement penalty of any AI content.

For a subject that is not food, keep the same spirit: ordinary light, a lived-in setting, every
repeated object different, imperfect framing.

## When to check

Every image from `generate_creative_image`, before it is used as `photoPath`. Photos already in a
library are not exempt: an existing recipe photo gets the same check, whoever made it.

## How to look

Check a 100% crop of the region that will be on screen, at the size and crop of each placement:
4:5, 9:16 and, for a MOTION, the video zoom (up to 1.12x). Open the full-size file with Read and
crop it. Never judge from a contact sheet: it hides exactly the tells below.

## Tells

- **Light**: golden-hour or HDR glow, halos, dramatic or studio lighting, perfect white balance,
  colours too saturated, over-sharpening.
- **Repeated items**: identical copies of the same item, the same garnish or prop twice.
- **Objects and physics**: melted or merged objects, floating items, utensils or handles that do not
  connect to anything, impossible reflections, steam or liquid that looks painted, wrong scale.
- **Text**: pseudo-text, garbled labels, invented logos, a fake product or app screen.
- **Texture**: plastic or waxy food, skin or fabric; everything glossy; over-smoothed surfaces;
  perfect cheese pulls.
- **Anatomy**: hands and fingers (count, joints), teeth, eyes, hair edges merging into the
  background.
- **Detail under zoom**: smeared or mushy detail, repeating textures, a staged stock-photo look.

## Video and audio (generated clips)

For a clip from `generate_creative_video`, see `references/direct.md`, "Video from a still". Check the
4 fps contact sheet, then 100% crops of the face and hands at 2 or 3 timestamps (ffmpeg frame
extraction), then listen to the audio if there is any. Any tell is a FAIL.

- **Role**: a generated person who reacts to, endorses or talks about the product is a FAIL,
  whatever else passes. Generated people appear only as b-roll (hands, an action, a setting).
- **Face**: exaggerated or bug eyes, rubber faces, unnatural mouth motion.
- **Hands**: morphing or merging, fingers appearing or vanishing.
- **Identity**: the person, clothes or set drifting between frames.
- **Background**: warping or flicker.
- **Text**: garbled text or logos appearing.
- **Camera**: a move that does not match a handheld phone.
- **Audio**: lip-sync drift, a robotic or mismatched voice, sound that does not match the action,
  music or speech nobody asked for, clipped endings.

On FAIL, retry at most twice with the failed tells named in the prompt; then the Standard-tier offer
in `direct.md`. Never ship a FAIL.

## Verdict

Write PASS or FAIL for each placement crop, naming the tell on a FAIL. Any tell above that shows in
a crop is a FAIL. A flaw that shows in no placement crop can PASS with a note.

## On FAIL

Regenerate with the failed tells named in the new prompt, for example "four different peppers, no
two alike", "matte sauce, dried at the edges, soft window light", "hands out of frame". At most two
regenerations per image (each uses Codex allowance, or bills the API key on the fallback route, more at
larger sizes). Then stop. Tell the person what still looks generated and offer a
library photo, a reference photo (`referenceImagePaths`), or filming. Never ship a FAIL.

## Report

When showing the draft for approval, give a one-line realism verdict per generated photo:

- "Realism: passed — natural light, believable clutter"
- "Realism: passed with a note — a smudge at the far edge, outside every placement crop"

The person's approval remains the final check.
