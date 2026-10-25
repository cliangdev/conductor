package com.conductor.creative;

import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.generated.v2.model.CreativeRenderSpecAudio;
import com.conductor.generated.v2.model.CreativeRenderSpecBeat;
import com.conductor.generated.v2.model.CreativeRenderSpecBrand;
import com.conductor.generated.v2.model.CreativeRenderSpecSlide;
import com.conductor.generated.v2.model.CreativeRenderSpecSlideshow;
import com.conductor.generated.v2.model.CreativeSlideshow;
import com.conductor.generated.v2.model.CreativeRenderSpecCreative;
import com.conductor.generated.v2.model.CreativeRenderSpecLogos;
import com.conductor.generated.v2.model.CreativeRenderSpecMotion;
import com.conductor.generated.v2.model.CreativeRenderSpecMotionBackground;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the render input ({@link CreativeRenderSpec}) a local render job renders from — from a plain value:
 * the Creative's fields (an entity that may never have been saved) plus its already-resolved media
 * ({@link MediaFacts}). It touches no repository, so the same code serves a persisted Creative's render
 * ({@link CreativeRenderService}) and a draft that exists only in a request ({@link CreativeService#buildDraftSpec}).
 *
 * <p>The only side effect is read-only URL signing (media, kit logos). A {@link MediaFacts#isLocal local}
 * media file has no object to sign: its URL is the literal {@code local:<key>}, with a {@code null} focal.
 */
@Component
public class CreativeSpecBuilder {

    static final String DEFAULT_CAROUSEL_RATIO = "4x5";
    /** Contract: signed URLs in a render spec must stay valid at least 30 minutes. */
    private static final int SPEC_URL_EXPIRY_MINUTES = 45;

    private final CreativeRegistry registry;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public CreativeSpecBuilder(CreativeRegistry registry, StorageService storageService, ObjectMapper objectMapper) {
        this.registry = registry;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** A MOTION creative's {@code motion}/{@code audio}, plus the media the spec needs URLs for. */
    public record MotionMedia(CreativeMotion motion, CreativeAudio audio, MediaFacts backgroundClip, MediaFacts audioTrack) {
    }

    /**
     * @param beatPhotos each sequence beat's own photo by the id the beat names; a beat with no entry (no
     *                   photo of its own, or one that did not resolve) inherits {@code mainPhoto}
     * @param motionMedia null for a non-MOTION creative
     */
    public CreativeRenderSpec build(String renderId, boolean previewOnly, Creative creative, BrandKit kit,
                                    MediaFacts mainPhoto, Map<String, MediaFacts> beatPhotos, List<String> placements,
                                    MotionMedia motionMedia) {
        CreativeRenderSpecBrand brand = new CreativeRenderSpecBrand(toStringMap(kit.getTokens()),
                toStringList(kit.getEnabledPlacements()))
                .fontFamily(kit.getFontFamily())
                .fontUrl(kit.getFontUrl())
                .ctaClaim(kit.getCtaClaim())
                .logos(new CreativeRenderSpecLogos()
                        .mark(signedOrNull(kit.getMarkGcsPath()))
                        .wordmarkDark(signedOrNull(kit.getWordmarkDarkGcsPath()))
                        .wordmarkLight(signedOrNull(kit.getWordmarkLightGcsPath()))
                        .badge(signedOrNull(kit.getBadgeGcsPath())));

        List<CreativeRenderSpecBeat> beats = new ArrayList<>();
        for (SequenceBeat beat : toSequenceBeats(creative.getSequence())) {
            MediaFacts beatPhoto = beat.getPhotoId() != null
                    ? beatPhotos.getOrDefault(beat.getPhotoId(), mainPhoto)
                    : mainPhoto;
            beats.add(new CreativeRenderSpecBeat()
                    .headline(beat.getHeadline())
                    .body(beat.getBody())
                    .cta(beat.getCta())
                    .photoUrl(mediaUrl(beatPhoto))
                    .focal(focal(beatPhoto)));
        }

        CreativeRenderSpecCreative creativeSpec = new CreativeRenderSpecCreative(creative.getLayout(),
                CreativeTheme.fromValue(creative.getTheme()), toStringList(creative.getPlacements()),
                toTypeOverrides(creative.getTypeOverrides()), focal(mainPhoto))
                .headline(creative.getHeadline())
                .body(creative.getBody())
                .caption(creative.getCaption())
                .focalOverride(creative.getFocalOverride() != null ? toStringMap(creative.getFocalOverride()) : null)
                .sequenceKind(creative.getSequenceKind() != null ? SequenceKind.fromValue(creative.getSequenceKind()) : null)
                .sequence(beats)
                .photoUrl(mediaUrl(mainPhoto))
                .lockup(CreativeLockup.fromValue(creative.getLockup()))
                .layoutOverrides(toLayoutOverrides(creative.getLayoutOverrides()))
                .kind(CreativeKind.fromValue(creative.getKind()));

        if (motionMedia != null && motionMedia.motion() != null) {
            creativeSpec.motion(buildMotionSpec(motionMedia))
                    .audio(buildAudioSpec(motionMedia))
                    .clipHasAudio(motionMedia.backgroundClip() != null ? motionMedia.backgroundClip().getHasAudio() : null);
        }

        return new CreativeRenderSpec(renderId, previewOnly, creativeSpec, brand, new ArrayList<Object>(placements));
    }

    // ── CAROUSEL slideshow and CLIP sound (music on CAROUSEL and CLIP) ───────────────────────────

    /** The fade-in every normalised mix starts with, and the loudness the local job normalises to. */
    static final BigDecimal FADE_IN_SEC = new BigDecimal("0.5");
    static final BigDecimal TARGET_LUFS = new BigDecimal("-14");
    static final BigDecimal CROSSFADE_SEC = new BigDecimal("0.5");
    static final double DEFAULT_FIRST_SLIDE_SEC = 3.0;
    static final double DEFAULT_SLIDE_SEC = 2.5;
    static final int SLIDESHOW_WIDTH = 1080;
    static final int SLIDESHOW_HEIGHT = 1920;

    /** One duration per slide: the requested list when it fits, else 3.0 s for the first slide and 2.5 s for the rest. */
    static List<BigDecimal> resolveSlideDurations(CreativeSlideshow slideshow, int slideCount) {
        List<BigDecimal> requested = slideshow != null ? slideshow.getSlideDurations() : null;
        if (requested != null && requested.size() == slideCount) {
            return List.copyOf(requested);
        }
        List<BigDecimal> durations = new ArrayList<>();
        for (int i = 0; i < slideCount; i++) {
            durations.add(BigDecimal.valueOf(i == 0 ? DEFAULT_FIRST_SLIDE_SEC : DEFAULT_SLIDE_SEC));
        }
        return durations;
    }

    /**
     * The local-render spec of a CAROUSEL with a music track: the 9x16 slide list, the resolved slideshow
     * (durations, zoom, 0.5 s crossfade), the audio block, and one 1080x1920 placement. Everything else is
     * the plain spec. {@code slides} are the 9x16 list's media (the {@code default} list when there is no
     * 9x16 one), in order; a media that did not resolve is skipped.
     */
    public CreativeRenderSpec buildCarousel(String renderId, boolean previewOnly, Creative creative, BrandKit kit,
                                            List<MediaFacts> slides, MediaFacts track, CreativeAudio audio,
                                            CreativeSlideshow slideshow) {
        CreativeRenderSpec spec = build(renderId, previewOnly, creative, kit, null, Map.of(),
                toStringList(creative.getPlacements()), null);
        List<CreativeRenderSpecSlide> specSlides = new ArrayList<>();
        for (MediaFacts slide : slides) {
            String url = mediaUrl(slide);
            if (url != null) {
                specSlides.add(new CreativeRenderSpecSlide(url));
            }
        }
        boolean zoom = slideshow == null || slideshow.getZoom() == null || slideshow.getZoom();
        spec.getCreative()
                .slides(specSlides)
                .slideshow(new CreativeRenderSpecSlideshow(resolveSlideDurations(slideshow, specSlides.size()), zoom,
                        CROSSFADE_SEC))
                .audio(buildNormalisedAudio(audio, track));
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("key", "9x16");
        placement.put("width", SLIDESHOW_WIDTH);
        placement.put("height", SLIDESHOW_HEIGHT);
        spec.setPlacements(new ArrayList<>(List.of(placement)));
        return spec;
    }

    /** One CLIP frame the local job renders: its placement and the clip it takes sound from. */
    public record ClipPlacement(String key, int width, int height, MediaFacts clip) {
    }

    /**
     * The local-render spec of a CLIP whose sound is replaced, mixed or muted: the audio block and one
     * object per placement frame ({@code clipUrl}, {@code durationSec}, {@code clipHasAudio}). The clip
     * is used from its start — a CLIP has no start offset of its own.
     */
    public CreativeRenderSpec buildClip(String renderId, Creative creative, BrandKit kit, List<ClipPlacement> clips,
                                        MediaFacts track, CreativeAudio audio) {
        CreativeRenderSpec spec = build(renderId, false, creative, kit, null, Map.of(),
                toStringList(creative.getPlacements()), null);
        spec.getCreative().audio(buildNormalisedAudio(audio, track));
        List<Object> placements = new ArrayList<>();
        for (ClipPlacement clip : clips) {
            Map<String, Object> placement = new LinkedHashMap<>();
            placement.put("key", clip.key());
            placement.put("width", clip.width());
            placement.put("height", clip.height());
            placement.put("clipUrl", mediaUrl(clip.clip()));
            placement.put("durationSec", clip.clip().getDurationSeconds());
            placement.put("clipHasAudio", Boolean.TRUE.equals(clip.clip().getHasAudio()));
            placements.add(placement);
        }
        spec.setPlacements(placements);
        return spec;
    }

    /** The CAROUSEL/CLIP audio block: {@code trackUrl} for a track or mix, plus the fixed fade-in and target loudness. */
    private CreativeRenderSpecAudio buildNormalisedAudio(CreativeAudio audio, MediaFacts track) {
        boolean usesTrack = "track".equals(audio.getSource()) || "mix".equals(audio.getSource());
        return new CreativeRenderSpecAudio()
                .source(audio.getSource())
                .trackUrl(usesTrack && track != null ? mediaUrl(track) : null)
                .volume(audio.getVolume())
                .fadeOutSec(audio.getFadeOutSec())
                .fadeInSec(FADE_IN_SEC)
                .targetLufs(TARGET_LUFS);
    }

    /** kit enabled ∪ creative opt-ins ∩ registry — except a sequence Creative, which renders only its own shape. */
    public List<String> resolvePlacements(BrandKit kit, Creative creative) {
        if ("story".equals(creative.getSequenceKind())) {
            return List.of("story");
        }
        if ("carousel".equals(creative.getSequenceKind())) {
            // nexus's default: a carousel with no ratio set is a 4:5 Instagram carousel.
            return List.of(creative.getCarouselRatio() != null ? creative.getCarouselRatio() : DEFAULT_CAROUSEL_RATIO);
        }
        Set<String> union = new LinkedHashSet<>(toStringList(kit.getEnabledPlacements()));
        union.addAll(toStringList(creative.getPlacements()));
        union.retainAll(registry.placements().keySet());
        return List.copyOf(union);
    }

    /** A signed GET for an uploaded library file, the literal {@code local:<key>} for a local one, else null. */
    private String mediaUrl(MediaFacts media) {
        if (media == null) {
            return null;
        }
        if (media.isLocal()) {
            return media.getRef();
        }
        return media.isUploaded() ? signedOrNull(media.getGcsPath()) : null;
    }

    /** A library file's focal point (empty when it has none, or when there is no file); null for a local file. */
    private Map<String, String> focal(MediaFacts media) {
        if (media != null && media.isLocal()) {
            return null;
        }
        return toStringMap(media != null ? media.getFocal() : null);
    }

    private String signedOrNull(String gcsPath) {
        return gcsPath != null ? storageService.generateSignedUrl(gcsPath, SPEC_URL_EXPIRY_MINUTES) : null;
    }

    /** Contract: "motion.background.clipUrl (VIDEO media)" — a signed GET, present only when the resolved
     *  background clip has actually finished uploading (a local clip: its {@code local:<key>}). */
    private CreativeRenderSpecMotion buildMotionSpec(MotionMedia motionMedia) {
        CreativeMotion motion = motionMedia.motion();
        CreativeMotionBackground background = motion.getBackground();
        MediaFacts clip = motionMedia.backgroundClip();
        CreativeRenderSpecMotionBackground specBackground = new CreativeRenderSpecMotionBackground()
                .source(background != null ? background.getSource() : null)
                .motion(background != null ? background.getMotion() : null)
                .clipUrl(clip != null ? mediaUrl(clip) : null)
                .clipStartSec(background != null ? background.getClipStartSec() : null);
        return new CreativeRenderSpecMotion()
                .preset(motion.getPreset())
                .durationSec(motion.getDurationSec())
                .background(specBackground)
                .endCard(motion.getEndCard());
    }

    /** Contract: "audio.trackUrl (AUDIO media)" — a signed GET, present only for a track that finished uploading. */
    private CreativeRenderSpecAudio buildAudioSpec(MotionMedia motionMedia) {
        CreativeAudio audio = motionMedia.audio();
        if (audio == null) {
            return null;
        }
        MediaFacts track = motionMedia.audioTrack();
        return new CreativeRenderSpecAudio()
                .source(audio.getSource())
                .trackUrl(track != null ? mediaUrl(track) : null)
                .volume(audio.getVolume())
                .fadeOutSec(audio.getFadeOutSec());
    }

    // ── JSON <-> typed helpers ───────────────────────────────────────────────────────────────────

    private List<String> toStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<String>>() {
        });
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private Map<String, List<BigDecimal>> toTypeOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, new TypeReference<Map<String, List<BigDecimal>>>() {
        }) : Map.of();
    }

    private CreativeLayoutOverrides toLayoutOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, CreativeLayoutOverrides.class) : null;
    }

    private List<SequenceBeat> toSequenceBeats(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<SequenceBeat>>() {
        });
    }
}
