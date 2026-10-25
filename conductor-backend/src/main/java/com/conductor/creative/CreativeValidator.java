package com.conductor.creative;

import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.conductor.creative.CreativeValidationException.Violation;

/**
 * A data-driven port of nexus-marketing/social/core.mjs's {@code validate()} and {@code copyErrors()} —
 * every rule the Creative library enforces, run against the {@link BrandKit} a Creative renders with.
 *
 * <p>Two groups of rule, both collected into one {@link CreativeValidationException} so a form can
 * highlight every failing field in one round trip:
 *
 * <ul>
 *   <li><b>Structural</b> — always enforced, on every create/patch/variant write: the layout and
 *       placement keys are real, the layout supports the requested theme, the kit's accent-phrase rule,
 *       every Brand Kit copy rule, and sequence shape (story 2-7 beats, carousel 2-10 cards, every beat
 *       after the first needs its own headline, every referenced photo exists in the project).</li>
 *   <li><b>Readiness</b> — enforced only when the Creative is or is becoming {@code READY}: a photo is
 *       chosen, uploaded and not blocked, and the headline is non-blank. (The full readiness checklist,
 *       including non-blocking items like caption/alt text, is {@link CreativeService#readiness}, a
 *       separate read — this method only re-checks the subset that blocks the state transition.)</li>
 * </ul>
 */
@Component
public class CreativeValidator {

    /** Sane pixel bounds for a {@code layoutOverrides.band}/{@code padBottom} value — generous enough
     * for any placement's own artboard height (the tallest today is 1920px) without letting a typo'd
     * override (e.g. a stray extra zero) blow past what any layout could sensibly use. */
    static final int LAYOUT_OVERRIDE_MIN_PX = 0;
    static final int LAYOUT_OVERRIDE_MAX_PX = 4000;

    /** Sane bounds for a pinned {@code typeOverrides} entry {@code [fontSize, lineHeight?, letterSpacing?]}:
     * a font size in artboard px, a unitless line-height multiplier, and a letter-spacing in px. */
    static final int TYPE_SIZE_MAX_PX = 600;
    static final double TYPE_LEADING_MAX = 3.0;
    static final int TYPE_TRACKING_LIMIT_PX = 100;

    private final CreativeRegistry registry;

    public CreativeValidator(CreativeRegistry registry) {
        this.registry = registry;
    }

    /** One story beat or carousel card, mirroring the generated {@code SequenceBeat} DTO. */
    public record SequenceBeat(String headline, String body, String photoId) {
    }

    /**
     * One entry of a CLIP creative's {@code clipMedia} map (COND-24 PR1) — {@code placementKey} is either
     * {@code "default"} or a real registry placement key, resolved (by {@link CreativeService}, which has
     * database access) against the project's media library before the validator ever sees it.
     */
    public record ClipMediaEntry(String placementKey, String mediaId, boolean resolvable, boolean isVideo,
                                 boolean uploaded, boolean blocked) {
    }

    /**
     * One slide of a CAROUSEL creative's {@code slides} map, resolved by {@link CreativeService} against the
     * media library (or, for a draft, the {@code localMedia} declarations) before the validator sees it.
     * {@code width}/{@code height} are null when unknown (the aspect check is then skipped).
     */
    public record SlideEntry(String placementKey, int index, String mediaId, boolean resolvable, boolean isImage,
                             boolean uploaded, boolean blocked, Integer width, Integer height, boolean gif) {

        /** A slide that is not known to be a GIF. */
        public SlideEntry(String placementKey, int index, String mediaId, boolean resolvable, boolean isImage,
                          boolean uploaded, boolean blocked, Integer width, Integer height) {
            this(placementKey, index, mediaId, resolvable, isImage, uploaded, blocked, width, height, false);
        }
    }

    /** The placement keys a CAROUSEL's {@code slides} map may name (besides {@code "default"}). */
    public static final List<String> CAROUSEL_PLACEMENTS = List.of("4x5", "9x16", "1x1");
    /** Instagram / Facebook / the default list: 10 images; TikTok photo posts (9x16): 35. */
    public static final int CAROUSEL_MAX_SLIDES = 10;
    public static final int CAROUSEL_MAX_SLIDES_9X16 = 35;
    /** Relative aspect-ratio tolerance: {@code |w/h - target| / target}. */
    public static final double CAROUSEL_ASPECT_TOLERANCE = 0.02;

