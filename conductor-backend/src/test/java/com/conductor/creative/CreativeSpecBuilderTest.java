package com.conductor.creative;

import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CreativeSpecBuilder} from plain values — an unsaved {@link Creative} and {@link MediaFacts}, no
 * repository, no Spring context. The persisted path's output is pinned separately by the golden-file tests in
 * {@code CreativeRenderServiceIntegrationTest}.
 */
class CreativeSpecBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CreativeSpecBuilder builder;
    private BrandKit kit;

    @BeforeEach
    void setUp() {
        StorageService storage = mock(StorageService.class);
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed://" + i.getArgument(0));
        builder = new CreativeSpecBuilder(new CreativeRegistry(objectMapper), storage, objectMapper);

        kit = new BrandKit();
        kit.setTokens(objectMapper.valueToTree(Map.of("accent", "#3B82F6")));
        kit.setEnabledPlacements(objectMapper.valueToTree(List.of("9x16", "4x5", "1x1")));
        kit.setMarkGcsPath("kit/mark.png");
    }

    @Test
    void aLibraryPhotoIsASignedUrlWithItsFocalPoint() {
        CreativeRenderSpec spec = builder.build("r-1", false, creative(), kit, libraryPhoto("p1"), Map.of(),
                List.of("4x5"), null);

        assertThat(spec.getRenderId()).isEqualTo("r-1");
        assertThat(spec.getPreviewOnly()).isFalse();
        assertThat(spec.getCreative().getPhotoUrl()).isEqualTo("signed://photos/p1.jpg");
        assertThat(spec.getCreative().getFocal()).containsEntry("x", "0.3");
        assertThat(spec.getBrand().getLogos().getMark()).isEqualTo("signed://kit/mark.png");
        assertThat(spec.getPlacements()).containsExactly("4x5");
    }

    @Test
    void aLocalPhotoIsTheLiteralLocalReferenceWithANullFocal() {
        MediaFacts hero = MediaFacts.local("hero", CreativePhoto.MEDIA_KIND_IMAGE, null, null, 1200, 1600);

        CreativeRenderSpec spec = builder.build("draft", true, creative(), kit, hero, Map.of(), List.of(), null);

        assertThat(spec.getPreviewOnly()).isTrue();
        assertThat(spec.getCreative().getPhotoUrl()).isEqualTo("local:hero");
        assertThat(spec.getCreative().getFocal()).isNull();
    }

    @Test
    void aBeatWithItsOwnPhotoUsesItAndOneWithoutInheritsTheCreativesPhoto() {
        Creative story = creative();
        story.setSequenceKind("story");
        story.setSequence(objectMapper.valueToTree(List.of(
                Map.of("headline", "One"),
                Map.of("headline", "Two", "photoId", "local:b2"),
                Map.of("headline", "Three", "photoId", "ghost"))));
        MediaFacts beat = MediaFacts.local("b2", CreativePhoto.MEDIA_KIND_IMAGE, null, null, null, null);

        CreativeRenderSpec spec = builder.build("draft", true, story, kit, libraryPhoto("p1"),
                Map.of("local:b2", beat), List.of("story"), null);

        assertThat(spec.getCreative().getSequence()).extracting("photoUrl")
                .containsExactly("signed://photos/p1.jpg", "local:b2", "signed://photos/p1.jpg");
        assertThat(spec.getCreative().getSequence().get(1).getFocal()).isNull();
        assertThat(spec.getCreative().getSequence().get(2).getFocal()).containsEntry("x", "0.3");
    }

    @Test
    void aMotionCreativeCarriesLocalClipAndTrackUrlsAndTheClipsAudioFlag() {
        Creative motion = creative();
        motion.setKind(Creative.KIND_MOTION);
        MediaFacts clip = MediaFacts.local("clip", CreativePhoto.MEDIA_KIND_VIDEO, new BigDecimal("10"), true, null, null);
        MediaFacts track = MediaFacts.local("song", CreativePhoto.MEDIA_KIND_AUDIO, new BigDecimal("30"), null, null, null);
        CreativeSpecBuilder.MotionMedia media = new CreativeSpecBuilder.MotionMedia(
                new CreativeMotion().preset("fade-up").durationSec(BigDecimal.valueOf(8)).endCard(true)
                        .background(new CreativeMotionBackground().source("clip").motion("none").clipMediaId("local:clip")),
                new CreativeAudio().source("track").trackId("local:song").volume(new BigDecimal("0.8")),
                clip, track);

        CreativeRenderSpec spec = builder.build("draft", true, motion, kit, null, Map.of(), List.of(), media);

        assertThat(spec.getCreative().getMotion().getBackground().getClipUrl()).isEqualTo("local:clip");
        assertThat(spec.getCreative().getAudio().getTrackUrl()).isEqualTo("local:song");
        assertThat(spec.getCreative().getClipHasAudio()).isTrue();
    }

    @Test
    void placementsAreTheKitUnionOptInsIntersectedWithTheRegistryExceptForASequence() {
        Creative single = creative();
        single.setPlacements(objectMapper.valueToTree(List.of("story", "nope")));
        assertThat(builder.resolvePlacements(kit, single)).containsExactlyInAnyOrder("9x16", "4x5", "1x1", "story");

        Creative story = creative();
        story.setSequenceKind("story");
        assertThat(builder.resolvePlacements(kit, story)).containsExactly("story");

        Creative carousel = creative();
        carousel.setSequenceKind("carousel");
        assertThat(builder.resolvePlacements(kit, carousel)).containsExactly("4x5");
        carousel.setCarouselRatio("1x1");
        assertThat(builder.resolvePlacements(kit, carousel)).containsExactly("1x1");
    }

    @Test
    void aCarouselWithATrackGetsSlidesDurationsAndTheNormalisedAudioBlock() {
        Creative carousel = creative();
        carousel.setKind(Creative.KIND_CAROUSEL);
        carousel.setPlacements(objectMapper.valueToTree(List.of("9x16")));
        List<MediaFacts> slides = List.of(libraryPhoto("a"), libraryPhoto("b"), MediaFacts.local("c", CreativePhoto.MEDIA_KIND_IMAGE, null, null, 1080, 1920));
        MediaFacts track = MediaFacts.local("song", CreativePhoto.MEDIA_KIND_AUDIO, new BigDecimal("30"), null, null, null);

        CreativeRenderSpec spec = builder.buildCarousel("r-1", false, carousel, kit, slides, track,
                new CreativeAudio().source("track").trackId("t").volume(new BigDecimal("0.9")).fadeOutSec(BigDecimal.ONE),
                null);

        assertThat(spec.getCreative().getSlides()).extracting("url")
                .containsExactly("signed://photos/a.jpg", "signed://photos/b.jpg", "local:c");
        // No durations given: 3.0 s for the first slide, 2.5 s for the rest; zoom on; 0.5 s crossfade.
        assertThat(spec.getCreative().getSlideshow().getSlideDurations()).extracting(BigDecimal::doubleValue)
                .containsExactly(3.0, 2.5, 2.5);
        assertThat(spec.getCreative().getSlideshow().getZoom()).isTrue();
        assertThat(spec.getCreative().getSlideshow().getCrossfadeSec()).isEqualByComparingTo("0.5");
        assertThat(spec.getCreative().getAudio().getTrackUrl()).isEqualTo("local:song");
        assertThat(spec.getCreative().getAudio().getVolume()).isEqualByComparingTo("0.9");
        assertThat(spec.getCreative().getAudio().getFadeInSec()).isEqualByComparingTo("0.5");
        assertThat(spec.getCreative().getAudio().getTargetLufs()).isEqualByComparingTo("-14");
        assertThat(spec.getPlacements()).containsExactly(Map.of("key", "9x16", "width", 1080, "height", 1920));
    }

    @Test
    void slideDurationsAreTakenAsGivenOnlyWhenThereIsOneForEverySlideAndZoomCanBeTurnedOff() {
        com.conductor.generated.v2.model.CreativeSlideshow show = new com.conductor.generated.v2.model.CreativeSlideshow()
                .slideDurations(List.of(new BigDecimal("4"), new BigDecimal("1"))).zoom(false);
        assertThat(CreativeSpecBuilder.resolveSlideDurations(show, 2)).extracting(BigDecimal::doubleValue).containsExactly(4.0, 1.0);
        assertThat(CreativeSpecBuilder.resolveSlideDurations(show, 3)).extracting(BigDecimal::doubleValue).containsExactly(3.0, 2.5, 2.5);
        assertThat(CreativeSpecBuilder.resolveSlideDurations(null, 1)).extracting(BigDecimal::doubleValue).containsExactly(3.0);

        Creative carousel = creative();
        carousel.setKind(Creative.KIND_CAROUSEL);
        CreativeRenderSpec spec = builder.buildCarousel("r-1", false, carousel, kit, List.of(libraryPhoto("a")), null,
                new CreativeAudio().source("track"), show);
        assertThat(spec.getCreative().getSlideshow().getZoom()).isFalse();
    }

    @Test
    void aClipWithNewSoundGetsOnePlacementObjectPerFrameAndTheAudioBlock() {
        Creative clipCreative = creative();
        clipCreative.setKind(Creative.KIND_CLIP);
        MediaFacts clip = MediaFacts.local("clip", CreativePhoto.MEDIA_KIND_VIDEO, new BigDecimal("12.5"), true, 1080, 1920);
        MediaFacts track = MediaFacts.local("song", CreativePhoto.MEDIA_KIND_AUDIO, new BigDecimal("30"), null, null, null);

        CreativeRenderSpec spec = builder.buildClip("r-1", clipCreative, kit,
                List.of(new CreativeSpecBuilder.ClipPlacement("9x16", 1080, 1920, clip)), track,
                new CreativeAudio().source("mix").trackId("t").volume(new BigDecimal("0.3")).fadeOutSec(BigDecimal.ONE));

        assertThat(spec.getCreative().getKind().getValue()).isEqualTo("CLIP");
        assertThat(spec.getCreative().getAudio().getSource()).isEqualTo("mix");
        assertThat(spec.getCreative().getAudio().getTrackUrl()).isEqualTo("local:song");
        assertThat(spec.getPlacements()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> placement = (Map<String, Object>) spec.getPlacements().get(0);
        assertThat(placement).containsEntry("key", "9x16").containsEntry("width", 1080).containsEntry("height", 1920)
                .containsEntry("clipUrl", "local:clip").containsEntry("clipHasAudio", true);
        assertThat(((BigDecimal) placement.get("durationSec"))).isEqualByComparingTo("12.5");
    }

    @Test
    void anUnknownClipDurationIsOmittedUnknownAudioCountsAsPresentAndTheRealSizeWins() {
        Creative clipCreative = creative();
        clipCreative.setKind(Creative.KIND_CLIP);
        MediaFacts clip = MediaFacts.local("clip", CreativePhoto.MEDIA_KIND_VIDEO, null, null, 720, 1280);
        CreativeRenderSpec spec = builder.buildClip("r-1", clipCreative, kit,
                List.of(new CreativeSpecBuilder.ClipPlacement("9x16", 1080, 1920, clip)), null,
                new CreativeAudio().source("mix").trackId("t").volume(new BigDecimal("0.3")).fadeOutSec(BigDecimal.ONE));
        @SuppressWarnings("unchecked")
        Map<String, Object> placement = (Map<String, Object>) spec.getPlacements().get(0);
        assertThat(placement).doesNotContainKey("durationSec").containsEntry("clipHasAudio", true)
                .containsEntry("width", 720).containsEntry("height", 1280);

        MediaFacts silent = MediaFacts.local("clip", CreativePhoto.MEDIA_KIND_VIDEO, new BigDecimal("5"), false, null, null);
        CreativeRenderSpec silentSpec = builder.buildClip("r-1", clipCreative, kit,
                List.of(new CreativeSpecBuilder.ClipPlacement("9x16", 1080, 1920, silent)), null,
                new CreativeAudio().source("none").volume(BigDecimal.ONE).fadeOutSec(BigDecimal.ONE));
        @SuppressWarnings("unchecked")
        Map<String, Object> silentPlacement = (Map<String, Object>) silentSpec.getPlacements().get(0);
        assertThat(silentPlacement).containsEntry("clipHasAudio", false).containsEntry("width", 1080);
    }

    @Test
    void aMutedClipHasNoTrackUrl() {
        Creative clipCreative = creative();
        clipCreative.setKind(Creative.KIND_CLIP);
        MediaFacts clip = MediaFacts.local("clip", CreativePhoto.MEDIA_KIND_VIDEO, new BigDecimal("5"), true, 1080, 1920);
        CreativeRenderSpec spec = builder.buildClip("r-1", clipCreative, kit,
                List.of(new CreativeSpecBuilder.ClipPlacement("9x16", 1080, 1920, clip)), null,
                new CreativeAudio().source("none").volume(BigDecimal.ONE).fadeOutSec(BigDecimal.ONE));
        assertThat(spec.getCreative().getAudio().getTrackUrl()).isNull();
    }

    private Creative creative() {
        Creative c = new Creative();
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setLockup(Creative.LOCKUP_PLAIN);
        c.setKind(Creative.KIND_STILL);
        c.setHeadline("Plan the week in *one sentence*.");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        return c;
    }

    private MediaFacts libraryPhoto(String id) {
        CreativePhoto photo = new CreativePhoto();
        photo.setId(id);
        photo.setGcsPath("photos/" + id + ".jpg");
        photo.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        photo.setFocal(objectMapper.valueToTree(Map.of("x", "0.3", "y", "0.7")));
        return MediaFacts.of(photo);
    }
}
