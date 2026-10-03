package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeDraftSpecRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.generated.v2.model.LocalMediaInfo;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CreativeService#buildDraftSpec}: the render spec of a Creative that was never saved. A pure unit
 * test (mock repositories, real validator/registry/spec builder) because the properties under test are
 * about what the service does and does not <em>call</em> — above all, that nothing is ever written.
 */
class CreativeDraftSpecTest {

    private static final String PROJECT = "proj-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CreativeRepository creativeRepository;
    private CreativePhotoRepository photoRepository;
    private BrandKitRepository brandKitRepository;
    private BrandKitService brandKitService;
    private ProjectRepository projectRepository;
    private CreativeRenderService renderService;
    private CreativeExperimentRepository experimentRepository;
    private CreativeRenderFrameRepository frameRepository;
    private CreativeRenderRepository renderRepository;
    private AssetRepository assetRepository;
    private ProjectSecurityService security;
    private StorageService storage;
    private CreativeService service;
    private BrandKit kit;
    private User caller;

    @BeforeEach
    void setUp() {
        creativeRepository = mock(CreativeRepository.class);
        photoRepository = mock(CreativePhotoRepository.class);
        brandKitRepository = mock(BrandKitRepository.class);
        brandKitService = mock(BrandKitService.class);
        projectRepository = mock(ProjectRepository.class);
        renderService = mock(CreativeRenderService.class);
        experimentRepository = mock(CreativeExperimentRepository.class);
        frameRepository = mock(CreativeRenderFrameRepository.class);
        renderRepository = mock(CreativeRenderRepository.class);
        assetRepository = mock(AssetRepository.class);
        security = mock(ProjectSecurityService.class);
        storage = mock(StorageService.class);
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed://" + i.getArgument(0));

        CreativeRegistry registry = new CreativeRegistry(objectMapper);
        service = new CreativeService(creativeRepository, photoRepository, brandKitRepository, brandKitService,
                projectRepository, registry, new CreativeValidator(registry),
                new CreativeSpecBuilder(registry, storage, objectMapper), security, storage, objectMapper,
                renderService, experimentRepository, frameRepository, renderRepository, assetRepository);

        caller = new User();
        caller.setId("user-1");
        when(security.isProjectMember(eq(PROJECT), anyString())).thenReturn(true);
        when(security.isAdminOrCreator(eq(PROJECT), anyString())).thenReturn(true);

        kit = new BrandKit();
        kit.setId("kit-1");
        kit.setProjectId(PROJECT);
        kit.setTokens(objectMapper.valueToTree(Map.of("accent", "#3B82F6")));
        kit.setCopyRules(objectMapper.createArrayNode());
        kit.setApprovedLines(objectMapper.createArrayNode());
        kit.setEnabledPlacements(objectMapper.valueToTree(List.of("9x16", "4x5", "1x1")));
        when(brandKitRepository.findByIdAndProjectId("kit-1", PROJECT)).thenReturn(Optional.of(kit));
        when(brandKitService.peekDefault(PROJECT)).thenReturn(kit);
        when(brandKitService.resolveDefault(PROJECT)).thenReturn(kit);
    }

    // ── 200 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aLibraryPhotoGivesASpecWithItsSignedUrlAndTheDraftRenderId() {
        CreativePhoto photo = libraryPhoto("photo-1");
        when(photoRepository.findByIdAndProjectId("photo-1", PROJECT)).thenReturn(Optional.of(photo));

        CreativeService.DraftSpec draft = service.buildDraftSpec(PROJECT,
                draftRequest("Plan the week in *one sentence*.").photoId("photo-1"), caller);

