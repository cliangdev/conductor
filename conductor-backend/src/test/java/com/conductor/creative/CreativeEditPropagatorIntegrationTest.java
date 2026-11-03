package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CompleteCreativeRevisionRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.integration.ActionResult;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.AssetService;
import com.conductor.service.PublishBundleHasher;
import com.conductor.service.PublishTargetService;
import com.conductor.service.ReviewService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.service.view.ReviewWithUser;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Carrying a slide-text edit to the Posts that use the Creative, end to end against a real database: a real
 * Creative with a stored spec, a real edit and apply (so the real after-commit event fires), real Posts with
 * real destinations and attached renders. Covers every Post status, the per-target choices that must survive,
 * idempotence, one Post failing, the freeze bypass staying internal, the reviewer fix, and the three read APIs.
 *
 * <p>{@link ActionInvocationService} is mocked (the same single override {@code PublishBundleGuardIntegrationTest}
 * uses, so both share one cached context) for the platform revoke a Scheduled Post triggers.
 */
class CreativeEditPropagatorIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeRevisionService revisionService;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRenderService renderService;
    @Autowired private CreativeAttachService attachService;
    @Autowired private CreativeEditPropagator propagator;
    @Autowired private CreativeEditReadService readService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRevisionRepository revisionRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private PostContentChangeRepository changeRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemReviewerRepository workItemReviewerRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetAssetRepository targetAssetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemService workItemService;
    @Autowired private ReviewService reviewService;
    @Autowired private PublishTargetService publishTargetService;
    @Autowired private PublishBundleHasher bundleHasher;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ActionInvocationService actionInvocationService;

    private User admin;
    private User reviewer;
    private User otherReviewer;
    private Project project;
    private String metaConnectionId;
    private String tiktokConnectionId;
    private Creative creative;
    private CreativeRender render1;

    @BeforeEach
    void setUp() {
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of("post_id", "page_1_post_99")));
        admin = newUser("Slide Editor");
        reviewer = newUser("Rita Reviewer");
        otherReviewer = newUser("Omar Reviewer");
        project = new Project();
        project.setName("Edit Propagation");
        project.setKey("EP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        member(admin, MemberRole.ADMIN);
        member(reviewer, MemberRole.REVIEWER);
        member(otherReviewer, MemberRole.REVIEWER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        workflowSeeder.seedMarketing(project);

        metaConnectionId = connectionRepository.saveAndFlush(connection("meta",
                "{\"pageId\":\"p1\",\"pageName\":\"Acme Page\",\"instagramBusinessAccountId\":\"ig1\","
                        + "\"instagramUsername\":\"acme\"}")).getId();
        tiktokConnectionId = connectionRepository.saveAndFlush(connection("tiktok",
                "{\"creatorNickname\":\"Acme Creator\",\"creatorUsername\":\"acme\"}")).getId();

        creative = slideshow(3);
        render1 = renderService.renderCarouselForRevision(project.getId(), creative, admin.getId());
    }

    // ── propagation across statuses ─────────────────────────────────────────

    @Test
    void anEditMovesEveryUnpublishedPostOntoTheNewSlidesAndReopensTheReviewAsTheStatusCalls() {
        WorkItem draft = post("Draft post");
        WorkItem inReview = post("In review post");
        WorkItem approved = post("Approved post");
        WorkItem scheduled = post("Scheduled post");
        WorkItem published = post("Published post");
        forceStatus(inReview, "IN_REVIEW");
        forceStatus(approved, "APPROVED");
        forceStatus(scheduled, "SCHEDULED");
        handOffFacebook(scheduled);
        forceStatus(published, "PUBLISHED");
        assignReviewer(inReview, reviewer);
        approve(inReview, reviewer);
        assertThat(standingApprovals(inReview)).isEqualTo(1);
        Map<String, String> hashBefore = new LinkedHashMap<>();
        for (WorkItem p : List.of(draft, inReview, approved, scheduled, published)) {
            hashBefore.put(p.getId(), bundleHasher.hash(reload(p)));
        }
        Map<String, List<List<String>>> selectionBefore = new LinkedHashMap<>();
        for (WorkItem p : List.of(draft, inReview, approved, scheduled)) {
            selectionBefore.put(p.getId(), selectionKeys(p));
        }
        clearInvocations(actionInvocationService);

        CreativeRevision revision = editAndApply("First", "A fixed first slide");

        CreativeRender render2 = renderRepository.findById(revision.getRenderId()).orElseThrow();
        assertThat(render2.getId()).isNotEqualTo(render1.getId());
        for (WorkItem p : List.of(draft, inReview, approved, scheduled)) {
            // On the new frames only, destinations and slide order exactly as they were, bundle hash changed.
            assertThat(framesOf(p)).allSatisfy(f -> assertThat(f.getRenderId()).isEqualTo(render2.getId()));
            assertThat(assetRepository.findAllByWorkItemId(p.getId())).hasSize(6);
            assertThat(selectionKeys(p)).isEqualTo(selectionBefore.get(p.getId()));
            assertThat(bundleHasher.hash(reload(p))).isNotEqualTo(hashBefore.get(p.getId()));
        }

        assertThat(reload(draft).getCurrentStatus()).isEqualTo("DRAFT");
        assertThat(reload(draft).getCurrentReviewRound()).isZero();
        assertThat(change(draft)).satisfies(c -> {
            assertThat(c.getFromStatus()).isEqualTo("DRAFT");
            assertThat(c.getToStatus()).isEqualTo("DRAFT");
            assertThat(c.getReviewRoundBefore()).isZero();
            assertThat(c.getReviewRoundAfter()).isZero();
            assertThat(c.getSource()).isEqualTo("EDITOR");
            assertThat(c.getActorUserId()).isEqualTo(admin.getId());
            assertThat(c.getRevisionId()).isEqualTo(revision.getId());
            assertThat(c.getTextDiff().get(0).get("new").asText()).isEqualTo("A fixed first slide");
        });

        // In review: stays, new round, the approval cast against the old slides stops counting.
        assertThat(reload(inReview).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(inReview).getCurrentReviewRound()).isEqualTo(1);
        assertThat(change(inReview)).satisfies(c -> {
            assertThat(c.getFromStatus()).isEqualTo("IN_REVIEW");
            assertThat(c.getToStatus()).isEqualTo("IN_REVIEW");
            assertThat(c.getReviewRoundBefore()).isZero();
            assertThat(c.getReviewRoundAfter()).isEqualTo(1);
        });
        assertThat(standingApprovals(inReview)).isZero();

        // Approved: back to the review status with a new round.
        assertThat(reload(approved).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(approved).getCurrentReviewRound()).isEqualTo(1);
        assertThat(change(approved)).satisfies(c -> {
            assertThat(c.getFromStatus()).isEqualTo("APPROVED");
            assertThat(c.getToStatus()).isEqualTo("IN_REVIEW");
            assertThat(c.getReviewRoundAfter()).isEqualTo(1);
        });

        // Scheduled: the platform hand-off is revoked first, then back to review.
        assertThat(reload(scheduled).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(scheduled).getCurrentReviewRound()).isEqualTo(1);
        assertThat(targetOf(scheduled, "facebook").getState()).isEqualTo(PostPublishTargetState.REVOKED);
        verify(actionInvocationService, times(1)).invoke(any(), anyString(), any(), anyString(), any());
        assertThat(change(scheduled).getFromStatus()).isEqualTo("SCHEDULED");

        // Published: untouched, no record.
        assertThat(reload(published).getCurrentStatus()).isEqualTo("PUBLISHED");
        assertThat(framesOf(published)).allSatisfy(f -> assertThat(f.getRenderId()).isEqualTo(render1.getId()));
        assertThat(bundleHasher.hash(reload(published))).isEqualTo(hashBefore.get(published.getId()));
        assertThat(changeRepository.findAllByWorkItemIdOrderByCreatedAtDescIdDesc(published.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
    }

    @Test
    void perTargetChoicesSurviveTheMove() {
        WorkItem post = post("Choices");
        PostPublishTarget instagram = targetOf(post, "instagram");
        instagram.setCaptionOverride("Instagram only caption");
        instagram.setPublishOptions("{\"altText\":\"Hand-written alt\",\"collaborators\":[\"someone\"]}");
        targetRepository.saveAndFlush(instagram);
        List<List<String>> before = selectionKeys(post);
        String facebookOptionsBefore = targetOf(post, "facebook").getPublishOptions();

        editAndApply("Second", "A different second");

        assertThat(selectionKeys(post)).isEqualTo(before);
        PostPublishTarget after = targetOf(post, "instagram");
        assertThat(after.getCaptionOverride()).isEqualTo("Instagram only caption");
        assertThat(after.getPublishOptions()).contains("Hand-written alt").contains("someone");
        assertThat(targetOf(post, "facebook").getPublishOptions()).isEqualTo(facebookOptionsBefore);
        // Each destination is still on its own ratio's slides, in slide order.
        List<Asset> instagramAssets = assetsOf(after);
        assertThat(instagramAssets).hasSize(3);
        assertThat(frameOf(instagramAssets.get(0)).getPlacementKey()).isEqualTo("4x5");
        assertThat(instagramAssets).extracting(a -> frameOf(a).getSequenceIndex()).containsExactly(0, 1, 2);
        assertThat(assetsOf(targetOf(post, "tiktok"))).extracting(a -> frameOf(a).getPlacementKey())
                .containsOnly("9x16");
    }

    @Test
    void aCarouselWithATrackKeepsTheVideoAndPhotosChoicesAndMovesTheVideoToo() {
        // The Creative gets a music track; render it the way the local job does (slides, the mp4, complete).
        CreativePhoto track = trackMedia();
        Creative c = creativeRepository.findById(creative.getId()).orElseThrow();
        c.setAudio(objectMapper.createObjectNode().put("source", "track").put("trackId", track.getId())
                .put("volume", 1.0).put("fadeOutSec", 1.0));
        creativeRepository.save(c);
        CreativeRender withVideo1 = renderWithVideo();
        // TikTok takes the video by default, Instagram and Facebook the slides.
        WorkItem trackPost = emptyPost("Track post");
        attachService.attach(project.getId(), creative.getId(), withVideo1.getId(), trackPost.getId(), admin);
        PostPublishTarget tiktokBefore = targetOf(trackPost, "tiktok");
        assertThat(assetsOf(tiktokBefore)).singleElement().satisfies(a -> assertThat(a.getContentType()).isEqualTo("video/mp4"));
        assertThat(optionOf(tiktokBefore, "carouselAs")).isEqualTo("video");
        assertThat(optionOf(targetOf(trackPost, "instagram"), "carouselAs")).isEqualTo("photos");

        // The next version: slides and the video are re-rendered; the event arrives for that render.
        CreativeRender withVideo2 = renderWithVideo();
        CreativeRevision revision = manualRevision(withVideo2, CreativeRevision.SOURCE_EDITOR, null, null);
        CreativeEditPropagator.Result result = propagator.propagate(rendered(revision, withVideo2));

        assertThat(result.moved()).containsExactly(trackPost.getId());
        PostPublishTarget tiktok = targetOf(trackPost, "tiktok");
        assertThat(optionOf(tiktok, "carouselAs")).isEqualTo("video");
        assertThat(assetsOf(tiktok)).singleElement().satisfies(a -> {
            assertThat(a.getContentType()).isEqualTo("video/mp4");
            assertThat(frameOf(a).getRenderId()).isEqualTo(withVideo2.getId());
        });
        assertThat(optionOf(targetOf(trackPost, "instagram"), "carouselAs")).isEqualTo("photos");
        assertThat(assetsOf(targetOf(trackPost, "instagram"))).hasSize(3)
                .allSatisfy(a -> assertThat(frameOf(a).getRenderId()).isEqualTo(withVideo2.getId()));
        // The old render's video asset is gone from the Post, the new one is its only video.
        assertThat(assetRepository.findAllByWorkItemId(trackPost.getId()).stream()
                .filter(a -> "video/mp4".equals(a.getContentType()))).hasSize(1);
    }

    @Test
    void aSecondEventForTheSameRevisionChangesNothing() {
        WorkItem inReview = post("Idempotent");
        forceStatus(inReview, "IN_REVIEW");
        CreativeRevision revision = editAndApply("First", "Once only");
        CreativeRender render2 = renderRepository.findById(revision.getRenderId()).orElseThrow();
        assertThat(reload(inReview).getCurrentReviewRound()).isEqualTo(1);
        String hash = bundleHasher.hash(reload(inReview));
        List<String> assetIds = assetRepository.findAllByWorkItemId(inReview.getId()).stream().map(Asset::getId).sorted().toList();

        CreativeEditPropagator.Result again = propagator.propagate(rendered(revision, render2));

        assertThat(again.moved()).isEmpty();
        assertThat(again.failed()).isEmpty();
        assertThat(reload(inReview).getCurrentReviewRound()).isEqualTo(1);
        assertThat(bundleHasher.hash(reload(inReview))).isEqualTo(hash);
        assertThat(assetRepository.findAllByWorkItemId(inReview.getId()).stream().map(Asset::getId).sorted().toList())
                .isEqualTo(assetIds);
        assertThat(changeRepository.findAllByWorkItemIdOrderByCreatedAtDescIdDesc(inReview.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void onePostFailingDoesNotStopTheOthersAndLeavesItselfUntouched() {
        WorkItem broken = post("Broken");
        WorkItem fine = post("Fine");
        forceStatus(broken, "IN_REVIEW");
        forceStatus(fine, "IN_REVIEW");
        // The broken Post holds a frame of the old render that the new render has no counterpart for.
        CreativeRenderFrame orphanFrame = newFrame(render1, "story", 0);
        Asset orphan = new Asset();
        orphan.setWorkItem(reload(broken));
        orphan.setType(assetRepository.findAllByWorkItemId(broken.getId()).get(0).getType());
        orphan.setLabel("Old story frame");
        orphan.setKind(AssetService.KIND_FILE);
        orphan.setRef(orphanFrame.getGcsPath());
        orphan.setGcsPath(orphanFrame.getGcsPath());
        orphan.setContentType("image/jpeg");
        orphan.setSizeBytes(4L);
        orphan.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        orphan.setDone(true);
        orphan.setCreativeFrameId(orphanFrame.getId());
        assetRepository.saveAndFlush(orphan);
        String brokenHash = bundleHasher.hash(reload(broken));

        CreativeRevision revision = editAndApply("First", "Fixed");

        assertThat(reload(fine).getCurrentReviewRound()).isEqualTo(1);
        assertThat(framesOf(fine)).allSatisfy(f -> assertThat(f.getRenderId()).isEqualTo(revision.getRenderId()));
        // Rolled back whole: same round, same assets, same bundle, no record.
        assertThat(reload(broken).getCurrentReviewRound()).isZero();
        assertThat(bundleHasher.hash(reload(broken))).isEqualTo(brokenHash);
        assertThat(framesOf(broken)).filteredOn(f -> f.getRenderId().equals(revision.getRenderId())).isEmpty();
        assertThat(changeRepository.existsByWorkItemIdAndRevisionId(broken.getId(), revision.getId())).isFalse();
        assertThat(changeRepository.existsByWorkItemIdAndRevisionId(fine.getId(), revision.getId())).isTrue();
    }

    @Test
    void theFreezeBypassIsNotReachableThroughThePublicAttach() {
        WorkItem inReview = post("Frozen");
        forceStatus(inReview, "IN_REVIEW");
        CreativeRevision revision = editAndApply("First", "Moved by the edit");
        CreativeRender render2 = renderRepository.findById(revision.getRenderId()).orElseThrow();
        // Any other render attached by hand is refused exactly as before: the frozen Post stays put.
        Creative current = creativeRepository.findById(creative.getId()).orElseThrow();
        CreativeRender render3 = renderService.renderCarouselForRevision(project.getId(), current, admin.getId());
        assertThatThrownBy(() -> attachService.attach(project.getId(), creative.getId(), render3.getId(),
                inReview.getId(), admin)).isInstanceOf(BusinessException.class).hasMessageContaining("locked");
        assertThatThrownBy(() -> attachService.attach(project.getId(), creative.getId(), render1.getId(),
                inReview.getId(), admin)).isInstanceOf(BusinessException.class).hasMessageContaining("locked");
        assertThatThrownBy(() -> publishTargetService.replaceSelection(project.getId(), inReview.getId(), List.of(
                new PublishTargetService.TargetSelection("facebook", metaConnectionId)), admin))
                .isInstanceOf(BusinessException.class);
        forceStatus(inReview, "APPROVED");
        assertThatThrownBy(() -> attachService.attach(project.getId(), creative.getId(), render3.getId(),
                inReview.getId(), admin)).isInstanceOf(BusinessException.class);
        assertThat(framesOf(inReview)).allSatisfy(f -> assertThat(f.getRenderId()).isEqualTo(render2.getId()));
    }

    // ── the reviewer fix ────────────────────────────────────────────────────

    @Test
    void aReviewerOnlyMemberFixesTextFromThePostAndItStaysInTheSameRound() {
        WorkItem fixPost = post("Fix this one");
        WorkItem otherInReview = post("Another in review");
        WorkItem draft = post("Still a draft");
        forceStatus(fixPost, "IN_REVIEW");
        forceStatus(otherInReview, "IN_REVIEW");
        assignReviewer(fixPost, reviewer);
        assignReviewer(fixPost, otherReviewer);
        approve(fixPost, otherReviewer);
        assertThat(standingApprovals(fixPost)).isEqualTo(1);
        // Both moved to round 2 so "same round" is not just round zero.
        bumpRound(fixPost, 2);
        bumpRound(otherInReview, 2);
        approve(fixPost, otherReviewer);
        assertThat(standingApprovals(fixPost)).isEqualTo(1);

        CreativeRevisionService.EditResult edit = revisionService.edit(project.getId(), creative.getId(),
                currentVersion(), List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "Typo fixed")), "EDITOR",
                reviewer, fixPost.getId());
        CreativeRevision pending = edit.revision();
        assertThat(pending.getSource()).isEqualTo("REVIEWER");
        assertThat(pending.getPostId()).isEqualTo(fixPost.getId());
        assertThat(pending.getReviewRound()).isEqualTo(2);
        applyPending(pending);

        // The fixed Post: same status, same round, a record with equal rounds from the reviewer.
        assertThat(reload(fixPost).getCurrentStatus()).isEqualTo("IN_REVIEW");
        assertThat(reload(fixPost).getCurrentReviewRound()).isEqualTo(2);
        assertThat(change(fixPost)).satisfies(c -> {
            assertThat(c.getSource()).isEqualTo("REVIEWER");
            assertThat(c.getReviewRoundBefore()).isEqualTo(2);
            assertThat(c.getReviewRoundAfter()).isEqualTo(2);
            assertThat(c.getActorUserId()).isEqualTo(reviewer.getId());
        });
        // The approval cast in this round was bound to the old bundle hash: it no longer stands.
        assertThat(standingApprovals(fixPost)).isZero();
        // Another Post using the Creative gets the normal treatment: a new round.
        assertThat(reload(otherInReview).getCurrentReviewRound()).isEqualTo(3);
        assertThat(change(otherInReview).getReviewRoundAfter()).isEqualTo(3);
        assertThat(reload(draft).getCurrentStatus()).isEqualTo("DRAFT");

        // The reviewer's next approval binds the new bundle hash and stands.
        Review approval = reviewService.submitReview(project.getId(), fixPost.getId(), "APPROVED", "now it reads right", reviewer);
        assertThat(approval.getBundleHash()).isEqualTo(bundleHasher.hash(reload(fixPost)));
        assertThat(approval.getReviewRound()).isEqualTo(2);
        assertThat(standingApprovals(fixPost)).isEqualTo(1);
    }

    @Test
    void thePostNamedByTheFixMustUseTheCreativeBeInReviewAndHaveTheCallerAsReviewerOrEditor() {
        WorkItem inReview = post("In review");
        WorkItem draft = post("Draft");
        forceStatus(inReview, "IN_REVIEW");
        assignReviewer(inReview, reviewer);
        List<SlideshowTextFields.Edit> edits = List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "x"));

        // A Post with no frame of this Creative.
        WorkItem unrelated = workItemService.createWorkItem(project.getId(), "POST", "Unrelated", "c", "MARKETING", admin);
        forceStatus(unrelated, "IN_REVIEW");
        assignReviewer(unrelated, reviewer);
        assertThat(violationRules(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", reviewer, unrelated.getId()))).containsExactly("slideTextEditPostMismatch");

        // A Post that is not in review any more.
        assignReviewer(draft, reviewer);
        assertThatThrownBy(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", reviewer, draft.getId()))
                .isInstanceOfSatisfying(CreativeRenderDispatchException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("slideTextEditPostNotInReview");
                });

        // A reviewer-role member who is not a reviewer of THAT Post.
        assertThatThrownBy(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", otherReviewer, inReview.getId())).isInstanceOf(ForbiddenException.class);
        // And a reviewer without a Post is still refused: the old rule for the plain edit.
        assertThatThrownBy(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", reviewer)).isInstanceOf(ForbiddenException.class);
        // A non-member and an unknown Post are 404s.
        assertThatThrownBy(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", newUser("Outsider"), inReview.getId())).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", admin, "no-such-post")).isInstanceOf(EntityNotFoundException.class);
        assertThat(revisionRepository.findFirstByCreativeIdAndStatus(creative.getId(), "PENDING")).isEmpty();

        // An editor who is not one of the Post's reviewers may pass the Post too: not a REVIEWER fix.
        CreativeRevision byEditor = revisionService.edit(project.getId(), creative.getId(), currentVersion(), edits,
                "EDITOR", admin, inReview.getId()).revision();
        assertThat(byEditor.getSource()).isEqualTo("EDITOR");
        assertThat(byEditor.getPostId()).isEqualTo(inReview.getId());
        applyPending(byEditor);
        assertThat(reload(inReview).getCurrentReviewRound()).isEqualTo(1);
    }

    @Test
    void aFixWhoseRoundHasMovedOnIsTreatedAsAnOrdinaryEdit() {
        WorkItem post = post("Round moved");
        forceStatus(post, "IN_REVIEW");
        assignReviewer(post, reviewer);
        CreativeRevision pending = revisionService.edit(project.getId(), creative.getId(), currentVersion(),
                List.of(new SlideshowTextFields.Edit(1, "texts[0].t", "Fix")), "EDITOR", reviewer, post.getId()).revision();
        // The review was reopened (changes requested and resubmitted) while the slides rendered.
        bumpRound(post, 1);

        applyPending(pending);

        assertThat(reload(post).getCurrentReviewRound()).isEqualTo(2);
        assertThat(change(post).getReviewRoundBefore()).isEqualTo(1);
        assertThat(change(post).getReviewRoundAfter()).isEqualTo(2);
    }

    // ── the read APIs ───────────────────────────────────────────────────────

    @Test
    void affectedPostsNameTheOutcomeForEachStatusAndMarkTheReviewerFix() {
        WorkItem draft = post("Draft");
        WorkItem inReview = post("In review");
        WorkItem fixing = post("Fixing");
        WorkItem approved = post("Approved");
        WorkItem scheduled = post("Scheduled");
        WorkItem published = post("Published");
        forceStatus(inReview, "IN_REVIEW");
        forceStatus(fixing, "IN_REVIEW");
        forceStatus(approved, "APPROVED");
        forceStatus(scheduled, "SCHEDULED");
        forceStatus(published, "PUBLISHED");

        Map<String, CreativeEditReadService.AffectedPost> byId = new LinkedHashMap<>();
        readService.affectedPosts(project.getId(), creative.getId(), fixing.getId(), admin)
                .forEach(p -> byId.put(p.workItemId(), p));

        assertThat(byId).hasSize(6);
        assertThat(byId.get(draft.getId()).outcome()).isEqualTo("updated");
        assertThat(byId.get(inReview.getId()).outcome()).isEqualTo("new-round");
        assertThat(byId.get(fixing.getId()).outcome()).isEqualTo("reviewer-fix");
        assertThat(byId.get(approved.getId()).outcome()).isEqualTo("back-to-review");
        assertThat(byId.get(scheduled.getId()).outcome()).isEqualTo("back-to-review");
        assertThat(byId.get(published.getId()).outcome()).isEqualTo("unchanged");
        assertThat(byId.get(inReview.getId()).statusLabel()).isEqualTo("In Review");
        assertThat(byId.get(inReview.getId()).displayId()).startsWith(project.getKey() + "-");
        assertThat(byId.get(inReview.getId()).title()).isEqualTo("In review");
        // The Post named by postId only counts when it is in review: for the others it reads as usual.
        assertThat(readService.affectedPosts(project.getId(), creative.getId(), approved.getId(), admin).stream()
                .filter(p -> p.workItemId().equals(approved.getId())).findFirst().orElseThrow().outcome())
                .isEqualTo("back-to-review");
        // Members only.
        assertThatThrownBy(() -> readService.affectedPosts(project.getId(), creative.getId(), null, newUser("Outsider")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void contentChangesListNewestFirstWithNamesAndAreCappedAtFifty() {
        WorkItem post = post("History");
        forceStatus(post, "IN_REVIEW");
        CreativeRevision first = editAndApply("First", "One");
        CreativeRevision second = editAndApply("Second", "Two");

        List<CreativeEditReadService.ChangeView> changes = readService.contentChanges(project.getId(), post.getId(), reviewer);

        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).change().getRevisionId()).isEqualTo(second.getId());
        assertThat(changes.get(1).change().getRevisionId()).isEqualTo(first.getId());
        assertThat(changes.get(0).revisionNo()).isEqualTo(second.getRevisionNo());
        assertThat(changes.get(0).creativeDisplayId()).isEqualTo(creative.displayId());
        assertThat(changes.get(0).actorName()).isEqualTo("Slide Editor");
        assertThat(changes.get(0).change().getReviewRoundAfter()).isEqualTo(2);

        // The cap.
        for (int i = 0; i < 52; i++) {
            PostContentChange extra = new PostContentChange();
            extra.setProjectId(project.getId());
            extra.setWorkItemId(post.getId());
            extra.setCreativeId(creative.getId());
            extra.setRevisionId(manualRevisionRow().getId());
            extra.setSource("EDITOR");
            extra.setReviewRoundBefore(0);
            extra.setReviewRoundAfter(0);
            extra.setTextDiff(objectMapper.createArrayNode());
            changeRepository.save(extra);
        }
        assertThat(readService.contentChanges(project.getId(), post.getId(), admin)).hasSize(50);

        assertThatThrownBy(() -> readService.contentChanges(project.getId(), post.getId(), newUser("Outsider")))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> readService.contentChanges(project.getId(), "no-such-post", admin))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void renderAvailabilityFallsBackToTheDaemonWithoutADesignatedTarget() {
        CreativeEditReadService.RenderAvailability availability =
                readService.renderAvailability(project.getId(), creative.getId(), reviewer);

        assertThat(availability.runtime()).isEqualTo("self-hosted");
        assertThat(availability.targetName()).isNull();
        assertThat(availability.daemonOnline()).isNull();
        assertThatThrownBy(() -> readService.renderAvailability(project.getId(), "no-such", admin))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> readService.renderAvailability(project.getId(), creative.getId(), newUser("Outsider")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void theRevisionListCarriesTheAuthorsName() {
        editAndApply("First", "Named");

        List<CreativeRevision> revisions = revisionService.listSettled(project.getId(), creative.getId(), reviewer, 50);

        assertThat(revisions.get(0).getAuthorName()).isEqualTo("Slide Editor");
        assertThat(revisions).allSatisfy(r -> assertThat(r.getAuthorName()).isEqualTo("Slide Editor"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** A CAROUSEL with a 9x16 and a 4x5 list of {@code count} slides and a spec whose slide i says First, Second, .... */
    private Creative slideshow(int count) {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("9x16", images(count, 1080, 1920));
        lists.put("4x5", images(count, 1080, 1350));
        String[] words = {"First", "Second", "Third", "Fourth"};
        List<Object> slideSpecs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> slide = new LinkedHashMap<>();
            slide.put("type", "photo");
            slide.put("bg", "media:" + lists.get("9x16").get(i));
            slide.put("texts", List.of(new LinkedHashMap<>(Map.of("t", words[i], "y", 40))));
            slideSpecs.add(slide);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "spec-" + UUID.randomUUID().toString().substring(0, 8));
        spec.put("slides", slideSpecs);
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CAROUSEL);
        request.setCaption("Seven ways to save recipes.");
        request.setAltText("Recipe cards.");
        List<String> alts = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            alts.add("Card " + i);
        }
        request.setSlideAltText(alts);
        request.setSlides(lists);
        request.setSlideshowSpec(spec);
        return creativeService.createCreative(project.getId(), request, admin).creative();
    }

    /** A Post on all three destinations, with {@link #render1} attached (every destination then has its own slides). */
    private WorkItem post(String title) {
        WorkItem post = emptyPost(title);
        attachService.attach(project.getId(), creative.getId(), render1.getId(), post.getId(), admin);
        return reload(post);
    }

    /** A Post on all three destinations that inherit (no Creative attached yet). */
    private WorkItem emptyPost(String title) {
        WorkItem post = workItemService.createWorkItem(project.getId(), "POST", title, "Caption for " + title, "MARKETING", admin);
        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("facebook", metaConnectionId),
                new PublishTargetService.TargetSelection("instagram", metaConnectionId),
                new PublishTargetService.TargetSelection("tiktok", tiktokConnectionId)), admin);
        return reload(post);
    }

    /** Edits one slide's first text, completes the render job's answer and returns the applied revision. */
    private CreativeRevision editAndApply(String oldText, String newText) {
        Creative current = creativeRepository.findById(creative.getId()).orElseThrow();
        int slide = oldText.equals("First") ? 1 : oldText.equals("Second") ? 2 : 3;
        CreativeRevision pending = revisionService.edit(project.getId(), creative.getId(), current.getVersion(),
                List.of(new SlideshowTextFields.Edit(slide, "texts[0].t", newText)), "EDITOR", admin).revision();
        return applyPending(pending);
    }

    /** The render job's answer for a pending revision: a new slide image per ratio for each changed slide. */
    private CreativeRevision applyPending(CreativeRevision pending) {
        Map<String, Map<String, String>> slideMedia = new LinkedHashMap<>();
        for (int slide : CreativeRevisionService.changedSlides(pending)) {
            slideMedia.computeIfAbsent("9x16", k -> new LinkedHashMap<>()).put(String.valueOf(slide), photo(1080, 1920).getId());
            slideMedia.computeIfAbsent("4x5", k -> new LinkedHashMap<>()).put(String.valueOf(slide), photo(1080, 1350).getId());
        }
        CompleteCreativeRevisionRequest request = new CompleteCreativeRevisionRequest();
        request.setSlideMedia(slideMedia);
        CreativeRevision applied = revisionService.complete(project.getId(), creative.getId(), pending.getId(), request,
                new RenderCaller.UserCaller(admin));
        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(applied.getRenderId()).isNotNull();
        // The job's run is over, as it is by the time a person starts the next edit.
        finishRun(pending);
        return applied;
    }

    @Autowired private com.conductor.repository.WorkflowRunRepository runRepository;

    private void finishRun(CreativeRevision revision) {
        if (revision.getWorkflowRunId() == null) {
            return;
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            var run = runRepository.findById(revision.getWorkflowRunId()).orElseThrow();
            if (run.getStatus() != com.conductor.entity.WorkflowRunStatus.SUCCESS) {
                run.setStatus(com.conductor.entity.WorkflowRunStatus.SUCCESS);
                run.setCompletedAt(OffsetDateTime.now());
                runRepository.save(run);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (runRepository.findById(revision.getWorkflowRunId()).orElseThrow().getStatus()
                    == com.conductor.entity.WorkflowRunStatus.SUCCESS) {
                return;
            }
        }
    }

    private int currentVersion() {
        return creativeRepository.findById(creative.getId()).orElseThrow().getVersion();
    }

    private List<String> violationRules(Runnable call) {
        try {
            call.run();
        } catch (CreativeValidationException e) {
            return e.violations().stream().map(CreativeValidationException.Violation::ruleId).toList();
        }
        throw new AssertionError("expected a CreativeValidationException");
    }

    private CreativeRevisionRendered rendered(CreativeRevision revision, CreativeRender render) {
        return new CreativeRevisionRendered(project.getId(), creative.getId(), revision.getId(), render.getId());
    }

    private CreativeRevision manualRevisionRow() {
        CreativeRevision rev = new CreativeRevision();
        rev.setProjectId(project.getId());
        rev.setCreativeId(creative.getId());
        Integer max = revisionRepository.findMaxRevisionNo(creative.getId());
        rev.setRevisionNo((max == null ? 0 : max) + 1);
        rev.setCreativeVersion(currentVersion());
        rev.setStatus("APPLIED");
        rev.setSource("EDITOR");
        rev.setAuthorUserId(admin.getId());
        rev.setTextDiff(objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("slide", 1)
                .put("path", "texts[0].t").put("old", "First").put("new", "Edited")));
        return revisionRepository.saveAndFlush(rev);
    }

    private CreativeRevision manualRevision(CreativeRender render, String source, String postId, Integer round) {
        CreativeRevision rev = manualRevisionRow();
        rev.setSource(source);
        rev.setRenderId(render.getId());
        rev.setPostId(postId);
        rev.setReviewRound(round);
        return revisionRepository.saveAndFlush(rev);
    }

    // ── a render with the companion video ───────────────────────────────────

    private CreativeRender renderWithVideo() {
        CreativeRenderService.CreateRenderResult requested = renderService.requestRender(project.getId(),
                creative.getId(), new com.conductor.generated.v2.model.CreateCreativeRenderRequest(), admin);
        assertThat(requested.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        String renderId = requested.render().getId();
        renderService.putFrame(project.getId(), creative.getId(), renderId, "9x16", null, 1080, 1920,
                ("mp4-" + renderId).getBytes(), "video/mp4", new java.math.BigDecimal("8"), true, admin);
        renderService.completeRender(project.getId(), creative.getId(), renderId, null, admin);
        return renderRepository.findById(renderId).orElseThrow();
    }

    private CreativePhoto trackMedia() {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".mp3");
        p.setContentType("audio/mpeg");
        p.setMediaKind(CreativePhoto.MEDIA_KIND_AUDIO);
        p.setSizeBytes(500_000L);
        p.setDurationSeconds(new java.math.BigDecimal("30"));
        p.setSource("Pixabay");
        p.setLicence("Pixabay licence");
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private CreativeRenderFrame newFrame(CreativeRender render, String placementKey, Integer index) {
        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey(placementKey);
        frame.setSequenceIndex(index);
        frame.setGcsPath("projects/" + project.getId() + "/creatives/" + creative.getId() + "/renders/" + render.getId()
                + "/" + placementKey + "-" + index + ".jpg");
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1920);
        frame.setSizeBytes(4L);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    // ── assertions' helpers ─────────────────────────────────────────────────

    private PostContentChange change(WorkItem post) {
        List<PostContentChange> changes = changeRepository.findAllByWorkItemIdOrderByCreatedAtDescIdDesc(post.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(changes).isNotEmpty();
        return changes.get(0);
    }

    private List<CreativeRenderFrame> framesOf(WorkItem post) {
        return assetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(a -> a.getCreativeFrameId() != null)
                .map(a -> frameRepository.findById(a.getCreativeFrameId()).orElseThrow()).toList();
    }

    private CreativeRenderFrame frameOf(Asset asset) {
        return frameRepository.findById(asset.getCreativeFrameId()).orElseThrow();
    }

    private List<Asset> assetsOf(PostPublishTarget target) {
        return targetAssetRepository.findAllByTargetId(target.getId()).stream()
                .map(PostPublishTargetAsset::getAssetId).map(id -> assetRepository.findById(id).orElseThrow()).toList();
    }

    /** Per destination, the slots its selection holds in order ("placement|index"), whichever render they come from. */
    private List<List<String>> selectionKeys(WorkItem post) {
        List<List<String>> out = new ArrayList<>();
        targetRepository.findAllByWorkItemId(post.getId()).stream()
                .sorted(java.util.Comparator.comparing(PostPublishTarget::getPlatform))
                .forEach(t -> out.add(assetsOf(t).stream()
                        .map(a -> t.getPlatform() + ":" + frameOf(a).getPlacementKey() + "|" + frameOf(a).getSequenceIndex())
                        .toList()));
        return out;
    }

    private PostPublishTarget targetOf(WorkItem post, String platform) {
        return targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> platform.equals(t.getPlatform())).findFirst().orElseThrow();
    }

    private String optionOf(PostPublishTarget target, String key) {
        try {
            var node = objectMapper.readTree(target.getPublishOptions() == null ? "{}" : target.getPublishOptions()).get(key);
            return node == null ? null : node.asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handOffFacebook(WorkItem post) {
        PostPublishTarget target = targetOf(post, "facebook");
        target.setState(PostPublishTargetState.HANDED_OFF);
        target.setPlatformPostId("page_1_post_99");
        targetRepository.saveAndFlush(target);
    }

    private long standingApprovals(WorkItem post) {
        return reviewService.listReviews(project.getId(), post.getId(), admin).stream()
                .filter(r -> "APPROVED".equals(r.verdict())).filter(ReviewWithUser::current).count();
    }

    /** An approval cast against the bundle and round as they stand now. */
    private void approve(WorkItem post, User by) {
        WorkItem current = reload(post);
        Review review = reviewRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(r -> by.getId().equals(r.getReviewerId())).findFirst().orElseGet(Review::new);
        review.setWorkItemId(post.getId());
        review.setReviewerId(by.getId());
        review.setVerdict("APPROVED");
        review.setSubmittedAt(OffsetDateTime.now());
        review.setReviewRound(current.getCurrentReviewRound());
        review.setBundleHash(bundleHasher.hash(current));
        reviewRepository.saveAndFlush(review);
    }

    private void bumpRound(WorkItem post, int round) {
        WorkItem stored = reload(post);
        stored.setCurrentReviewRound(round);
        workItemRepository.saveAndFlush(stored);
    }

    private void forceStatus(WorkItem post, String status) {
        WorkItem stored = reload(post);
        stored.setCurrentStatus(status);
        workItemRepository.saveAndFlush(stored);
    }

    private void assignReviewer(WorkItem post, User user) {
        WorkItemReviewer assignment = new WorkItemReviewer();
        assignment.setWorkItemId(post.getId());
        assignment.setUserId(user.getId());
        assignment.setAssignedBy(admin.getId());
        workItemReviewerRepository.save(assignment);
    }

    private WorkItem reload(WorkItem post) {
        return workItemRepository.findById(post.getId()).orElseThrow();
    }

    private List<String> images(int count, int width, int height) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(photo(width, height).getId());
        }
        return ids;
    }

    private CreativePhoto photo(int width, int height) {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".png");
        p.setContentType("image/png");
        p.setSizeBytes(1000L);
        p.setWidth(width);
        p.setHeight(height);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private Connection connection(String connectorId, String configJson) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson(configJson);
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connection;
    }

    private void member(User user, MemberRole role) {
        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(user);
        membership.setRole(role);
        projectMemberRepository.save(membership);
    }

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }
}