    /**
     * A MOTION creative's {@code motion}/{@code audio} (COND-24 PR2), with every media reference already
     * resolved by the caller ({@link CreativeService}/{@link CreativeRenderService}, both of which have
     * database access) — the validator itself never touches the database. Numeric fields are already
     * defaulted by the caller before this reaches the validator (see {@code CreativeService#applyMotion
     * Defaults}), so a null here only ever means "field genuinely omitted from a raw, not-yet-defaulted
     * write" in a unit test.
     */
    public record MotionInput(
            String preset,
            Double durationSec,
            String backgroundSource,
            String backgroundMotion,
            String backgroundClipMediaId,
            boolean backgroundClipResolvable,
            boolean backgroundClipIsVideo,
            boolean backgroundClipUploaded,
            boolean backgroundClipBlocked,
            Double backgroundClipDurationSeconds,
            Boolean backgroundClipHasAudio,
            Double clipStartSec,
            Boolean endCard,
            String audioSource,
            String audioTrackId,
            boolean audioTrackResolvable,
            boolean audioTrackIsAudio,
            boolean audioTrackUploaded,
            boolean audioTrackBlocked,
            Double volume,
            Double fadeOutSec) {

        /**
         * The validator's view of a MOTION creative, from its {@code motion}/{@code audio} and the media
         * they reference ({@code clip}/{@code track}: null when the id is unset or did not resolve) —
         * shared by the create/patch/variant writes, a render of a saved Creative, and a draft spec, so all
         * of them judge the same facts the same way. Null when {@code motion} is.
         */
        public static MotionInput resolve(CreativeMotion motion, CreativeAudio audio, MediaFacts clip, MediaFacts track) {
            if (motion == null) {
                return null;
            }
            CreativeMotionBackground background = motion.getBackground();
            String clipMediaId = background != null ? background.getClipMediaId() : null;
            String trackId = audio != null ? audio.getTrackId() : null;
            return new MotionInput(
                    motion.getPreset(),
                    motion.getDurationSec() != null ? motion.getDurationSec().doubleValue() : null,
                    background != null ? background.getSource() : null,
                    background != null ? background.getMotion() : null,
                    clipMediaId,
                    clipMediaId == null || clip != null,
                    clip != null && clip.isVideo(),
                    clip != null && clip.isUploaded(),
                    clip != null && clip.isBlocked(),
                    clip != null && clip.getDurationSeconds() != null ? clip.getDurationSeconds().doubleValue() : null,
                    clip != null ? clip.getHasAudio() : null,
                    background != null && background.getClipStartSec() != null ? background.getClipStartSec().doubleValue() : null,
                    motion.getEndCard(),
                    audio != null ? audio.getSource() : null,
                    trackId,
                    trackId == null || track != null,
                    track != null && track.isAudio(),
                    track != null && track.isUploaded(),
                    track != null && track.isBlocked(),
                    audio != null && audio.getVolume() != null ? audio.getVolume().doubleValue() : null,
                    audio != null && audio.getFadeOutSec() != null ? audio.getFadeOutSec().doubleValue() : null);
        }

        /** A test-friendly builder — every field defaults to "structurally valid" (fade-up preset, 8s
         *  duration, photo background at zoom-in, no audio) so a test sets only what its case is about. */
        public static final class Builder {
            private String preset = "fade-up";
            private Double durationSec = 8.0;
            private String backgroundSource = "photo";
            private String backgroundMotion = "zoom-in";
            private String backgroundClipMediaId;
            private boolean backgroundClipResolvable = true;
            private boolean backgroundClipIsVideo;
            private boolean backgroundClipUploaded;
            private boolean backgroundClipBlocked;
            private Double backgroundClipDurationSeconds;
            private Boolean backgroundClipHasAudio;
            private Double clipStartSec;
            private Boolean endCard = true;
            private String audioSource = "none";
            private String audioTrackId;
            private boolean audioTrackResolvable = true;
            private boolean audioTrackIsAudio;
            private boolean audioTrackUploaded;
            private boolean audioTrackBlocked;
            private Double volume = 0.8;
            private Double fadeOutSec = 1.0;

            public Builder preset(String v) { this.preset = v; return this; }
            public Builder durationSec(Double v) { this.durationSec = v; return this; }
            public Builder backgroundSource(String v) { this.backgroundSource = v; return this; }
            public Builder backgroundMotion(String v) { this.backgroundMotion = v; return this; }
            public Builder backgroundClipMediaId(String v) { this.backgroundClipMediaId = v; return this; }
            public Builder backgroundClipResolvable(boolean v) { this.backgroundClipResolvable = v; return this; }
            public Builder backgroundClipIsVideo(boolean v) { this.backgroundClipIsVideo = v; return this; }
            public Builder backgroundClipUploaded(boolean v) { this.backgroundClipUploaded = v; return this; }
            public Builder backgroundClipBlocked(boolean v) { this.backgroundClipBlocked = v; return this; }
            public Builder backgroundClipDurationSeconds(Double v) { this.backgroundClipDurationSeconds = v; return this; }
            public Builder backgroundClipHasAudio(Boolean v) { this.backgroundClipHasAudio = v; return this; }
            public Builder clipStartSec(Double v) { this.clipStartSec = v; return this; }
            public Builder endCard(Boolean v) { this.endCard = v; return this; }
            public Builder audioSource(String v) { this.audioSource = v; return this; }
            public Builder audioTrackId(String v) { this.audioTrackId = v; return this; }
            public Builder audioTrackResolvable(boolean v) { this.audioTrackResolvable = v; return this; }
            public Builder audioTrackIsAudio(boolean v) { this.audioTrackIsAudio = v; return this; }
            public Builder audioTrackUploaded(boolean v) { this.audioTrackUploaded = v; return this; }
            public Builder audioTrackBlocked(boolean v) { this.audioTrackBlocked = v; return this; }
            public Builder volume(Double v) { this.volume = v; return this; }
            public Builder fadeOutSec(Double v) { this.fadeOutSec = v; return this; }

            public MotionInput build() {
                return new MotionInput(preset, durationSec, backgroundSource, backgroundMotion, backgroundClipMediaId,
                        backgroundClipResolvable, backgroundClipIsVideo, backgroundClipUploaded, backgroundClipBlocked,
                        backgroundClipDurationSeconds, backgroundClipHasAudio, clipStartSec, endCard, audioSource,
                        audioTrackId, audioTrackResolvable, audioTrackIsAudio, audioTrackUploaded, audioTrackBlocked,
                        volume, fadeOutSec);
            }
        }
    }

    /**
     * A CAROUSEL's or CLIP's {@code audio} with its track (and, for a CLIP, its clip's sound) already
     * resolved by the caller — MOTION carries the same facts inside {@link MotionInput}. Numeric fields are
     * defaulted by the caller; a null only means "omitted" in a unit test.
     *
     * @param clipHasAudio for a CLIP: false when any chosen clip is known to have no sound, true when every
     *                     known clip has some, null when there is no clip or its sound is unknown
     */
    public record AudioInput(
            String source,
            String trackId,
            boolean trackResolvable,
            boolean trackIsAudio,
            boolean trackUploaded,
            boolean trackBlocked,
            Double volume,
            Double fadeOutSec,
            Boolean clipHasAudio) {

        /** A test-friendly builder: structurally valid "no sound" by default. */
        public static final class Builder {
            private String source = "none";
            private String trackId;
            private boolean trackResolvable = true;
            private boolean trackIsAudio;
            private boolean trackUploaded;
            private boolean trackBlocked;
            private Double volume = 1.0;
            private Double fadeOutSec = 1.0;
            private Boolean clipHasAudio;

            public Builder source(String v) { this.source = v; return this; }
            public Builder trackId(String v) { this.trackId = v; return this; }
            public Builder trackResolvable(boolean v) { this.trackResolvable = v; return this; }
            public Builder trackIsAudio(boolean v) { this.trackIsAudio = v; return this; }
            public Builder trackUploaded(boolean v) { this.trackUploaded = v; return this; }
            public Builder trackBlocked(boolean v) { this.trackBlocked = v; return this; }
            public Builder volume(Double v) { this.volume = v; return this; }
            public Builder fadeOutSec(Double v) { this.fadeOutSec = v; return this; }
            public Builder clipHasAudio(Boolean v) { this.clipHasAudio = v; return this; }

            /** A valid library track: resolves, is AUDIO, uploaded, unblocked. */
            public Builder goodTrack(String id) {
                this.trackId = id;
                this.trackResolvable = true;
                this.trackIsAudio = true;
                this.trackUploaded = true;
                this.trackBlocked = false;
                return this;
            }

            public AudioInput build() {
                return new AudioInput(source, trackId, trackResolvable, trackIsAudio, trackUploaded, trackBlocked,
                        volume, fadeOutSec, clipHasAudio);
            }
        }
    }

    /** A CAROUSEL's {@code slideshow}; {@code slideCount} is the length of its 9x16 slide list (the
     *  default list when there is no 9x16 one), 0 when there are no slides yet. */
    public record SlideshowInput(List<Double> slideDurations, int slideCount) {
    }

