<!-- Keep identical to the copy in the other creative skill (conductor-creative / conductor-content-studio references/realism-check.md). Skills install independently and cannot reference each other. -->
# Realism check for generated photos

This is about image quality, not concealment. The `aiGenerated: true` provenance and the platform's
AI label still apply to every generated photo, however real it looks.

## When

Every image from `generate_creative_image`, before it is used as `photoPath`. Also glance at library
photos marked `aiGenerated`.

## How to look

Open the full-size file with Read, not only the small inline preview. Look at it twice: at phone
size, as a scrolling viewer would, and zoomed in on the subject.

## Tells

- **Surface and finish**: waxy or plastic skin, food or fabric; everything glossy; over-smoothed
  texture; over-sharpening; HDR glow or halos; colours too saturated.
- **Anatomy**: hands and fingers (count, joints), teeth, eyes and gaze, ears, hair edges merging
  into the background.
- **Objects and physics**: duplicated items (the same garnish, mug or prop twice); objects merging or
  floating; tools or handles that do not connect to anything; impossible reflections; steam or liquid
  that looks painted; wrong scale.
- **Scene and light**: light direction or shadows that disagree; everything perfectly tidy or
  symmetrical; a staged stock-photo look; background detail that turns to mush; repeating textures.
- **Text and brand**: pseudo-text, garbled labels, invented logos, a fake product or app screen.

## Verdict

PASS, or FAIL naming the specific tell(s). A minor issue that will not show at phone size can PASS
with a note.

## On FAIL

Regenerate with the scene prompt revised to fix the named tell, for example "one bowl of salsa, not
two", "matte surface, soft window light, less sharpening", "hands out of frame", "a worn leather
sofa with a crease, not a showroom set". At most two regenerations per image (each costs about $0.05
on the API, or Codex allowance on the Codex route, which the person has already agreed to). Avoid
hands and faces in close-up unless the idea needs them.

If the third image still fails, stop. Tell the person what looks generated and offer a library
photo, a reference photo (`referenceImagePaths`), or filming.

## Report

When showing the draft for approval, give a one-line realism verdict per generated photo:

- "Realism: passed — natural light, believable clutter"
- "Realism: passed with a note — slightly glossy sauce, invisible at phone size"

The person's approval remains the final check.
