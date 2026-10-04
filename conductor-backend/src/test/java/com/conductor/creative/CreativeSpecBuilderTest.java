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