    /** Everything {@link #validate} needs to know about the intended state of a Creative. */
    public record Input(
            String layout,
            String theme,
            List<String> placements,
            String headline,
            String body,
            String caption,
            String state,
            String photoId,
            boolean photoIdResolvable,
            boolean photoPresent,
            boolean photoUploaded,
            boolean photoBlocked,
            String sequenceKind,
            List<SequenceBeat> sequence,
            String carouselRatio,
            Set<String> unknownSequencePhotoIds,
            Map<String, Integer> layoutOverrideBand,
            Map<String, Integer> layoutOverridePadBottom,
            String kind,
            List<ClipMediaEntry> clipMedia,
            MotionInput motion,
            Map<String, List<SlideEntry>> slides,
            List<String> slideAltText,
            boolean photoGif,
            AudioInput audio,
            SlideshowInput slideshow) {

        /** Without the audio and slideshow (MOTION keeps its audio inside {@link MotionInput}). */
        public Input(String layout, String theme, List<String> placements, String headline, String body,
                     String caption, String state, String photoId, boolean photoIdResolvable, boolean photoPresent,
                     boolean photoUploaded, boolean photoBlocked, String sequenceKind, List<SequenceBeat> sequence,
                     String carouselRatio, Set<String> unknownSequencePhotoIds,
                     Map<String, Integer> layoutOverrideBand, Map<String, Integer> layoutOverridePadBottom,
                     String kind, List<ClipMediaEntry> clipMedia, MotionInput motion,
                     Map<String, List<SlideEntry>> slides, List<String> slideAltText, boolean photoGif) {
            this(layout, theme, placements, headline, body, caption, state, photoId, photoIdResolvable,
                    photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence, carouselRatio,
                    unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom, kind, clipMedia, motion,
                    slides, slideAltText, photoGif, null, null);
        }

        /** The same input with a CAROUSEL's or CLIP's audio and a CAROUSEL's slideshow. */
        public Input withAudioAndSlideshow(AudioInput audio, SlideshowInput slideshow) {
            return new Input(layout, theme, placements, headline, body, caption, state, photoId, photoIdResolvable,
                    photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence, carouselRatio,
                    unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom, kind, clipMedia, motion,
                    slides, slideAltText, photoGif, audio, slideshow);
        }

        /** Without the GIF flag: the main photo is not known to be a GIF. */
        public Input(String layout, String theme, List<String> placements, String headline, String body,
                     String caption, String state, String photoId, boolean photoIdResolvable, boolean photoPresent,
                     boolean photoUploaded, boolean photoBlocked, String sequenceKind, List<SequenceBeat> sequence,
                     String carouselRatio, Set<String> unknownSequencePhotoIds,
                     Map<String, Integer> layoutOverrideBand, Map<String, Integer> layoutOverridePadBottom,
                     String kind, List<ClipMediaEntry> clipMedia, MotionInput motion,
                     Map<String, List<SlideEntry>> slides, List<String> slideAltText) {
            this(layout, theme, placements, headline, body, caption, state, photoId, photoIdResolvable,
                    photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence, carouselRatio,
                    unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom, kind, clipMedia, motion,
                    slides, slideAltText, false);
        }

        /** The same input with the main photo flagged as a GIF (or not). */
        public Input withPhotoGif(boolean gif) {
            return new Input(layout, theme, placements, headline, body, caption, state, photoId, photoIdResolvable,
                    photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence, carouselRatio,
                    unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom, kind, clipMedia, motion,
                    slides, slideAltText, gif, audio, slideshow);
        }

        /** The pre-CAROUSEL shape: no slides, no slide alt text. */
        public Input(String layout, String theme, List<String> placements, String headline, String body,
                     String caption, String state, String photoId, boolean photoIdResolvable, boolean photoPresent,
                     boolean photoUploaded, boolean photoBlocked, String sequenceKind, List<SequenceBeat> sequence,
                     String carouselRatio, Set<String> unknownSequencePhotoIds,
                     Map<String, Integer> layoutOverrideBand, Map<String, Integer> layoutOverridePadBottom,
                     String kind, List<ClipMediaEntry> clipMedia, MotionInput motion) {
            this(layout, theme, placements, headline, body, caption, state, photoId, photoIdResolvable,
                    photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence, carouselRatio,
                    unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom, kind, clipMedia, motion,
                    Map.of(), List.of(), false);
        }

        /**
         * A test-friendly builder for {@link Input} — every field defaults to "structurally valid and
         * uninvolved" (e.g. a real layout, an empty sequence) so a test can set only the field(s) its case
         * is about. {@link CreativeService} builds {@link Input} directly with the full constructor since
         * it always has every field in hand; this builder exists for {@code CreativeValidatorTest}.
         */
        public static final class InputBuilder {
            private String layout = "stacked";
            private String theme = Creative.THEME_DARK;
            private List<String> placements = List.of();
            private String headline;
            private String body;
            private String caption;
            private String state = Creative.STATE_DRAFT;
            private String photoId;
            private boolean photoIdResolvable = true;
            private boolean photoPresent;
            private boolean photoUploaded;
            private boolean photoBlocked;
            private String sequenceKind;
            private List<SequenceBeat> sequence = List.of();
            private String carouselRatio;
            private Set<String> unknownSequencePhotoIds = Set.of();
            private Map<String, Integer> layoutOverrideBand = Map.of();
            private Map<String, Integer> layoutOverridePadBottom = Map.of();
            private String kind = Creative.KIND_STILL;
            private List<ClipMediaEntry> clipMedia = List.of();
            private MotionInput motion;
            private Map<String, List<SlideEntry>> slides = Map.of();
            private List<String> slideAltText = List.of();
            private boolean photoGif;
            private AudioInput audio;
            private SlideshowInput slideshow;

            public InputBuilder audio(AudioInput v) { this.audio = v; return this; }
            public InputBuilder slideshow(SlideshowInput v) { this.slideshow = v; return this; }
            public InputBuilder photoGif(boolean v) { this.photoGif = v; return this; }
            public InputBuilder slides(Map<String, List<SlideEntry>> v) { this.slides = v; return this; }
            public InputBuilder slideAltText(List<String> v) { this.slideAltText = v; return this; }
            public InputBuilder layout(String v) { this.layout = v; return this; }
            public InputBuilder theme(String v) { this.theme = v; return this; }
            public InputBuilder placements(List<String> v) { this.placements = v; return this; }
            public InputBuilder headline(String v) { this.headline = v; return this; }
            public InputBuilder body(String v) { this.body = v; return this; }
            public InputBuilder caption(String v) { this.caption = v; return this; }
            public InputBuilder state(String v) { this.state = v; return this; }
            public InputBuilder photoId(String v) { this.photoId = v; return this; }
            public InputBuilder photoIdResolvable(boolean v) { this.photoIdResolvable = v; return this; }
            public InputBuilder photoPresent(boolean v) { this.photoPresent = v; return this; }
            public InputBuilder photoUploaded(boolean v) { this.photoUploaded = v; return this; }
            public InputBuilder photoBlocked(boolean v) { this.photoBlocked = v; return this; }
            public InputBuilder sequenceKind(String v) { this.sequenceKind = v; return this; }
            public InputBuilder sequence(List<SequenceBeat> v) { this.sequence = v; return this; }
            public InputBuilder carouselRatio(String v) { this.carouselRatio = v; return this; }
            public InputBuilder unknownSequencePhotoIds(Set<String> v) { this.unknownSequencePhotoIds = v; return this; }
            public InputBuilder layoutOverrideBand(Map<String, Integer> v) { this.layoutOverrideBand = v; return this; }
            public InputBuilder layoutOverridePadBottom(Map<String, Integer> v) { this.layoutOverridePadBottom = v; return this; }
            public InputBuilder kind(String v) { this.kind = v; return this; }
            public InputBuilder clipMedia(List<ClipMediaEntry> v) { this.clipMedia = v; return this; }
            public InputBuilder motion(MotionInput v) { this.motion = v; return this; }

            public Input build() {
                return new Input(layout, theme, placements, headline, body, caption, state, photoId,
                        photoIdResolvable, photoPresent, photoUploaded, photoBlocked, sequenceKind, sequence,
                        carouselRatio, unknownSequencePhotoIds, layoutOverrideBand, layoutOverridePadBottom,
                        kind, clipMedia, motion, slides, slideAltText, photoGif, audio, slideshow);
            }
        }
    }

