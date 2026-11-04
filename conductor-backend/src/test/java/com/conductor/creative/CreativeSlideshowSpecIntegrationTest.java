package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeDraftSpecRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The slideshow spec a CAROUSEL Creative keeps ({@code slideshowSpec}) and its revisions, against a real
 * database: validation rule ids, the clear rules (mirroring {@code slideStyle}), revisions written with the
 * save, variants, draft-spec and the revision list.
 */
class CreativeSlideshowSpecIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRevisionRepository revisionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Spec Test");
        project.setKey("SP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    void creatingACarouselWithASpecStoresItAndWritesACommitRevision() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "Hook one", "Hook two"));

        assertThat(created.getSlideshowSpec().at("/slides/0/texts/0/t").asText()).isEqualTo("Hook one");
        assertThat(created.getSlideshowSpec().at("/slides/0/bg").asText()).isEqualTo("media:" + ids.get(0));

        List<CreativeRevision> revisions = revisions(created);
        assertThat(revisions).singleElement().satisfies(r -> {
            assertThat(r.getRevisionNo()).isEqualTo(1);
            assertThat(r.getSource()).isEqualTo("COMMIT");
            assertThat(r.getStatus()).isEqualTo("APPLIED");
            assertThat(r.getCreativeVersion()).isEqualTo(created.getVersion());
            assertThat(r.getSpec()).isEqualTo(created.getSlideshowSpec());
            assertThat(r.getSlides()).isEqualTo(created.getSlides());
            assertThat(r.getTextDiff().isArray()).isTrue();
            assertThat(r.getTextDiff()).isEmpty();
            assertThat(r.getAuthorUserId()).isEqualTo(admin.getId());
            assertThat(r.getAppliedAt()).isNotNull();
        });
    }

    @Test
    void aCreativeWithoutASpecHasNoRevisions() {
        Creative created = creativeService.createCreative(project.getId(),
                carousel(Map.of("default", images(2))), admin).creative();
        assertThat(created.getSlideshowSpec()).isNull();
        assertThat(revisions(created)).isEmpty();
    }

    @Test
    void aSpecOnANonCarouselIsRefusedWithTheKindRule() {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("Hello");
        request.setSlideshowSpec(specMap(images(1), "a"));
        assertThat(ruleIds(request)).containsExactly("slideshowSpecKind");
    }

    @Test
    void aStructurallyBadSpecIsInvalid() {
        List<String> ids = images(2);
        CreateCreativeRequest request = carousel(Map.of("default", ids));
        request.setSlideshowSpec(Map.of("id", "x", "slides", List.of()));
        assertThat(ruleIds(request)).contains("slideshowSpecInvalid");

        Map<String, Object> noId = new LinkedHashMap<>(specMap(ids, "a", "b"));
        noId.remove("id");
        request.setSlideshowSpec(noId);
        assertThat(violations(request)).extracting(CreativeValidationException.Violation::field).contains("slideshowSpec.id");
    }

    @Test
    void anImageRefThatIsNotAProjectImageIsAMediaViolationNamingTheField() {
        List<String> ids = images(2);
        CreativePhoto video = photo(project.getId(), CreativePhoto.MEDIA_KIND_VIDEO);
        CreativePhoto elsewhere = photo(otherProject().getId(), CreativePhoto.MEDIA_KIND_IMAGE);

        for (String bad : List.of("http://example.com/a.png", "/tmp/a.png", "file:///a.png", "data:image/png;base64,AAAA",
                "media:" + UUID.randomUUID(), "media:" + video.getId(), "media:" + elsewhere.getId(), "media:not-a-uuid")) {
            Map<String, Object> spec = specMap(ids, "a", "b");
            slide(spec, 1).put("bg", bad);
            CreateCreativeRequest request = carousel(Map.of("default", ids));
            request.setSlideshowSpec(spec);
            List<CreativeValidationException.Violation> v = violations(request);
            assertThat(v).as(bad).singleElement().satisfies(x -> {
                assertThat(x.ruleId()).isEqualTo("slideshowSpecMedia");
                assertThat(x.field()).isEqualTo("slideshowSpec.slides[1].bg");
            });
        }
    }

    @Test
    void everyImageFieldKindIsChecked() {
        List<String> ids = images(2);
        Map<String, Object> spec = specMap(ids, "a", "b");
        slide(spec, 0).put("shot", "x.png");
        slide(spec, 0).put("photos", List.of(Map.of("src", "media:" + ids.get(0)), Map.of("src", "nope")));
        slide(spec, 1).put("days", List.of(Map.of("d", "Mon", "t", "x", "img", "nope")));
        CreateCreativeRequest request = carousel(Map.of("default", ids));
        request.setSlideshowSpec(spec);
        assertThat(violations(request)).extracting(CreativeValidationException.Violation::field).containsExactlyInAnyOrder(
                "slideshowSpec.slides[0].shot", "slideshowSpec.slides[0].photos[1].src",
                "slideshowSpec.slides[1].days[0].img");
    }

    @Test
    void theSlideCountMustMatchEveryNonEmptyList() {
        List<String> ids = images(3);
        CreateCreativeRequest request = carousel(Map.of("default", ids));
        request.setSlideshowSpec(specMap(images(2), "a", "b"));
        assertThat(violations(request)).singleElement().satisfies(x -> {
            assertThat(x.ruleId()).isEqualTo("slideshowSpecSlideCount");
            assertThat(x.field()).isEqualTo("slideshowSpec.slides");
        });

        Map<String, List<String>> two = new LinkedHashMap<>();
        two.put("9x16", images(2));
        two.put("4x5", images(3));
        two.put("1x1", List.of());
        CreateCreativeRequest mixed = carousel(two);
        mixed.setSlideshowSpec(specMap(images(2), "a", "b"));
        assertThat(violations(mixed)).extracting(CreativeValidationException.Violation::ruleId)
                .contains("slideshowSpecSlideCount");
    }

    @Test
    void aClientSentResolvedStyleIsStripped() {
        List<String> ids = images(2);
        Map<String, Object> spec = specMap(ids, "a", "b");
        spec.put("resolvedStyle", Map.of("accent", "#fff"));
        spec.put("style", Map.of("accent", "#abc", "resolvedStyle", Map.of("x", 1)));
        Creative created = createCarousel(ids, spec);
        assertThat(created.getSlideshowSpec().has("resolvedStyle")).isFalse();
        assertThat(created.getSlideshowSpec().get("style").has("resolvedStyle")).isFalse();
        assertThat(created.getSlideshowSpec().at("/style/accent").asText()).isEqualTo("#abc");
    }

    // ── patch: set, replace, clear ──────────────────────────────────────────

    @Test
    void settingASpecOnACarouselThatHadNoneWritesAnImportRevision() {
        List<String> ids = images(2);
        Creative created = creativeService.createCreative(project.getId(), carousel(Map.of("default", ids)), admin).creative();

        PatchCreativeRequest patch = patch(created);
        patch.setSlideshowSpec(specMap(ids, "a", "b"));
        Creative patched = creativeService.patchCreative(project.getId(), created.getId(), patch, admin).creative();

        assertThat(patched.getSlideshowSpec()).isNotNull();
        assertThat(revisions(patched)).singleElement().satisfies(r -> {
            assertThat(r.getSource()).isEqualTo("IMPORT");
            assertThat(r.getRevisionNo()).isEqualTo(1);
            assertThat(r.getTextDiff()).isEmpty();
            assertThat(r.getCreativeVersion()).isEqualTo(patched.getVersion());
        });
        assertThat(patched.getVersion()).isGreaterThan(created.getVersion());
    }

    @Test
    void replacingASpecWritesACommitRevisionWithTheTextDiffAndTheNextNumber() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "Hook one", "Hook two"));

        PatchCreativeRequest patch = patch(created);
        patch.setSlideshowSpec(specMap(ids, "Hook ONE", "Hook two"));
        Creative patched = creativeService.patchCreative(project.getId(), created.getId(), patch, admin).creative();

        List<CreativeRevision> revisions = revisions(patched);
        assertThat(revisions).extracting(CreativeRevision::getRevisionNo).containsExactly(2, 1);
        CreativeRevision newest = revisions.get(0);
        assertThat(newest.getSource()).isEqualTo("COMMIT");
        assertThat(newest.getCreativeVersion()).isEqualTo(patched.getVersion());
        assertThat(newest.getTextDiff()).hasSize(1);
        JsonNode change = newest.getTextDiff().get(0);
        assertThat(change.get("slide").asInt()).isEqualTo(1);
        assertThat(change.get("path").asText()).isEqualTo("texts[0].t");
        assertThat(change.get("old").asText()).isEqualTo("Hook one");
        assertThat(change.get("new").asText()).isEqualTo("Hook ONE");
        assertThat(newest.getSpec()).isEqualTo(patched.getSlideshowSpec());
    }

    @Test
    void resendingTheIdenticalSpecWritesNoRevision() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));

        PatchCreativeRequest patch = patch(created);
        patch.setSlideshowSpec(specFor(ids, "a", "b"));
        patch.setCaption("another caption");
        Creative patched = creativeService.patchCreative(project.getId(), created.getId(), patch, admin).creative();

        assertThat(patched.getSlideshowSpec()).isEqualTo(created.getSlideshowSpec());
        assertThat(revisions(patched)).hasSize(1);
    }

    @Test
    void patchingAnotherFieldKeepsTheSpecAndWritesNoRevision() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        PatchCreativeRequest patch = patch(created);
        patch.setCaption("changed");
        Creative patched = creativeService.patchCreative(project.getId(), created.getId(), patch, admin).creative();
        assertThat(patched.getSlideshowSpec()).isEqualTo(created.getSlideshowSpec());
        assertThat(revisions(patched)).hasSize(1);
    }

    @Test
    void changingSlidesWithoutASpecClearsItButResendingTheSameSlidesKeepsIt() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));

        PatchCreativeRequest same = patch(created);
        same.setSlides(Map.of("default", List.copyOf(ids)));
        Creative kept = creativeService.patchCreative(project.getId(), created.getId(), same, admin).creative();
        assertThat(kept.getSlideshowSpec()).isNotNull();

        PatchCreativeRequest reordered = patch(kept);
        reordered.setSlides(Map.of("default", List.of(ids.get(1), ids.get(0))));
        Creative cleared = creativeService.patchCreative(project.getId(), created.getId(), reordered, admin).creative();
        assertThat(cleared.getSlideshowSpec()).isNull();
        assertThat(revisions(cleared)).hasSize(1);

        PatchCreativeRequest replaced = patch(cleared);
        replaced.setSlides(Map.of("default", images(2)));
        replaced.setSlideshowSpec(specMap(images(2), "x", "y"));
        Creative resent = creativeService.patchCreative(project.getId(), created.getId(), replaced, admin).creative();
        assertThat(resent.getSlideshowSpec()).isNotNull();
        assertThat(revisions(resent)).extracting(CreativeRevision::getSource).containsExactly("IMPORT", "COMMIT");
    }

    @Test
    void slidesAndASpecOfAnotherLengthInTheSameRequestAreCountChecked() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        PatchCreativeRequest patch = patch(created);
        patch.setSlides(Map.of("default", images(3)));
        patch.setSlideshowSpec(specMap(images(2), "a", "b"));
        assertThatThrownBy(() -> creativeService.patchCreative(project.getId(), created.getId(), patch, admin))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> assertThat(e.violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("slideshowSpecSlideCount"));
    }

    @Test
    void anExplicitNullOrEmptyObjectClearsTheSpecWithoutARevisionAndAbsentKeepsIt() throws Exception {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

        PatchCreativeRequest absent = json.readValue("{\"caption\":\"x\"}", PatchCreativeRequest.class);
        assertThat(absent.getSlideshowSpec()).isNull();
        absent.setVersion(created.getVersion());
        Creative kept = creativeService.patchCreative(project.getId(), created.getId(), absent, admin).creative();
        assertThat(kept.getSlideshowSpec()).isNotNull();

        PatchCreativeRequest explicitNull = json.readValue("{\"slideshowSpec\":null}", PatchCreativeRequest.class);
        assertThat(explicitNull.getSlideshowSpec()).isNotNull().isEmpty();
        explicitNull.setVersion(kept.getVersion());
        Creative cleared = creativeService.patchCreative(project.getId(), created.getId(), explicitNull, admin).creative();
        assertThat(cleared.getSlideshowSpec()).isNull();
        assertThat(revisions(cleared)).hasSize(1);

        // Setting it again after a clear is an import, not a commit.
        PatchCreativeRequest again = patch(cleared);
        again.setSlideshowSpec(specMap(ids, "a", "b"));
        Creative reset = creativeService.patchCreative(project.getId(), created.getId(), again, admin).creative();
        assertThat(revisions(reset)).extracting(CreativeRevision::getSource).containsExactly("IMPORT", "COMMIT");

        PatchCreativeRequest emptyObject = json.readValue("{\"slideshowSpec\":{}}", PatchCreativeRequest.class);
        emptyObject.setVersion(reset.getVersion());
        assertThat(creativeService.patchCreative(project.getId(), created.getId(), emptyObject, admin).creative()
                .getSlideshowSpec()).isNull();
    }

    @Test
    void leavingCarouselClearsTheSpecAndSettingOneOnAnotherKindIsRefused() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));

        PatchCreativeRequest toStill = patch(created);
        toStill.setKind(CreativeKind.STILL);
        toStill.setHeadline("A headline");
        toStill.setPhotoId(ids.get(0));
        Creative still = creativeService.patchCreative(project.getId(), created.getId(), toStill, admin).creative();
        assertThat(still.getKind()).isEqualTo("STILL");
        assertThat(still.getSlideshowSpec()).isNull();

        PatchCreativeRequest set = patch(still);
        set.setSlideshowSpec(specMap(ids, "a", "b"));
        assertThatThrownBy(() -> creativeService.patchCreative(project.getId(), created.getId(), set, admin))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> assertThat(e.violations())
                        .extracting(CreativeValidationException.Violation::ruleId).contains("slideshowSpecKind"));

        // clearing is allowed on any kind
        PatchCreativeRequest clear = patch(still);
        clear.setSlideshowSpec(Map.of());
        assertThat(creativeService.patchCreative(project.getId(), created.getId(), clear, admin).creative()
                .getSlideshowSpec()).isNull();
    }

    @Test
    void aRejectedPatchWritesNoRevision() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        PatchCreativeRequest patch = patch(created);
        Map<String, Object> bad = specMap(ids, "changed", "b");
        slide(bad, 0).put("bg", "http://x/y.png");
        patch.setSlideshowSpec(bad);
        assertThatThrownBy(() -> creativeService.patchCreative(project.getId(), created.getId(), patch, admin))
                .isInstanceOf(CreativeValidationException.class);
        assertThat(revisions(created)).hasSize(1);
        assertThat(creativeRepository.findById(created.getId()).orElseThrow().getSlideshowSpec().at("/slides/0/texts/0/t")
                .asText()).isEqualTo("a");
    }

    // ── variants ────────────────────────────────────────────────────────────

    @Test
    void aVariantCopiesTheSpecAndStartsItsOwnRevisionsWithAnImport() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        Creative variant = creativeService.createVariant(project.getId(), created.getId(),
                new CreateCreativeVariantRequest(), admin).creative();

        assertThat(variant.getSlideshowSpec()).isEqualTo(created.getSlideshowSpec());
        assertThat(revisions(variant)).singleElement().satisfies(r -> {
            assertThat(r.getSource()).isEqualTo("IMPORT");
            assertThat(r.getRevisionNo()).isEqualTo(1);
            assertThat(r.getCreativeVersion()).isEqualTo(variant.getVersion());
        });
        assertThat(revisions(created)).hasSize(1);
    }

    // ── draft spec ──────────────────────────────────────────────────────────

    @Test
    void aDraftIsValidatedLikeCreateAndNothingIsRecorded() {
        List<String> ids = images(2);
        CreativeDraftSpecRequest draft = new CreativeDraftSpecRequest();
        draft.setKind(CreativeKind.CAROUSEL);
        draft.setCaption("c");
        draft.setAltText("alt");
        draft.setSlides(Map.of("default", ids));
        draft.setSlideshowSpec(specMap(ids, "a", "b"));
        creativeService.buildDraftSpec(project.getId(), draft, admin);

        Map<String, Object> bad = specMap(ids, "a", "b");
        slide(bad, 0).put("bg", "/local/path.png");
        draft.setSlideshowSpec(bad);
        assertThatThrownBy(() -> creativeService.buildDraftSpec(project.getId(), draft, admin))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> assertThat(e.violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("slideshowSpecMedia"));

        draft.setSlideshowSpec(specMap(images(3), "a", "b", "c"));
        assertThatThrownBy(() -> creativeService.buildDraftSpec(project.getId(), draft, admin))
                .isInstanceOfSatisfying(CreativeValidationException.class, e -> assertThat(e.violations())
                        .extracting(CreativeValidationException.Violation::ruleId).contains("slideshowSpecSlideCount"));
        assertThat(revisionRepository.findAll()).noneMatch(r -> project.getId().equals(r.getProjectId()));
    }

    @Test
    void aDraftOverABaseKeepsItsSpecUnlessTheSlidesChange() {
        List<String> ids = images(2);
        Creative base = createCarousel(ids, specFor(ids, "a", "b"));

        CreativeDraftSpecRequest keep = new CreativeDraftSpecRequest();
        keep.setBaseCreativeId(base.getId());
        keep.setCaption("only the caption changes");
        creativeService.buildDraftSpec(project.getId(), keep, admin);

        // Different slides without a spec: the base spec (2 slides) must not be carried onto 3 slides, which
        // would trip the count rule.
        CreativeDraftSpecRequest other = new CreativeDraftSpecRequest();
        other.setBaseCreativeId(base.getId());
        other.setSlides(Map.of("default", images(3)));
        creativeService.buildDraftSpec(project.getId(), other, admin);
    }

    // ── revisions list ──────────────────────────────────────────────────────

    @Test
    void theRevisionListIsNewestFirstCappedAtFiftyAndMembersOnly() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "t0", "b"));
        Creative current = created;
        for (int i = 1; i <= 52; i++) {
            PatchCreativeRequest patch = patch(current);
            patch.setSlideshowSpec(specMap(ids, "t" + i, "b"));
            current = creativeService.patchCreative(project.getId(), created.getId(), patch, admin).creative();
        }

        List<CreativeRevision> list = creativeService.listRevisions(project.getId(), created.getId(), admin);
        assertThat(list).hasSize(50);
        assertThat(list.get(0).getRevisionNo()).isEqualTo(53);
        assertThat(list.get(49).getRevisionNo()).isEqualTo(4);
        assertThat(list).isSortedAccordingTo((a, b) -> Integer.compare(b.getRevisionNo(), a.getRevisionNo()));

        User outsider = newUser();
        assertThatThrownBy(() -> creativeService.listRevisions(project.getId(), created.getId(), outsider))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> creativeService.listRevisions(project.getId(), "no-such-creative", admin))
                .isInstanceOf(EntityNotFoundException.class);
        Project other = otherProject();
        assertThatThrownBy(() -> creativeService.listRevisions(other.getId(), created.getId(), admin))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deletingACreativeDeletesItsRevisions() {
        List<String> ids = images(2);
        Creative created = createCarousel(ids, specFor(ids, "a", "b"));
        assertThat(revisions(created)).hasSize(1);
        creativeService.deleteCreative(project.getId(), created.getId(), admin);
        assertThat(revisionRepository.findMaxRevisionNo(created.getId())).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Creative createCarousel(List<String> ids, Map<String, Object> spec) {
        CreateCreativeRequest request = carousel(Map.of("default", ids));
        request.setSlideshowSpec(spec);
        return creativeService.createCreative(project.getId(), request, admin).creative();
    }

    private CreateCreativeRequest carousel(Map<String, List<String>> slides) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CAROUSEL);
        request.setCaption("Seven ways to save recipes.");
        request.setAltText("Recipe cards.");
        request.setSlides(slides);
        return request;
    }

    private PatchCreativeRequest patch(Creative current) {
        Creative fresh = creativeRepository.findById(current.getId()).orElseThrow();
        PatchCreativeRequest request = new PatchCreativeRequest();
        request.setVersion(fresh.getVersion());
        return request;
    }

    private List<CreativeRevision> revisions(Creative c) {
        return creativeService.listRevisions(project.getId(), c.getId(), admin);
    }

    private List<CreativeValidationException.Violation> violations(CreateCreativeRequest request) {
        try {
            creativeService.createCreative(project.getId(), request, admin);
        } catch (CreativeValidationException e) {
            return e.violations();
        }
        throw new AssertionError("expected a CreativeValidationException");
    }

    private List<String> ruleIds(CreateCreativeRequest request) {
        return violations(request).stream().map(CreativeValidationException.Violation::ruleId).toList();
    }

    /** One slide per id, each with a bg and one text. */
    private Map<String, Object> specMap(List<String> ids, String... texts) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "spec-" + UUID.randomUUID().toString().substring(0, 8));
        List<Object> slides = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> slide = new LinkedHashMap<>();
            slide.put("type", "photo");
            slide.put("bg", "media:" + ids.get(i));
            slide.put("texts", List.of(new LinkedHashMap<>(Map.of("t", texts[i], "y", 40))));
            slides.add(slide);
        }
        spec.put("slides", slides);
        return spec;
    }

    /** A stable spec (same id each call) so identical resends compare equal. */
    private Map<String, Object> specFor(List<String> ids, String... texts) {
        Map<String, Object> spec = specMap(ids, texts);
        spec.put("id", "stable");
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slide(Map<String, Object> spec, int index) {
        return (Map<String, Object>) ((List<Object>) spec.get("slides")).get(index);
    }

    private List<String> images(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(photo(project.getId(), CreativePhoto.MEDIA_KIND_IMAGE).getId());
        }
        return ids;
    }

    private CreativePhoto photo(String projectId, String mediaKind) {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(projectId);
        p.setMediaKind(mediaKind);
        p.setGcsPath("projects/" + projectId + "/marketing/photos/" + UUID.randomUUID() + ".jpg");
        p.setContentType(CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind) ? "video/mp4" : "image/jpeg");
        p.setSizeBytes(1000L);
        p.setWidth(1080);
        p.setHeight(1920);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        if (CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind)) {
            p.setDurationSeconds(java.math.BigDecimal.valueOf(5));
        }
        return photoRepository.save(p);
    }

    private Project otherProject() {
        Project p = new Project();
        p.setName("Other");
        p.setKey("OT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(admin);
        return projectRepository.save(p);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Spec Admin");
        return userRepository.save(user);
    }
}