        CreativeRenderSpec spec = draft.spec();
        assertThat(spec.getRenderId()).isEqualTo("draft");
        assertThat(spec.getPreviewOnly()).isTrue();
        assertThat(spec.getCreative().getHeadline()).isEqualTo("Plan the week in *one sentence*.");
        assertThat(spec.getCreative().getPhotoUrl()).isEqualTo("signed://photos/photo-1.jpg");
        assertThat(spec.getCreative().getFocal()).containsEntry("x", "0.3");
        assertThat(spec.getPlacements()).containsExactlyInAnyOrder("9x16", "4x5", "1x1");
        assertThat(draft.readiness().items()).extracting(CreativeService.ReadinessItem::key)
                .contains("caption", "photoChecked", "photoProvenance", "state");
    }

    @Test
    void previewOnlyFalseIsEchoedIntoTheSpec() {
        CreativeService.DraftSpec draft = service.buildDraftSpec(PROJECT,
                draftRequest("Hello").previewOnly(false), caller);

        assertThat(draft.spec().getPreviewOnly()).isFalse();
    }

    @Test
    void aLocalPhotoIsEmittedAsTheLiteralLocalReferenceAndNeverLookedUpInTheLibrary() {
        CreativeService.DraftSpec draft = service.buildDraftSpec(PROJECT,
                draftRequest("Hello").photoId("local:hero").localMedia(Map.of("hero", image())), caller);

        assertThat(draft.spec().getCreative().getPhotoUrl()).isEqualTo("local:hero");
        assertThat(draft.spec().getCreative().getFocal()).isNull();
        verify(photoRepository, never()).findByIdAndProjectId(eq("local:hero"), anyString());
        verify(photoRepository, never()).findAllByIdIn(any());
        // Local media count as provenance-unknown: readiness reports what is missing.
        assertThat(draft.readiness().ready()).isFalse();
        assertThat(draft.readiness().items())
                .filteredOn(i -> i.key().equals("photoChecked") || i.key().equals("photoProvenance"))
                .hasSize(2).allMatch(i -> !i.ok() && i.blocking());
    }

    @Test
    void aLocalSequenceBeatPhotoIsEmittedAsLocalAndTheOtherBeatsInheritTheMainPhoto() {
        CreativePhoto photo = libraryPhoto("photo-1");
        when(photoRepository.findByIdAndProjectId("photo-1", PROJECT)).thenReturn(Optional.of(photo));
        CreativeDraftSpecRequest request = draftRequest("Story *one*")
                .photoId("photo-1")
                .sequenceKind(SequenceKind.STORY)
                .sequence(List.of(new SequenceBeat().headline("Story *one*"),
                        new SequenceBeat().headline("Beat *two*").photoId("local:b2")))
                .localMedia(Map.of("b2", image()));

        CreativeService.DraftSpec draft = service.buildDraftSpec(PROJECT, request, caller);

        assertThat(draft.spec().getPlacements()).containsExactly("story");
        assertThat(draft.spec().getCreative().getSequence()).extracting("photoUrl")
                .containsExactly("signed://photos/photo-1.jpg", "local:b2");
        assertThat(draft.spec().getCreative().getSequence().get(1).getFocal()).isNull();
    }

    @Test
    void aMotionDraftWithLocalClipAndTrackUsesTheirDeclaredMetadata() {
        CreativeDraftSpecRequest request = draftRequest("Hello *there*")
                .kind(CreativeKind.MOTION)
                .motion(new CreativeMotion().background(new CreativeMotionBackground()
                        .source("clip").clipMediaId("local:clip")))
                .audio(new CreativeAudio().source("track").trackId("local:song"))
                .localMedia(Map.of(
                        "clip", new LocalMediaInfo(MediaKind.VIDEO).durationSeconds(new BigDecimal("10")).hasAudio(true),
                        "song", new LocalMediaInfo(MediaKind.AUDIO).durationSeconds(new BigDecimal("30"))));

        CreativeRenderSpec spec = service.buildDraftSpec(PROJECT, request, caller).spec();

        assertThat(spec.getCreative().getMotion().getBackground().getClipUrl()).isEqualTo("local:clip");
        assertThat(spec.getCreative().getMotion().getPreset()).isEqualTo("fade-up");
        assertThat(spec.getCreative().getAudio().getTrackUrl()).isEqualTo("local:song");
        assertThat(spec.getCreative().getClipHasAudio()).isTrue();
    }

    @Test
    void motionClipRulesReadTheLocalMediasDurationAndAudioFlag() {
        CreativeDraftSpecRequest request = draftRequest("Hello *there*")
                .kind(CreativeKind.MOTION)
                .motion(new CreativeMotion().background(new CreativeMotionBackground()
                        .source("clip").clipMediaId("local:clip").clipStartSec(new BigDecimal("12"))))
                .audio(new CreativeAudio().source("clip"))
                .localMedia(Map.of("clip", new LocalMediaInfo(MediaKind.VIDEO)
                        .durationSeconds(new BigDecimal("10")).hasAudio(false)));

        assertThatThrownBy(() -> service.buildDraftSpec(PROJECT, request, caller))
                .isInstanceOfSatisfying(CreativeValidationException.class, e ->
                        assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId)
                                .containsExactlyInAnyOrder("clipStartBeyondDuration", "audioClipHasNoAudio"));
    }

    // ── 422 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCopyRuleViolationIsTheSameViolationCreateReports() {
        kit.setCopyRules(objectMapper.valueToTree(List.of(Map.of("id", "noExclaim", "pattern", "!",
                "message", "No exclamation marks.", "fields", List.of("headline")))));

        CreativeValidationException fromDraft = catchValidation(() ->
                service.buildDraftSpec(PROJECT, draftRequest("Dinner is ready!"), caller));
        CreativeValidationException fromCreate = catchValidation(() ->
                service.createCreative(PROJECT, new CreateCreativeRequest().headline("Dinner is ready!"), caller));

        assertThat(fromDraft.violations()).containsExactly(
                new CreativeValidationException.Violation("headline", "noExclaim", "No exclamation marks."));
        assertThat(fromDraft.violations()).isEqualTo(fromCreate.violations());
    }

    @Test
    void anUndeclaredLocalKeyIs422NamingTheField() {
        CreativeValidationException e = catchValidation(() ->
                service.buildDraftSpec(PROJECT, draftRequest("Hello").photoId("local:hero"), caller));

        assertThat(e.violations()).containsExactly(new CreativeValidationException.Violation("photoId",
                "localMediaUndeclared", "photoId \"local:hero\" is not declared in localMedia"));
    }

    @Test
    void aLocalKeyDeclaredAsTheWrongKindIs422NamingTheField() {
        CreativeDraftSpecRequest request = draftRequest("Hello")
                .photoId("local:hero")
                .audio(new CreativeAudio().source("track").trackId("local:hero"))
                .kind(CreativeKind.MOTION)
                .localMedia(Map.of("hero", new LocalMediaInfo(MediaKind.VIDEO)));

        CreativeValidationException e = catchValidation(() -> service.buildDraftSpec(PROJECT, request, caller));

        assertThat(e.violations()).extracting(CreativeValidationException.Violation::field, CreativeValidationException.Violation::ruleId)
                .contains(org.assertj.core.groups.Tuple.tuple("photoId", "localMediaKind"),
                        org.assertj.core.groups.Tuple.tuple("audio.trackId", "localMediaKind"));
    }

    @Test
    void aMalformedLocalKeyIs422() {
        CreativeValidationException e = catchValidation(() ->
                service.buildDraftSpec(PROJECT, draftRequest("Hello")
                        .photoId("local:Hero Shot").localMedia(Map.of("Hero Shot", image())), caller));

        assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId)
                .contains("localMediaKey");
    }

    @Test
    void aLocalReferenceWhereLocalMediaIsNotSuppliedIsJustAnUnknownLibraryId() {
        // create (and patch/variant) never accept local references; only the draft path declares them.
        CreativeValidationException e = catchValidation(() ->
                service.createCreative(PROJECT, new CreateCreativeRequest().photoId("local:hero"), caller));

        assertThat(e.violations()).extracting(CreativeValidationException.Violation::ruleId).containsExactly("photoNotFound");
    }

    @Test
    void aClipCreativeHasNoDraftSpec() {
        CreativeValidationException e = catchValidation(() ->
                service.buildDraftSpec(PROJECT, draftRequest("Hello").kind(CreativeKind.CLIP), caller));

        assertThat(e.violations()).extracting(CreativeValidationException.Violation::field).containsExactly("kind");
    }

    // ── auth + lookups ───────────────────────────────────────────────────────────────────────────

    @Test
    void aReviewerCannotBuildADraftSpecJustAsTheyCannotCreate() {
        when(security.isAdminOrCreator(eq(PROJECT), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.buildDraftSpec(PROJECT, draftRequest("Hello"), caller))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anUnknownBaseCreativeIs404() {
        when(creativeRepository.findByIdAndProjectId("nope", PROJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildDraftSpec(PROJECT, draftRequest("Hello").baseCreativeId("nope"), caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── nothing persisted ────────────────────────────────────────────────────────────────────────

    @Test
    void nothingIsWrittenAnywhereWhetherTheDraftSucceedsOrFails() {
        CreativePhoto photo = libraryPhoto("photo-1");
        when(photoRepository.findByIdAndProjectId("photo-1", PROJECT)).thenReturn(Optional.of(photo));
        when(creativeRepository.findByIdAndProjectId("base-1", PROJECT)).thenReturn(Optional.of(baseCreative()));

        service.buildDraftSpec(PROJECT, draftRequest("Hello").photoId("photo-1"), caller);
        service.buildDraftSpec(PROJECT, new CreativeDraftSpecRequest().baseCreativeId("base-1")
                .photoId("local:hero").localMedia(Map.of("hero", image())), caller);
        assertThatThrownBy(() -> service.buildDraftSpec(PROJECT, draftRequest("Hello").photoId("local:x"), caller))
                .isInstanceOf(CreativeValidationException.class);

        for (Object repository : List.of(creativeRepository, photoRepository, brandKitRepository, projectRepository,
                experimentRepository, frameRepository, renderRepository, assetRepository, brandKitService, renderService)) {
            assertThat(mockingDetails(repository).getInvocations())
                    .extracting(i -> i.getMethod().getName())
                    .noneMatch(name -> name.startsWith("save") || name.startsWith("delete")
                            || name.startsWith("flush") || name.startsWith("lockFor") || name.startsWith("persist")
                            || name.startsWith("resolveDefault") || name.startsWith("requestRender"));
        }
        // Nor does it touch storage beyond signing a read URL.
        assertThat(mockingDetails(storage).getInvocations()).extracting(i -> i.getMethod().getName())
                .allMatch(name -> name.equals("generateSignedUrl"));
    }

    // ── baseCreativeId overlay ───────────────────────────────────────────────────────────────────

    @Test
    void theBaseCreativesFieldsAreTheStartingPointAndEveryFieldOnTheRequestOverlaysThem() {
        Creative base = baseCreative();
        when(creativeRepository.findByIdAndProjectId("base-1", PROJECT)).thenReturn(Optional.of(base));
        CreativePhoto photo = libraryPhoto("photo-1");
        when(photoRepository.findByIdAndProjectId("photo-1", PROJECT)).thenReturn(Optional.of(photo));

        CreativeDraftSpecRequest request = new CreativeDraftSpecRequest().baseCreativeId("base-1")
                .headline("A new *hook*")
                .photoId("local:hero")
                .localMedia(Map.of("hero", image()));
        CreativeRenderSpec spec = service.buildDraftSpec(PROJECT, request, caller).spec();

        // overlaid
        assertThat(spec.getCreative().getHeadline()).isEqualTo("A new *hook*");
        assertThat(spec.getCreative().getPhotoUrl()).isEqualTo("local:hero");
        // inherited from the base
        assertThat(spec.getCreative().getBody()).isEqualTo("Body copy");
        assertThat(spec.getCreative().getCaption()).isEqualTo("Base caption");
        assertThat(spec.getCreative().getLayout()).isEqualTo("stacked");
        assertThat(spec.getCreative().getLockup().getValue()).isEqualTo("chip");
        assertThat(spec.getCreative().getPlacements()).containsExactly("story");
        assertThat(spec.getPlacements()).containsExactlyInAnyOrder("9x16", "4x5", "1x1", "story");
        // the headline changed, so the base's hand-tuned type sizes no longer apply (as on patch)
        assertThat(spec.getCreative().getTypeOverrides()).isEmpty();
        // the saved Creative itself is untouched
        assertThat(base.getHeadline()).isEqualTo("The old *hook*");
        assertThat(base.getPhotoId()).isEqualTo("photo-1");
    }

    @Test
    void anUnchangedHeadlineAndLayoutKeepTheBasesTypeOverrides() {
        Creative base = baseCreative();
        when(creativeRepository.findByIdAndProjectId("base-1", PROJECT)).thenReturn(Optional.of(base));
        when(photoRepository.findByIdAndProjectId("photo-1", PROJECT)).thenReturn(Optional.of(libraryPhoto("photo-1")));

        CreativeRenderSpec spec = service.buildDraftSpec(PROJECT,
                new CreativeDraftSpecRequest().baseCreativeId("base-1").caption("A better caption"), caller).spec();

        assertThat(spec.getCreative().getCaption()).isEqualTo("A better caption");
        assertThat(spec.getCreative().getHeadline()).isEqualTo("The old *hook*");
        assertThat(spec.getCreative().getTypeOverrides()).containsKey("headline");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private CreativeValidationException catchValidation(Runnable call) {
        try {
            call.run();
        } catch (CreativeValidationException e) {
            return e;
        }
        throw new AssertionError("expected a CreativeValidationException");
    }

    private CreativeDraftSpecRequest draftRequest(String headline) {
        return new CreativeDraftSpecRequest().headline(headline);
    }

    private LocalMediaInfo image() {
        return new LocalMediaInfo(MediaKind.IMAGE).width(1200).height(1600);
    }

    private CreativePhoto libraryPhoto(String id) {
        CreativePhoto photo = new CreativePhoto();
        photo.setId(id);
        photo.setProjectId(PROJECT);
        photo.setGcsPath("photos/" + id + ".jpg");
        photo.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        photo.setSource("own");
        photo.setLicence("Own work");
        photo.setChecked(true);
        photo.setFocal(objectMapper.valueToTree(Map.of("x", "0.3", "y", "0.7")));
        return photo;
    }

    private Creative baseCreative() {
        Creative c = new Creative();
        c.setId("base-1");
        c.setProjectId(PROJECT);
        c.setBrandKitId("kit-1");
        c.setNumber(7);
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setLockup(Creative.LOCKUP_CHIP);
        c.setKind(Creative.KIND_STILL);
        c.setPhotoId("photo-1");
        c.setHeadline("The old *hook*");
        c.setBody("Body copy");
        c.setCaption("Base caption");
        c.setPlacements(objectMapper.valueToTree(List.of("story")));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.valueToTree(Map.of("headline", List.of(48))));
        return c;
    }
}