    public List<Violation> validate(BrandKit kit, Input input) {
        List<Violation> violations = new ArrayList<>();

        if (Creative.KIND_CAROUSEL.equals(input.kind())) {
            // Finished images used as-is: no layout, theme, headline, accent or sequence rules — only the
            // placements, the caption's copy rules, and the slides themselves.
            validatePlacements(input, violations);
            applyCopyRules(kit, "caption", input.caption(), violations);
            validateCarousel(input, violations);
            validateAudioFields(input, violations);
            if (Creative.STATE_READY.equals(input.state())) {
                validateReadyState(input, violations);
            }
            return violations;
        }

        validateLayoutAndTheme(input, violations);
        validatePlacements(input, violations);
        validateLayoutOverrides(input, violations);

        if (kit.isAccentPhraseRequired()) {
            checkAccentPhrase("headline", input.headline(), violations);
            if (input.sequence() != null) {
                for (int i = 0; i < input.sequence().size(); i++) {
                    checkAccentPhrase(sequenceField(i, "headline"), input.sequence().get(i).headline(), violations);
                }
            }
        }

        applyCopyRules(kit, "headline", input.headline(), violations);
        applyCopyRules(kit, "body", input.body(), violations);
        applyCopyRules(kit, "caption", input.caption(), violations);
        if (input.sequence() != null) {
            for (int i = 0; i < input.sequence().size(); i++) {
                SequenceBeat beat = input.sequence().get(i);
                applyCopyRules(kit, sequenceField(i, "headline"), beat.headline(), violations);
                applyCopyRules(kit, sequenceField(i, "body"), beat.body(), violations);
            }
        }

        validateSequenceBounds(input, violations);
        validateClipMedia(input, violations);
        validateMotion(input, violations);
        validateCarousel(input, violations);
        validateAudioFields(input, violations);

        if (input.photoId() != null && !input.photoIdResolvable()) {
            violations.add(new Violation("photoId", "photoNotFound",
                    "No photo with id " + input.photoId() + " in this project"));
        }
        if (input.photoGif() && Creative.KIND_STILL.equals(input.kind())) {
            violations.add(new Violation("photoId", "mediaFormat", "the photo must be JPEG, PNG or WebP"));
        }
        if (input.unknownSequencePhotoIds() != null) {
            for (String id : input.unknownSequencePhotoIds()) {
                violations.add(new Violation("sequence", "sequencePhoto",
                        "No photo with id " + id + " in this project"));
            }
        }

        if (Creative.STATE_READY.equals(input.state())) {
            validateReadyState(input, violations);
        }

        return violations;
    }

    private void validateLayoutAndTheme(Input input, List<Violation> violations) {
        if (!registry.hasLayout(input.layout())) {
            violations.add(new Violation("layout", "layout",
                    "layout must be one of: " + String.join(", ", registry.layouts().keySet())));
            return;
        }
        CreativeRegistry.LayoutInfo layoutInfo = registry.layout(input.layout());
        if (input.theme() != null && !layoutInfo.supportsTheme(input.theme())) {
            violations.add(new Violation("theme", "theme",
                    "theme \"" + input.theme() + "\" is not supported by layout \"" + input.layout()
                            + "\"; it supports: " + String.join(", ", layoutInfo.themes())));
        }
    }

    private void validatePlacements(Input input, List<Violation> violations) {
        if (input.placements() != null) {
            for (String placement : input.placements()) {
                if (!registry.hasPlacement(placement)) {
                    violations.add(new Violation("placements", "placement",
                            "unknown placement \"" + placement + "\", must be one of: "
                                    + String.join(", ", registry.placements().keySet())));
                }
            }
        }
        if (input.carouselRatio() != null && !registry.hasPlacement(input.carouselRatio())) {
            violations.add(new Violation("carouselRatio", "placement",
                    "carouselRatio must be one of: " + String.join(", ", registry.placements().keySet())));
        }
    }

    /**
     * {@code layoutOverrides.band}/{@code padBottom}: every placement key must be real (registry) and
     * every value a sane pixel amount — a data-driven port of the same shape check nexus's schema.json
     * gives its own {@code band}/{@code padBottom} (an object keyed by placement, integer values).
     */
    private void validateLayoutOverrides(Input input, List<Violation> violations) {
        validateOverrideMap("layoutOverrides.band", input.layoutOverrideBand(), violations);
        validateOverrideMap("layoutOverrides.padBottom", input.layoutOverridePadBottom(), violations);
    }

    private void validateOverrideMap(String field, Map<String, Integer> overrides, List<Violation> violations) {
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            String placementKey = entry.getKey();
            if (!registry.hasPlacement(placementKey)) {
                violations.add(new Violation(field, "placement",
                        "unknown placement \"" + placementKey + "\" in " + field + ", must be one of: "
                                + String.join(", ", registry.placements().keySet())));
                continue;
            }
            Integer value = entry.getValue();
            if (value == null || value < LAYOUT_OVERRIDE_MIN_PX || value > LAYOUT_OVERRIDE_MAX_PX) {
                violations.add(new Violation(field, "bounds",
                        field + "[\"" + placementKey + "\"] must be between " + LAYOUT_OVERRIDE_MIN_PX
                                + " and " + LAYOUT_OVERRIDE_MAX_PX + " px"));
            }
        }
    }

    /**
     * A write's {@code typeOverrides} ({@code {"<placementKey>": [fontSize, lineHeight?, letterSpacing?]}}):
     * every key a real placement and every entry one to three sane numbers (only {@code lineHeight} and
     * {@code letterSpacing} may be null, meaning "use the engine's default for this size"). Shared by create
     * (and so a draft spec) and update, which only call it when the request carries the field.
     */
    public List<Violation> validateTypeOverrides(Map<String, List<BigDecimal>> overrides) {
        List<Violation> violations = new ArrayList<>();
        if (overrides == null) {
            return violations;
        }
        for (Map.Entry<String, List<BigDecimal>> entry : overrides.entrySet()) {
            String placementKey = entry.getKey();
            String field = "typeOverrides[\"" + placementKey + "\"]";
            if (!registry.hasPlacement(placementKey)) {
                violations.add(new Violation("typeOverrides", "placement",
                        "unknown placement \"" + placementKey + "\" in typeOverrides, must be one of: "
                                + String.join(", ", registry.placements().keySet())));
                continue;
            }
            List<BigDecimal> value = entry.getValue();
            if (value == null || value.isEmpty() || value.size() > 3) {
                violations.add(new Violation("typeOverrides", "shape",
                        field + " must be [fontSize, lineHeight?, letterSpacing?]: one to three numbers"));
                continue;
            }
            BigDecimal size = value.get(0);
            if (size == null || size.signum() <= 0 || size.compareTo(BigDecimal.valueOf(TYPE_SIZE_MAX_PX)) > 0) {
                violations.add(new Violation("typeOverrides", "bounds",
                        field + " fontSize must be greater than 0 and at most " + TYPE_SIZE_MAX_PX + " px"));
            }
            BigDecimal leading = value.size() > 1 ? value.get(1) : null;
            if (leading != null && (leading.signum() <= 0 || leading.compareTo(BigDecimal.valueOf(TYPE_LEADING_MAX)) > 0)) {
                violations.add(new Violation("typeOverrides", "bounds",
                        field + " lineHeight must be greater than 0 and at most " + TYPE_LEADING_MAX
                                + " (a multiplier of fontSize)"));
            }
            BigDecimal tracking = value.size() > 2 ? value.get(2) : null;
            if (tracking != null && tracking.abs().compareTo(BigDecimal.valueOf(TYPE_TRACKING_LIMIT_PX)) > 0) {
                violations.add(new Violation("typeOverrides", "bounds",
                        field + " letterSpacing must be between -" + TYPE_TRACKING_LIMIT_PX + " and "
                                + TYPE_TRACKING_LIMIT_PX + " px"));
            }
        }
        return violations;
    }

    /** nexus's rule: exactly one pair of asterisks around the accent phrase, e.g. {@code "*so easy*"}. */
    private void checkAccentPhrase(String field, String text, List<Violation> violations) {
        if (text == null || text.isBlank()) {
            return;
        }
        long marks = text.chars().filter(c -> c == '*').count();
        if (marks != 2) {
            violations.add(new Violation(field, "accentPhrase",
                    field + " needs exactly one *accent phrase* (found " + marks + " asterisk" + (marks == 1 ? "" : "s") + ")"));
        }
    }

    private void applyCopyRules(BrandKit kit, String field, String text, List<Violation> violations) {
        if (text == null || text.isBlank() || kit.getCopyRules() == null) {
            return;
        }
        String bareField = bareField(field);
        for (JsonNode rule : kit.getCopyRules()) {
            if (!ruleAppliesToField(rule, bareField)) {
                continue;
            }
            String candidate = text;
            String exceptPattern = textOrNull(rule.get("exceptPattern"));
            if (exceptPattern != null) {
                candidate = compilePattern(exceptPattern, null).matcher(candidate).replaceAll("");
            }
            String pattern = textOrNull(rule.get("pattern"));
            String flags = textOrNull(rule.get("flags"));
            if (pattern == null || !compilePattern(pattern, flags).matcher(candidate).find()) {
                continue;
            }
            String ruleId = textOrNull(rule.get("id"));
            String message = textOrNull(rule.get("message"));
            violations.add(new Violation(field, ruleId != null ? ruleId : "copyRule",
                    message != null ? message : (field + " fails a Brand Kit copy rule")));
        }
    }

    private static boolean ruleAppliesToField(JsonNode rule, String bareField) {
        JsonNode fields = rule.get("fields");
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode f : fields) {
            if (bareField.equals(f.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Strips a {@code sequence[i].} prefix so a copy rule's {@code fields} (headline/body/caption) still match. */
    private static String bareField(String field) {
        int dot = field.lastIndexOf('.');
        return dot >= 0 ? field.substring(dot + 1) : field;
    }

    private static String sequenceField(int index, String suffix) {
        return "sequence[" + index + "]." + suffix;
    }

    private void validateSequenceBounds(Input input, List<Violation> violations) {
        if (input.sequenceKind() == null) {
            return;
        }
        List<SequenceBeat> sequence = input.sequence() != null ? input.sequence() : List.of();
        int min = "story".equals(input.sequenceKind()) ? 2 : 2;
        int max = "story".equals(input.sequenceKind()) ? 7 : 10;
        String what = "story".equals(input.sequenceKind()) ? "beat" : "card";
        if (sequence.size() < min) {
            violations.add(new Violation("sequence", "sequenceBounds",
                    input.sequenceKind() + " must have at least " + min + " " + what + "s; one is just a Creative"));
            return;
        }
        if (sequence.size() > max) {
            violations.add(new Violation("sequence", "sequenceBounds",
                    input.sequenceKind() + " has " + sequence.size() + " " + what + "s, more than the " + max + " allowed"));
            return;
        }
        for (int i = 1; i < sequence.size(); i++) {
            SequenceBeat beat = sequence.get(i);
            if (beat.headline() == null || beat.headline().isBlank()) {
                violations.add(new Violation(sequenceField(i, "headline"), "sequenceHeadline",
                        what + " " + (i + 1) + " needs its own headline, or it repeats the one before it"));
            }
        }
    }

    private void validateReadyState(Input input, List<Violation> violations) {
        if (Creative.KIND_CAROUSEL.equals(input.kind())) {
            if (input.caption() == null || input.caption().isBlank()) {
                violations.add(new Violation("caption", "captionRequired",
                        "caption is required before this Creative can go up for review"));
            }
            boolean hasSlides = input.slides() != null
                    && input.slides().values().stream().anyMatch(l -> l != null && !l.isEmpty());
            if (!hasSlides) {
                violations.add(new Violation("slides", "slidesRequired",
                        "at least one slide is required before this Creative can go up for review"));
            }
            return;
        }
        if (Creative.KIND_CLIP.equals(input.kind())) {
            if (input.caption() == null || input.caption().isBlank()) {
                violations.add(new Violation("caption", "captionRequired",
                        "caption is required before this Creative can go up for review"));
            }
            if (input.clipMedia() == null || input.clipMedia().isEmpty()) {
                violations.add(new Violation("clipMedia", "clipRequired",
                        "at least one clip is required before this Creative can go up for review"));
            }
            return;
        }
        // MOTION shares STILL's headline/photo READY rules (contract: "source=photo needs photoId
        // (READY-level, as STILL)") — except a photo is not required at all when the background is a clip.
        boolean requiresPhoto = !Creative.KIND_MOTION.equals(input.kind()) || isPhotoBackground(input.motion());
        if (requiresPhoto) {
            if (!input.photoPresent()) {
                violations.add(new Violation("photoId", "photoRequired", "a photo is required before this Creative can go up for review"));
            } else if (!input.photoUploaded()) {
                violations.add(new Violation("photoId", "photoUploaded", "the photo has not finished uploading"));
            } else if (input.photoBlocked()) {
                violations.add(new Violation("photoId", "photoBlocked", "the photo is blocked and cannot be used"));
            }
        }
        if (input.headline() == null || input.headline().isBlank()) {
            violations.add(new Violation("headline", "headlineRequired", "headline is required before this Creative can go up for review"));
        }
    }

    private static boolean isPhotoBackground(MotionInput motion) {
        if (motion == null) {
            return true;
        }
        String source = motion.backgroundSource();
        return source == null || "photo".equals(source);
    }

    /**
     * CLIP structural rules (COND-24 PR1), enforced on every write regardless of state — mirrors how
     * {@code photoId} is checked whenever present, before {@link #validateReadyState} adds the
     * READY-only "must be present at all" requirement on top. Every entry's placement key must be
     * {@code "default"} or a real registry placement, and every referenced media must resolve to a
     * VIDEO in this project that has finished uploading and is not blocked.
     */
    private void validateClipMedia(Input input, List<Violation> violations) {
        if (!Creative.KIND_CLIP.equals(input.kind()) || input.clipMedia() == null) {
            return;
        }
        for (ClipMediaEntry entry : input.clipMedia()) {
            String key = entry.placementKey();
            if (!"default".equals(key) && !registry.hasPlacement(key)) {
                violations.add(new Violation("clipMedia", "placement",
                        "unknown placement \"" + key + "\" in clipMedia, must be \"default\" or one of: "
                                + String.join(", ", registry.placements().keySet())));
                continue;
            }
            if (!entry.resolvable()) {
                violations.add(new Violation("clipMedia", "mediaNotFound",
                        "No media with id " + entry.mediaId() + " in this project"));
                continue;
            }
            if (!entry.isVideo()) {
                violations.add(new Violation("clipMedia", "mediaNotVideo",
                        "clipMedia[\"" + key + "\"] must be a VIDEO"));
                continue;
            }
            if (!entry.uploaded()) {
                violations.add(new Violation("clipMedia", "mediaUploaded",
                        "clipMedia[\"" + key + "\"] has not finished uploading"));
            }
            if (entry.blocked()) {
                violations.add(new Violation("clipMedia", "mediaBlocked",
                        "clipMedia[\"" + key + "\"] is blocked and cannot be used"));
            }
        }
    }

    /**
     * CAROUSEL structural rules, enforced on every write regardless of state: the {@code slides} map only
     * exists on a CAROUSEL (and a CAROUSEL carries none of the other kinds' media fields); every key is
     * {@code "default"} or 4x5 / 9x16 / 1x1; every list holds 1..10 slides (1..35 for 9x16); every slide
     * resolves to an uploaded, unblocked IMAGE whose aspect ratio is within 2% of its placement's; every
     * targeted placement has a list of its own or falls back to a {@code default} list whose images match
     * that placement's aspect; and {@code slideAltText} has no more entries than the longest list.
     */
    private void validateCarousel(Input input, List<Violation> violations) {
        Map<String, List<SlideEntry>> slides = input.slides() != null ? input.slides() : Map.of();
        if (!Creative.KIND_CAROUSEL.equals(input.kind())) {
            if (!slides.isEmpty()) {
                violations.add(new Violation("slides", "kindFields",
                        "slides only applies to a CAROUSEL creative, not " + input.kind()));
            }
            if (input.slideshow() != null) {
                violations.add(new Violation("slideshow", "kindFields",
                        "slideshow only applies to a CAROUSEL creative, not " + input.kind()));
            }
            return;
        }
        validateSlideshow(input, slides, violations);
        if (input.clipMedia() != null && !input.clipMedia().isEmpty()) {
            violations.add(new Violation("clipMedia", "kindFields", "clipMedia does not apply to a CAROUSEL creative"));
        }
        if (input.sequence() != null && !input.sequence().isEmpty() || input.sequenceKind() != null) {
            violations.add(new Violation("sequence", "kindFields", "sequence does not apply to a CAROUSEL creative"));
        }
        if (input.motion() != null) {
            violations.add(new Violation("motion", "kindFields", "motion does not apply to a CAROUSEL creative"));
        }
        if (input.photoId() != null) {
            violations.add(new Violation("photoId", "kindFields", "photoId does not apply to a CAROUSEL creative"));
        }

        int longest = 0;
        for (Map.Entry<String, List<SlideEntry>> list : slides.entrySet()) {
            String key = list.getKey();
            List<SlideEntry> entries = list.getValue() != null ? list.getValue() : List.of();
            longest = Math.max(longest, entries.size());
            if (!"default".equals(key) && !CAROUSEL_PLACEMENTS.contains(key)) {
                violations.add(new Violation("slides", "placement",
                        "unknown placement \"" + key + "\" in slides, must be \"default\" or one of: "
                                + String.join(", ", CAROUSEL_PLACEMENTS)));
                continue;
            }
            int max = "9x16".equals(key) ? CAROUSEL_MAX_SLIDES_9X16 : CAROUSEL_MAX_SLIDES;
            if (entries.isEmpty()) {
                violations.add(new Violation("slides", "slideCount",
                        "slides[\"" + key + "\"] must have at least 1 slide"));
            } else if (entries.size() > max) {
                violations.add(new Violation("slides", "slideCount",
                        "slides[\"" + key + "\"] has " + entries.size() + " slides, more than the " + max + " allowed"));
            }
            for (SlideEntry entry : entries) {
                String ref = "slides[\"" + key + "\"][" + entry.index() + "]";
                if (!entry.resolvable()) {
                    violations.add(new Violation("slides", "mediaNotFound",
                            "No media with id " + entry.mediaId() + " in this project"));
                    continue;
                }
                if (!entry.isImage()) {
                    violations.add(new Violation("slides", "mediaNotImage", ref + " must be an IMAGE"));
                    continue;
                }
                if (entry.gif()) {
                    violations.add(new Violation("slides", "mediaFormat", ref + " must be JPEG, PNG or WebP"));
                }
                if (!entry.uploaded()) {
                    violations.add(new Violation("slides", "mediaUploaded", ref + " has not finished uploading"));
                }
                if (entry.blocked()) {
                    violations.add(new Violation("slides", "mediaBlocked", ref + " is blocked and cannot be used"));
                }
                if (!"default".equals(key)) {
                    checkSlideAspect(ref, entry, key, key, violations);
                }
            }
        }

        // Every targeted placement needs slides of its own, or a default list that fits its aspect.
        List<SlideEntry> fallback = slides.get("default");
        if (input.placements() != null) {
            for (String placement : input.placements()) {
                if (slides.containsKey(placement)) {
                    continue;
                }
                if (fallback == null) {
                    violations.add(new Violation("slides", "placementSlides",
                            "placement \"" + placement + "\" has no slides: add slides[\"" + placement
                                    + "\"] or a slides[\"default\"] list"));
                    continue;
                }
                for (SlideEntry entry : fallback) {
                    if (entry.resolvable() && entry.isImage()) {
                        checkSlideAspect("slides[\"default\"][" + entry.index() + "]", entry, placement,
                                "placement \"" + placement + "\" falls back to it and", violations);
                    }
                }
            }
        }

        if (input.slideAltText() != null && input.slideAltText().size() > longest) {
            violations.add(new Violation("slideAltText", "slideAltText",
                    "slideAltText has " + input.slideAltText().size() + " entries but the longest slide list has only "
                            + longest));
        }
    }

    private void checkSlideAspect(String ref, SlideEntry entry, String placementKey, String subject,
                                  List<Violation> violations) {
        CreativeRegistry.PlacementInfo placement = registry.placements().get(placementKey);
        if (placement == null || entry.width() == null || entry.height() == null
                || entry.width() <= 0 || entry.height() <= 0) {
            return;
        }
        double target = (double) placement.width() / placement.height();
        double actual = (double) entry.width() / entry.height();
        if (Math.abs(actual - target) / target > CAROUSEL_ASPECT_TOLERANCE) {
            String needs = placement.width() + "x" + placement.height();
            String lead = subject.equals(placementKey) ? placementKey + " needs about " + needs
                    : subject + " needs about " + needs;
            violations.add(new Violation("slides", "slideAspect",
                    ref + " is " + entry.width() + "x" + entry.height() + " but " + lead));
        }
    }

    private static final Set<String> MOTION_PRESETS = Set.of("fade-up", "word-by-word", "accent-pop", "none");
    private static final Set<String> MOTION_BACKGROUND_SOURCES = Set.of("photo", "clip");
    private static final Set<String> MOTION_BACKGROUND_MOTIONS =
            Set.of("zoom-in", "zoom-out", "pan-left", "pan-right", "none");

    /**
     * MOTION structural rules (COND-24 PR2), enforced on every write regardless of state — mirrors
     * {@link #validateClipMedia}. {@code preset}/{@code background.source}/{@code background.motion}/
     * {@code audio.source} are plain strings, not OpenAPI enums (see {@code CreativeMotion}'s schema doc),
     * so an unknown value surfaces here as a friendly 422 field-path violation rather than a generic 400
     * from Jackson.
     */
    private void validateMotion(Input input, List<Violation> violations) {
        if (Creative.KIND_MOTION.equals(input.kind()) && input.sequenceKind() != null) {
            // A MOTION creative is one animated frame per placement: the render takes the first beat of a
            // sequence only, and a story's or carousel's own placements are not a video's. Refuse it up
            // front rather than render something other than what was asked for.
            violations.add(new Violation("sequenceKind", "motionNoSequence",
                    "a MOTION creative is a single animated frame and cannot be a " + input.sequenceKind()
                            + " (sequenceKind): drop the sequence, or make this a STILL creative"));
        }
        if (!Creative.KIND_MOTION.equals(input.kind()) || input.motion() == null) {
            return;
        }
        MotionInput m = input.motion();

        if (m.preset() != null && !MOTION_PRESETS.contains(m.preset())) {
            violations.add(new Violation("motion.preset", "motionPreset",
                    "motion.preset must be one of: " + String.join(", ", MOTION_PRESETS)));
        }
        if (m.durationSec() == null || m.durationSec() < 3 || m.durationSec() > 60) {
            violations.add(new Violation("motion.durationSec", "motionDuration",
                    "motion.durationSec must be between 3 and 60"));
        }

        String backgroundSource = m.backgroundSource() != null ? m.backgroundSource() : "photo";
        if (!MOTION_BACKGROUND_SOURCES.contains(backgroundSource)) {
            violations.add(new Violation("motion.background.source", "motionBackgroundSource",
                    "motion.background.source must be one of: " + String.join(", ", MOTION_BACKGROUND_SOURCES)));
        }
        if ("photo".equals(backgroundSource) && m.backgroundMotion() != null
                && !MOTION_BACKGROUND_MOTIONS.contains(m.backgroundMotion())) {
            violations.add(new Violation("motion.background.motion", "motionBackgroundMotion",
                    "motion.background.motion must be one of: " + String.join(", ", MOTION_BACKGROUND_MOTIONS)));
        }
        if ("clip".equals(backgroundSource)) {
            if (m.backgroundClipMediaId() == null || m.backgroundClipMediaId().isBlank()) {
                violations.add(new Violation("motion.background.clipMediaId", "clipRequired",
                        "motion.background.clipMediaId is required when background.source is \"clip\""));
            } else if (!m.backgroundClipResolvable()) {
                violations.add(new Violation("motion.background.clipMediaId", "mediaNotFound",
                        "No media with id " + m.backgroundClipMediaId() + " in this project"));
            } else {
                if (!m.backgroundClipIsVideo()) {
                    violations.add(new Violation("motion.background.clipMediaId", "mediaNotVideo",
                            "motion.background.clipMediaId must be a VIDEO"));
                }
                if (!m.backgroundClipUploaded()) {
                    violations.add(new Violation("motion.background.clipMediaId", "mediaUploaded",
                            "motion.background.clipMediaId has not finished uploading"));
                }
                if (m.backgroundClipBlocked()) {
                    violations.add(new Violation("motion.background.clipMediaId", "mediaBlocked",
                            "motion.background.clipMediaId is blocked and cannot be used"));
                }
                if (m.clipStartSec() != null && m.backgroundClipDurationSeconds() != null
                        && m.clipStartSec() >= m.backgroundClipDurationSeconds()) {
                    violations.add(new Violation("motion.background.clipStartSec", "clipStartBeyondDuration",
                            "motion.background.clipStartSec must be less than the clip's own duration"));
                }
            }
        }
        if (m.clipStartSec() != null && m.clipStartSec() < 0) {
            violations.add(new Violation("motion.background.clipStartSec", "bounds",
                    "motion.background.clipStartSec must be >= 0"));
        }

        validateAudio(Creative.KIND_MOTION, new AudioFacts(m.audioSource(), m.audioTrackId(),
                m.audioTrackResolvable(), m.audioTrackIsAudio(), m.audioTrackUploaded(), m.audioTrackBlocked(),
                m.volume(), m.fadeOutSec(), "clip".equals(backgroundSource), m.backgroundClipHasAudio()), violations);
    }

    /** Sources each kind's {@code audio.source} may take (a MOTION's default depends on its background). */
    private static final List<String> MOTION_AUDIO_SOURCE_LIST = List.of("clip", "none", "track");
    private static final List<String> CAROUSEL_AUDIO_SOURCE_LIST = List.of("none", "track");
    private static final List<String> CLIP_AUDIO_SOURCE_LIST = List.of("clip", "mix", "none", "track");

    /** The audio facts {@link #validateAudio} judges, whichever kind they came from. */
    private record AudioFacts(String source, String trackId, boolean trackResolvable, boolean trackIsAudio,
                              boolean trackUploaded, boolean trackBlocked, Double volume, Double fadeOutSec,
                              boolean clipBackground, Boolean clipHasAudio) {
    }

    /**
     * CAROUSEL and CLIP audio (MOTION's runs inside {@link #validateMotion}, through the same
     * {@link #validateAudio}); a STILL takes none. Skipped when {@code audio} is absent, which is the
     * "no sound / the clip's own sound" default.
     */
    private void validateAudioFields(Input input, List<Violation> violations) {
        String kind = input.kind();
        AudioInput a = input.audio();
        if (a == null) {
            return;
        }
        if (Creative.KIND_CAROUSEL.equals(kind) || Creative.KIND_CLIP.equals(kind)) {
            validateAudio(kind, new AudioFacts(a.source(), a.trackId(), a.trackResolvable(), a.trackIsAudio(),
                    a.trackUploaded(), a.trackBlocked(), a.volume(), a.fadeOutSec(), true, a.clipHasAudio()), violations);
        } else if (!Creative.KIND_MOTION.equals(kind) && a.source() != null && !"none".equals(a.source())) {
            violations.add(new Violation("audio", "kindFields",
                    "audio does not apply to a " + kind + " creative: it can have sound on a MOTION, CAROUSEL or CLIP"));
        }
    }

    /**
     * The audio rules shared by MOTION, CAROUSEL and CLIP: the source is one the kind takes, a track
     * (source {@code track} or, for a CLIP, {@code mix}) is an uploaded, unblocked AUDIO of this project,
     * {@code clip}/{@code mix} need a clip with sound, and volume and fade are in range.
     */
    private void validateAudio(String kind, AudioFacts a, List<Violation> violations) {
        List<String> allowed = switch (kind) {
            case Creative.KIND_CAROUSEL -> CAROUSEL_AUDIO_SOURCE_LIST;
            case Creative.KIND_CLIP -> CLIP_AUDIO_SOURCE_LIST;
            default -> MOTION_AUDIO_SOURCE_LIST;
        };
        String source = a.source();
        if (source != null && !allowed.contains(source)) {
            violations.add(new Violation("audio.source", "audioSource",
                    "audio.source must be one of: " + String.join(", ", allowed)));
        }
        boolean needsTrack = "track".equals(source) || ("mix".equals(source) && Creative.KIND_CLIP.equals(kind));
        if (needsTrack && allowed.contains(source)) {
            if (a.trackId() == null || a.trackId().isBlank()) {
                violations.add(new Violation("audio.trackId", "trackRequired",
                        "audio.trackId is required when audio.source is \"" + source + "\""));
            } else if (!a.trackResolvable()) {
                violations.add(new Violation("audio.trackId", "mediaNotFound",
                        "No media with id " + a.trackId() + " in this project"));
            } else {
                if (!a.trackIsAudio()) {
                    violations.add(new Violation("audio.trackId", "mediaNotAudio", "audio.trackId must be an AUDIO"));
                }
                if (!a.trackUploaded()) {
                    violations.add(new Violation("audio.trackId", "mediaUploaded",
                            "audio.trackId has not finished uploading"));
                }
                if (a.trackBlocked()) {
                    violations.add(new Violation("audio.trackId", "mediaBlocked",
                            "audio.trackId is blocked and cannot be used"));
                }
            }
        }
        if ("clip".equals(source) && Creative.KIND_MOTION.equals(kind)) {
            if (!a.clipBackground()) {
                violations.add(new Violation("audio.source", "audioClipNeedsClipBackground",
                        "audio.source \"clip\" needs a clip background"));
            } else if (Boolean.FALSE.equals(a.clipHasAudio())) {
                violations.add(new Violation("audio.source", "audioClipHasNoAudio",
                        "the background clip has no audio track"));
            }
        }
        if ("mix".equals(source) && Creative.KIND_CLIP.equals(kind) && Boolean.FALSE.equals(a.clipHasAudio())) {
            violations.add(new Violation("audio.source", "audioClipHasNoAudio",
                    "audio.source \"mix\" needs a clip with sound, and a chosen clip has no audio track:"
                            + " use \"track\" to give it music"));
        }
        if (a.volume() != null && (a.volume() < 0 || a.volume() > 1)) {
            violations.add(new Violation("audio.volume", "bounds", "audio.volume must be between 0 and 1"));
        }
        if (a.fadeOutSec() != null && (a.fadeOutSec() < 0 || a.fadeOutSec() > 5)) {
            violations.add(new Violation("audio.fadeOutSec", "bounds", "audio.fadeOutSec must be between 0 and 5"));
        }
    }

    /**
     * A CAROUSEL's slideshow: one duration per slide of the 9x16 list (checked once that list exists), each
     * 1..10 s; and, from READY on, a CAROUSEL with a track needs a 9x16 or default list to make its video from.
     */
    private void validateSlideshow(Input input, Map<String, List<SlideEntry>> slides, List<Violation> violations) {
        SlideshowInput show = input.slideshow();
        if (show != null && show.slideDurations() != null) {
            List<Double> durations = show.slideDurations();
            for (int i = 0; i < durations.size(); i++) {
                Double d = durations.get(i);
                if (d == null || d < SLIDE_DURATION_MIN || d > SLIDE_DURATION_MAX) {
                    violations.add(new Violation("slideshow.slideDurations", "bounds",
                            "slideshow.slideDurations[" + i + "] must be between " + (int) SLIDE_DURATION_MIN
                                    + " and " + (int) SLIDE_DURATION_MAX + " seconds"));
                }
            }
            // Only a carousel with a track renders a slideshow; stored durations of one without are ignored
            // (the spec builder falls back to defaults on a mismatch), so they must not block slide edits.
            boolean hasTrack = input.audio() != null && "track".equals(input.audio().source());
            if (hasTrack && show.slideCount() > 0 && durations.size() != show.slideCount()) {
                violations.add(new Violation("slideshow.slideDurations", "slideshowDurations",
                        "slideshow.slideDurations has " + durations.size() + " entries but the 9x16 slide list has "
                                + show.slideCount() + ": give one duration per slide"));
            }
        }
        AudioInput audio = input.audio();
        if (audio != null && "track".equals(audio.source()) && Creative.STATE_READY.equals(input.state())
                && !slides.isEmpty() && !slides.containsKey("9x16") && !slides.containsKey("default")) {
            violations.add(new Violation("slides", "slideshowSlides",
                    "a carousel with a music track is rendered as a 9x16 video: add slides[\"9x16\"] or a"
                            + " slides[\"default\"] list"));
        }
    }

    static final double SLIDE_DURATION_MIN = 1.0;
    static final double SLIDE_DURATION_MAX = 10.0;

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /**
     * Compiles a Brand Kit copy-rule regex, mapping its {@code flags} string (any combination of
     * {@code i}/{@code m}/{@code s}/{@code u}) onto {@link Pattern} flags — shared by {@link BrandKitService}
     * (validating a kit write) and this validator (applying the rule), so the two can never disagree about
     * what compiles.
     *
     * <p>Rules are written once and run in two engines: the browser checks copy live with JavaScript, the
     * backend enforces it with Java. JavaScript spells a code point outside the BMP as a backslash, "u" and
     * the hex in braces (with the {@code u} flag), which Java rejects; Java's spelling is a backslash, "x"
     * and the hex in braces. Those escapes are translated here so an emoji rule written for the browser
     * compiles on the server too.
     *
     * @throws PatternSyntaxException if {@code pattern} is not a valid regex
     */
    public static Pattern compilePattern(String pattern, String flags) {
        int javaFlags = 0;
        if (flags != null) {
            if (flags.indexOf('i') >= 0) {
                javaFlags |= Pattern.CASE_INSENSITIVE;
                if (flags.indexOf('u') >= 0) {
                    javaFlags |= Pattern.UNICODE_CASE;
                }
            }
            if (flags.indexOf('m') >= 0) {
                javaFlags |= Pattern.MULTILINE;
            }
            if (flags.indexOf('s') >= 0) {
                javaFlags |= Pattern.DOTALL;
            }
        }
        return Pattern.compile(JS_CODE_POINT_ESCAPE.matcher(pattern).replaceAll("$1\\\\x{$2}"), javaFlags);
    }

    /** The JavaScript code-point escape (backslash, "u", hex in braces) when not itself escaped. */
    private static final Pattern JS_CODE_POINT_ESCAPE =
            Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u\\{([0-9A-Fa-f]{1,6})\\}");
}
