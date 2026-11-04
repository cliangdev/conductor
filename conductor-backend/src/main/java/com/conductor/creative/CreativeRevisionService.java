package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CompleteCreativeRevisionRequest;
import com.conductor.generated.v2.model.FailCreativeRevisionRequest;
import com.conductor.generated.v2.model.SlideshowCheck;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.conductor.workflow.WorkflowRunCancellationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Editing the text on a slideshow (CAROUSEL) Creative's slides, and applying the re-rendered result.
 *
 * <p>An edit is a PENDING {@link CreativeRevision} holding the candidate spec; the render Workflow's job
 * ({@code conductor creative render-slides}) re-composes only the changed slides and reports them to
 * {@link #complete}, which — in one transaction — swaps the new media into the Creative's slide lists, stores the
 * candidate spec and bumps the Creative's version. Nothing on the Creative changes before then, so a failed or
 * abandoned render leaves it exactly as it was. See {@code docs/creatives.md}.
 */
@Service
public class CreativeRevisionService {

    private static final Logger log = LoggerFactory.getLogger(CreativeRevisionService.class);

    static final int MAX_EDITS = 50;

    public static final String RULE_SPEC_MISSING = "slideshowSpecMissing";
    public static final String RULE_EDIT_INVALID = "slideTextEditInvalid";
    public static final String RULE_EDIT_NOOP = "slideTextEditNoop";
    public static final String RULE_SLIDE_MEDIA = "slideMediaInvalid";
    /** 422: the Post named by {@code postId} does not use this Creative (or is not in this workspace's Posts). */
    public static final String RULE_POST_MISMATCH = "slideTextEditPostMismatch";
    /** The 409 {@code code} for "the Post named by {@code postId} is not in its review status". */
    public static final String CODE_POST_NOT_IN_REVIEW = "slideTextEditPostNotInReview";
    /** A slide list that is not 9x16 or 4x5 (1x1, or a shape that is neither): the composer cannot re-render it. */
    public static final String RULE_UNSUPPORTED_RATIO = "slideTextEditUnsupportedRatio";
    /**
     * The 409 {@code code} (and {@code ruleId}) for "a reviewer of this Post may not fix text that would send
     * other approved or scheduled Posts back to review: an editor must".
     */
    public static final String RULE_NEEDS_EDITOR = "slideTextEditNeedsEditor";
    /** The 409 {@code code} for "a slide-text revision is still rendering". */
    public static final String CODE_EDIT_ACTIVE = "SLIDE_EDIT_ACTIVE";

    static final String VERSION_MOVED_ON = "The Creative changed while the slides were rendering. Make the edit again.";
    /** A PENDING revision with no workflow run (it never got one) is abandoned after this long. */
    static final long ORPHAN_PENDING_MINUTES = 35;

    private static final List<String> RENDERED_RATIOS = List.of("9x16", "4x5");
    private static final Map<String, Double> RATIO_VALUES = Map.of("9x16", 9.0 / 16.0, "4x5", 4.0 / 5.0, "1x1", 1.0);
    /** Relative aspect tolerance when classifying a list by its images: the same 2% the validator allows. */
    private static final double RATIO_TOLERANCE = CreativeValidator.CAROUSEL_ASPECT_TOLERANCE;
    static final String UNSUPPORTED_1X1 = "Text editing isn't available for Creatives with 1x1 slides yet."
            + " Re-compose it locally with compose_slideshow.";

    private final CreativeRevisionRepository revisionRepository;
    private final CreativeRepository creativeRepository;
    private final CreativePhotoRepository photoRepository;
    private final CreativeRenderService renderService;
    private final CreativeRenderDispatchService dispatchService;
    private final CreativeRevisionRenderLinker renderLinker;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkItemRepository workItemRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final WorkflowDefinitionResolver workflowResolver;
    private final CreativeEditImpacts impacts;
    private final PlatformTransactionManager transactionManager;
    private final CreativePhotoService photoService;
    private final TransactionTemplate newTx;

    public CreativeRevisionService(CreativeRevisionRepository revisionRepository,
                                   CreativeRepository creativeRepository,
                                   CreativePhotoRepository photoRepository,
                                   CreativeRenderService renderService,
                                   CreativeRenderDispatchService dispatchService,
                                   CreativeRevisionRenderLinker renderLinker,
                                   ProjectSecurityService projectSecurityService,
                                   ObjectMapper objectMapper,
                                   WorkflowRunCancellationService cancellationService,
                                   WorkItemRepository workItemRepository,
                                   AssetRepository assetRepository,
                                   UserRepository userRepository,
                                   WorkflowDefinitionResolver workflowResolver,
                                   CreativeEditImpacts impacts,
                                   PlatformTransactionManager transactionManager,
                                   CreativePhotoService photoService) {
        this.photoService = photoService;
        this.impacts = impacts;
        this.transactionManager = transactionManager;
        this.cancellationService = cancellationService;
        this.workItemRepository = workItemRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.workflowResolver = workflowResolver;
        this.revisionRepository = revisionRepository;
        this.creativeRepository = creativeRepository;
        this.photoRepository = photoRepository;
        this.renderService = renderService;
        this.dispatchService = dispatchService;
        this.renderLinker = renderLinker;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The result of an accepted edit: the PENDING revision and the run that will render it. */
    public record EditResult(CreativeRevision revision, CreativeRenderDispatchService.RenderRunView renderRun) {
    }

    /** A revision plus the parts of the Creative the render job needs next to it. */
    public record RevisionDetail(CreativeRevision revision, List<Integer> changedSlides, JsonNode slides,
                                 JsonNode slideStyle, List<RenderList> renderLists,
                                 List<CreativePhotoService.PhotoView> media) {
    }

    /** A stored slide list the render job re-renders, and the ratio ({@code 9x16} / {@code 4x5}) it is made at. */
    public record RenderList(String list, String ratio) {
    }

    /**
     * Which of a Creative's lists are re-rendered, or why it cannot be: {@code unsupported} is the user-facing
     * reason (set when any non-empty list is 1x1 or any other shape the composer does not make).
     */
    record RenderPlan(List<RenderList> lists, String unsupported) {
    }

    // ── the edit ────────────────────────────────────────────────────────────

    /** {@link #edit(String, String, Integer, List, String, User, String)} with no Post: an editor's edit. */
    @Transactional
    public EditResult edit(String projectId, String creativeId, Integer version,
                           List<SlideshowTextFields.Edit> edits, String source, User caller) {
        return edit(projectId, creativeId, version, edits, source, caller, null);
    }

    /**
     * Records a PENDING revision of {@code creativeId}'s slideshow spec with {@code edits} applied and dispatches
     * the render of its changed slides. The checks run in the order the API documents them.
     *
     * <p><b>Reviewer fix.</b> With a {@code postId} the caller may be an editor <em>or</em> a reviewer of that Post
     * (even one whose project role is REVIEWER): the Post must use this Creative (else 422
     * {@value #RULE_POST_MISMATCH}) and be in its review status (else 409 {@value #CODE_POST_NOT_IN_REVIEW}). A
     * reviewer of the Post makes the revision {@code REVIEWER}, stamped with the Post and its current review round,
     * which is what lets {@link CreativeEditPropagator} keep that Post in the same round. An editor who is not
     * one of the Post's reviewers keeps {@code source}, with the Post and round recorded for the history.
     *
     * @param source {@link CreativeRevision#SOURCE_EDITOR} or {@link CreativeRevision#SOURCE_MCP}
     * @param postId the Post the edit is made from, or null
     */
    @Transactional
    public EditResult edit(String projectId, String creativeId, Integer version,
                           List<SlideshowTextFields.Edit> edits, String source, User caller, String postId) {
        boolean reviewerOfPost = false;
        boolean editor = true;
        WorkItem post = null;
        if (postId == null) {
            requireEditor(projectId, caller);
        } else {
            requireMember(projectId, caller);
            post = workItemRepository.findById(postId)
                    .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                    .orElseThrow(() -> new EntityNotFoundException("Post not found"));
            reviewerOfPost = impacts.isReviewerOf(postId, caller);
            editor = projectSecurityService.isEditor(projectId, caller.getId());
            if (!reviewerOfPost && !editor) {
                throw new ForbiddenException("Only ADMIN or CREATOR, or a reviewer of this Post, can edit its slide text");
            }
        }
        // The row lock serializes two edits (and an edit against a patch or a render dispatch).
        Creative creative = creativeRepository.findByIdAndProjectIdForUpdate(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        JsonNode spec = creative.getSlideshowSpec();
        if (!Creative.KIND_CAROUSEL.equals(creative.getKind()) || spec == null || spec.isNull()) {
            throw violation("slideshowSpec", RULE_SPEC_MISSING,
                    "This Creative has no stored slideshow spec, so its slide text cannot be edited."
                            + " Attach the spec it was made from first.");
        }
        RenderPlan plan = planRenderLists(toSlidesMap(creative.getSlides()));
        if (plan.unsupported() != null) {
            throw violation("slides", RULE_UNSUPPORTED_RATIO, plan.unsupported());
        }
        if (post != null) {
            requirePostInReviewUsing(projectId, creative, post);
            // The same computation affected-posts announces (as blockedReason), so the two cannot disagree.
            String blocked = impacts.blockedReason(impacts.forCreative(projectId, creativeId), post.getId(),
                    editor, reviewerOfPost);
            if (blocked != null) {
                throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, RULE_NEEDS_EDITOR, blocked,
                        Map.of("ruleId", RULE_NEEDS_EDITOR, "postId", post.getId()));
            }
        }
        if (version == null || version != creative.getVersion()) {
            throw new ConflictException("This Creative changed since you read it (current version "
                    + creative.getVersion() + ", expected " + version + ")");
        }
        Optional<CreativeRevision> pending = revisionRepository.findFirstByCreativeIdAndStatus(creativeId,
                CreativeRevision.STATUS_PENDING);
        if (pending.isPresent() && !resolveStale(pending.get())) {
            throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, CODE_EDIT_ACTIVE,
                    "Another text edit of this Creative is still being rendered.",
                    Map.of("revisionId", pending.get().getId()));
        }

        JsonNode candidate = applyEdits(spec, edits);
        ArrayNode diff = SlideshowTextFields.diffJson(spec, candidate);
        if (diff.isEmpty()) {
            throw violation("edits", RULE_EDIT_NOOP, "These edits change nothing: the text is already as given.");
        }

        Integer max = revisionRepository.findMaxRevisionNo(creativeId);
        CreativeRevision revision = new CreativeRevision();
        revision.setProjectId(projectId);
        revision.setCreativeId(creativeId);
        revision.setRevisionNo((max == null ? 0 : max) + 1);
        revision.setCreativeVersion(creative.getVersion());
        revision.setBaseVersion(creative.getVersion());
        revision.setStatus(CreativeRevision.STATUS_PENDING);
        revision.setSource(reviewerOfPost ? CreativeRevision.SOURCE_REVIEWER : source);
        revision.setSpec(candidate);
        revision.setTextDiff(diff);
        revision.setAuthorUserId(caller.getId());
        if (post != null) {
            revision.setPostId(post.getId());
            revision.setReviewRound(post.getCurrentReviewRound());
        }
        revision = revisionRepository.saveAndFlush(revision);

        CreativeRenderDispatchService.RenderRunView run =
                dispatchService.dispatchSlideTextEdit(projectId, creativeId, revision.getId());
        revision.setWorkflowRunId(run.runId());
        revision = revisionRepository.save(revision);
        return new EditResult(revision, run);
    }

    /**
     * The Post an edit is made from must use this Creative (one of its frames is on the Post) and be in its
     * Workflow's review status right now: that is the one place a reviewer may fix text.
     */
    private void requirePostInReviewUsing(String projectId, Creative creative, WorkItem post) {
        if (!assetRepository.existsByWorkItemIdUsingCreative(post.getId(), creative.getId())) {
            throw violation("postId", RULE_POST_MISMATCH, "That Post does not use this Creative.");
        }
        String slug = post.getWorkflow() != null ? post.getWorkflow() : "ENGINEERING";
        Statechart chart = workflowResolver.resolveRequired(projectId, slug, post.getWorkflowVersion());
        boolean inReview = com.conductor.service.AssetUploadPolicy.reviewGate(chart)
                .map(com.conductor.workflow.lifecycle.StatechartTransition::from)
                .filter(post.getCurrentStatus()::equals).isPresent();
        if (!inReview) {
            throw new CreativeRenderDispatchException(HttpStatus.CONFLICT, CODE_POST_NOT_IN_REVIEW,
                    "That Post is not in review any more, so its text cannot be fixed from the review.",
                    Map.of("postId", post.getId()));
        }
    }

    /** Every edit checked on its own (so each bad one is reported, by index), then all applied in order. */
    private JsonNode applyEdits(JsonNode spec, List<SlideshowTextFields.Edit> edits) {
        List<CreativeValidationException.Violation> problems = new ArrayList<>();
        if (edits == null || edits.isEmpty() || edits.size() > MAX_EDITS) {
            problems.add(new CreativeValidationException.Violation("edits", RULE_EDIT_INVALID,
                    "Send between 1 and " + MAX_EDITS + " edits"));
        } else {
            for (int i = 0; i < edits.size(); i++) {
                try {
                    SlideshowTextFields.apply(spec, List.of(edits.get(i)));
                } catch (SlideshowTextFields.EditException e) {
                    problems.add(new CreativeValidationException.Violation("edits[" + i + "]", RULE_EDIT_INVALID,
                            e.getMessage()));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new CreativeValidationException(problems);
        }
        return SlideshowTextFields.apply(spec, edits);
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /**
     * One revision with what the render job needs. A person must be a member; a run token only for the revision
     * its own run was dispatched for.
     */
    @Transactional
    public RevisionDetail getDetail(String projectId, String creativeId, String revisionId, RenderCaller caller) {
        Creative creative = findCreative(projectId, creativeId);
        CreativeRevision revision = findRevision(creativeId, revisionId);
        authorize(projectId, creativeId, revision, caller, false);
        resolveStale(revision);
        List<Integer> changed = changedSlides(revision);
        List<RenderList> lists = planRenderLists(toSlidesMap(creative.getSlides())).lists();
        List<CreativePhotoService.PhotoView> media = revision.getStatus().equals(CreativeRevision.STATUS_PENDING)
                ? photoService.viewsOf(projectId, mediaTheJobReads(revision, creative, changed, lists)) : List.of();
        return new RevisionDetail(revision, changed, creative.getSlides(), creative.getSlideStyle(), lists, media);
    }

    /**
     * The Media a render job reads for a revision: the images the changed slides of the candidate spec use, and
     * the entries of the re-rendered lists at the changed positions (the slides being replaced, for provenance).
     * Nothing else: a run token reads this much and no more.
     */
    private Set<String> mediaTheJobReads(CreativeRevision revision, Creative creative, List<Integer> changed,
                                         List<RenderList> lists) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode specSlides = revision.getSpec() != null ? revision.getSpec().get("slides") : null;
        if (specSlides != null && specSlides.isArray()) {
            com.fasterxml.jackson.databind.node.ObjectNode only = objectMapper.createObjectNode();
            ArrayNode picked = only.putArray("slides");
            for (int n : changed) {
                if (n >= 1 && n <= specSlides.size()) {
                    picked.add(specSlides.get(n - 1));
                }
            }
            for (SlideshowSpecs.ImageRef ref : SlideshowSpecs.imageRefs(only)) {
                String id = SlideshowSpecs.mediaId(ref.value());
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        Map<String, List<String>> slides = toSlidesMap(creative.getSlides());
        for (RenderList list : lists) {
            List<String> entries = slides.get(list.list());
            for (int n : changed) {
                if (entries != null && n >= 1 && n <= entries.size() && entries.get(n - 1) != null) {
                    ids.add(entries.get(n - 1));
                }
            }
        }
        return ids;
    }

    // ── the render job's uploads ────────────────────────────────────────────

    /**
     * Mints an image upload for a re-rendered slide of a PENDING revision. The photo endpoints refuse a run token;
     * this is the narrow door instead: the revision's own run (or an editor), an image, a PENDING revision. A run
     * token's Media is attributed to the revision's author, the person the system is acting for.
     */
    @Transactional
    public CreativePhotoService.PhotoView createMedia(String projectId, String creativeId, String revisionId,
            com.conductor.generated.v2.model.CreateCreativePhotoRequest request, RenderCaller caller) {
        CreativeRevision revision = findRevision(creativeId, revisionId);
        findCreative(projectId, creativeId);
        authorize(projectId, creativeId, revision, caller, true);
        requirePending(revision);
        return photoService.createImageForRevision(projectId, request, uploader(revision, caller));
    }

    /** Confirms an image minted by {@link #createMedia} for this revision. */
    @Transactional
    public CreativePhotoService.PhotoView confirmMedia(String projectId, String creativeId, String revisionId,
            String mediaId, Long sizeBytes, RenderCaller caller) {
        CreativeRevision revision = findRevision(creativeId, revisionId);
        findCreative(projectId, creativeId);
        authorize(projectId, creativeId, revision, caller, true);
        requirePending(revision);
        return photoService.confirmImageForRevision(projectId, mediaId, sizeBytes, uploader(revision, caller),
                revision.getCreatedAt());
    }

    private static String uploader(CreativeRevision revision, RenderCaller caller) {
        return caller instanceof RenderCaller.UserCaller u ? u.user().getId() : revision.getAuthorUserId();
    }

    /** The distinct 1-based slides a revision's text diff touches, ascending. */
    public static List<Integer> changedSlides(CreativeRevision revision) {
        TreeSet<Integer> slides = new TreeSet<>();
        if (revision.getTextDiff() != null) {
            revision.getTextDiff().forEach(n -> slides.add(n.path("slide").asInt()));
        }
        return new ArrayList<>(slides);
    }

    /**
     * The revisions a Creative read reports: its PENDING one (if any, after settling a stale one) and the newest
     * of all. Two indexed single-row reads.
     */
    public record RevisionHeads(CreativeRevision pending, CreativeRevision latest) {
    }

    public RevisionHeads heads(String creativeId) {
        CreativeRevision pending = revisionRepository.findFirstByCreativeIdAndStatus(creativeId,
                CreativeRevision.STATUS_PENDING).orElse(null);
        if (pending != null && resolveStale(pending)) {
            pending = null;
        }
        CreativeRevision latest = revisionRepository.findFirstByCreativeIdOrderByRevisionNoDesc(creativeId).orElse(null);
        return new RevisionHeads(pending, latest);
    }

    /**
     * The newest revisions of a Creative, newest first; a stale PENDING one is settled first so the list never
     * shows a revision as rendering when its run is long gone.
     */
    @Transactional
    public List<CreativeRevision> listSettled(String projectId, String creativeId, User caller, int max) {
        requireMember(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        List<CreativeRevision> revisions = revisionRepository.findAllByCreativeIdOrderByRevisionNoDesc(
                creative.getId(), org.springframework.data.domain.PageRequest.of(0, max));
        revisions.forEach(this::resolveStale);
        // One lookup for every author's name.
        Map<String, String> names = new HashMap<>();
        userRepository.findAllById(revisions.stream().map(CreativeRevision::getAuthorUserId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getName()));
        revisions.forEach(r -> r.setAuthorName(r.getAuthorUserId() == null ? null : names.get(r.getAuthorUserId())));
        return revisions;
    }

    // ── the render job's answer ─────────────────────────────────────────────

    /**
     * The job's result. A check with severity {@code error} FAILS the revision (checks stored, nothing else
     * changes). Otherwise applies it; a revision that cannot be applied (the Creative moved on, the media is not
     * usable, the media does not cover the changed slides) is FAILED with the reason, and the Creative is left
     * untouched. Always returns the revision (200); only a malformed body is a 422.
     */
    public CreativeRevision complete(String projectId, String creativeId, String revisionId,
                                     CompleteCreativeRevisionRequest request, RenderCaller caller) {
        // Not @Transactional: the follow-ups (releasing the run, queueing a video render, carrying the edit to
        // its Posts) each open their own transaction and must start only after this one has released its
        // connection. See AfterTransaction.
        return AfterTransaction.execute(transactionManager,
                followUps -> completeInTransaction(projectId, creativeId, revisionId, request, caller, followUps));
    }

    private CreativeRevision completeInTransaction(String projectId, String creativeId, String revisionId,
                                                   CompleteCreativeRevisionRequest request, RenderCaller caller,
                                                   AfterTransaction.FollowUps followUps) {
        Creative creative = creativeRepository.findByIdAndProjectIdForUpdate(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        CreativeRevision revision = findRevision(creativeId, revisionId);
        authorize(projectId, creativeId, revision, caller, true);
        requirePending(revision);
        releaseUnstartedRun(revision, caller, followUps);

        List<SlideshowCheck> checks = request.getChecks() != null ? request.getChecks() : List.of();
        List<SlideshowCheck> errors = checks.stream()
                .filter(c -> c.getSeverity() == SlideshowCheck.SeverityEnum.ERROR).toList();
        if (!errors.isEmpty()) {
            return failed(revision, "The re-rendered slides failed " + errors.size() + " check"
                    + (errors.size() == 1 ? "" : "s") + ": " + describe(errors), checks);
        }
        Map<String, Map<Integer, String>> slideMedia = parseSlideMedia(request.getSlideMedia());

        if (revision.getBaseVersion() == null || creative.getVersion() != revision.getBaseVersion()
                || creative.getSlideshowSpec() == null) {
            return failed(revision, VERSION_MOVED_ON, checks);
        }

        // Which lists get which ratio's slides: the same plan the render job was given.
        Map<String, List<String>> slides = toSlidesMap(creative.getSlides());
        RenderPlan plan = planRenderLists(slides);
        if (plan.unsupported() != null) {
            return failed(revision, plan.unsupported(), checks);
        }
        Map<String, String> ratioOfList = new LinkedHashMap<>();
        Set<String> ratiosNeeded = new LinkedHashSet<>();
        for (RenderList list : plan.lists()) {
            ratioOfList.put(list.list(), list.ratio());
            ratiosNeeded.add(list.ratio());
        }

        List<Integer> changed = changedSlides(revision);
        String coverage = coverageProblem(slideMedia, ratiosNeeded, changed);
        if (coverage != null) {
            return failed(revision, coverage, checks);
        }
        Set<String> mediaIds = new LinkedHashSet<>();
        slideMedia.values().forEach(bySlide -> mediaIds.addAll(bySlide.values()));
        Map<String, CreativePhoto> photos = new HashMap<>();
        for (CreativePhoto p : photoRepository.findAllByIdIn(mediaIds)) {
            photos.put(p.getId(), p);
        }
        for (String id : mediaIds) {
            CreativePhoto p = photos.get(id);
            if (p == null || !projectId.equals(p.getProjectId())
                    || !CreativePhoto.MEDIA_KIND_IMAGE.equals(p.getMediaKind()) || !p.isUploaded() || p.isBlocked()) {
                return failed(revision, "Media " + id + " is not an uploaded image of this project", checks);
            }
        }

        // Replace the changed entries of every list that gets re-rendered. All of this is computed before anything
        // is written, so a refusal leaves the Creative and the uploaded media exactly as they were.
        Map<String, List<String>> updated = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> list : slides.entrySet()) {
            List<String> entries = new ArrayList<>(list.getValue());
            String ratio = ratioOfList.get(list.getKey());
            if (ratio != null) {
                for (int slide : changed) {
                    if (slide > entries.size()) {
                        return failed(revision, "Slide " + slide + " is past the end of the " + list.getKey()
                                + " list", checks);
                    }
                    entries.set(slide - 1, slideMedia.get(ratio).get(slide));
                }
            }
            updated.put(list.getKey(), entries);
        }

        for (CreativePhoto p : photos.values()) {
            // The composer's own output: nothing in it to check for burned-in text.
            p.setChecked(true);
        }
        photoRepository.saveAll(photos.values());

        creative.setSlides(objectMapper.valueToTree(updated));
        creative.setSlideshowSpec(revision.getSpec());
        creative = creativeRepository.saveAndFlush(creative);

        revision.setStatus(CreativeRevision.STATUS_APPLIED);
        revision.setAppliedAt(OffsetDateTime.now());
        revision.setCreativeVersion(creative.getVersion());
        revision.setSlides(creative.getSlides());
        revision.setSlideAltText(creative.getSlideAltText());
        revision.setSlideStyle(creative.getSlideStyle());
        revision.setAltTextFlags(altTextFlags(revision.getTextDiff(), toStringList(creative.getSlideAltText())));
        ArrayNode stored = objectMapper.valueToTree(checks);
        revision.setChecks(stored.isEmpty() ? null : stored);
        revision.setError(null);
        revision = revisionRepository.save(revision);

        render(revision, creative, followUps);
        return revision;
    }

    /** The job could not produce the slides. */
    public CreativeRevision fail(String projectId, String creativeId, String revisionId,
                                 FailCreativeRevisionRequest request, RenderCaller caller) {
        return AfterTransaction.execute(transactionManager, followUps -> {
            findCreative(projectId, creativeId);
            CreativeRevision revision = findRevision(creativeId, revisionId);
            authorize(projectId, creativeId, revision, caller, true);
            requirePending(revision);
            releaseUnstartedRun(revision, caller, followUps);
            return failed(revision, request.getError(), request.getChecks());
        });
    }

    private CreativeRevision failed(CreativeRevision revision, String error, List<SlideshowCheck> checks) {
        revision.setStatus(CreativeRevision.STATUS_FAILED);
        revision.setError(error);
        revision.setChecks(checks == null || checks.isEmpty() ? null : objectMapper.valueToTree(checks));
        return revisionRepository.save(revision);
    }

    /**
     * Re-renders the applied Creative. Without a music track that is the instant carousel render (SUCCEEDED here,
     * so the revision is linked to it now); with one it queues the normal video render after this transaction
     * commits, and {@link CreativeRenderService#completeRender} links it when that render succeeds. A refusal
     * leaves the apply in place and is noted on the revision.
     */
    private void render(CreativeRevision revision, Creative creative, AfterTransaction.FollowUps followUps) {
        if (!renderService.needsLocalRender(creative)) {
            try {
                CreativeRender render = renderService.renderCarouselForRevision(creative.getProjectId(), creative,
                        revision.getAuthorUserId());
                renderLinker.link(revision, render, followUps);
            } catch (CreativeValidationException | com.conductor.exception.BusinessException e) {
                note(revision, "The slides were updated, but the carousel could not be rendered: " + e.getMessage());
            }
            return;
        }
        String ignoreRunId = revision.getWorkflowRunId();
        String projectId = revision.getProjectId();
        String creativeId = revision.getCreativeId();
        String revisionId = revision.getId();
        followUps.add(() -> {
            try {
                newTx.executeWithoutResult(status ->
                        dispatchService.dispatchRenderAfterEdit(projectId, creativeId, ignoreRunId));
            } catch (RuntimeException e) {
                log.warn("Could not queue the video render after slide-text revision {}", revisionId, e);
                String message = "The slides were updated, but the video render could not be queued: " + e.getMessage();
                newTx.executeWithoutResult(status -> revisionRepository.findById(revisionId).ifPresent(r -> {
                    r.setError(message);
                    revisionRepository.save(r);
                }));
            }
        });
    }

    /**
     * A person who finishes a revision with their own key (no daemon, no runtime target) leaves the run that was
     * dispatched for it waiting for a runner that is no longer needed, and a waiting run blocks the next edit and
     * every Render click for up to two days. Once the answer has committed, cancel that run if nothing has
     * started it; a run that is already going is left to finish (it will find the revision settled).
     */
    private void releaseUnstartedRun(CreativeRevision revision, RenderCaller caller, AfterTransaction.FollowUps followUps) {
        if (!(caller instanceof RenderCaller.UserCaller) || revision.getWorkflowRunId() == null) {
            return;
        }
        String runId = revision.getWorkflowRunId();
        followUps.add(() -> {
            try {
                newTx.executeWithoutResult(status -> dispatchService.findRun(runId)
                        .filter(run -> WorkflowRunStatus.PENDING.name().equals(run.status())
                                || WorkflowRunStatus.PENDING_LOCAL_PICKUP.name().equals(run.status()))
                        .ifPresent(run -> cancellationService.cancelRun(runId)));
            } catch (RuntimeException e) {
                log.warn("Could not release the unstarted render run {}", runId, e);
            }
        });
    }

    private void note(CreativeRevision revision, String message) {
        revision.setError(message);
        revisionRepository.save(revision);
    }

    // ── stale PENDING revisions ─────────────────────────────────────────────

    /**
     * A PENDING revision whose workflow run is over without having completed it (failed, cancelled, never picked
     * up, or finished without reporting) can never finish: it becomes FAILED with the run's reason. Returns whether
     * {@code revision} was settled.
     *
     * <p>This runs in the caller's transaction and never opens one of its own (a second pooled connection while
     * the caller holds one, and possibly a row lock, can starve a small pool). In a writable transaction, or none,
     * the settled state is stored (and flushed, so a new PENDING revision may be inserted right after). In a
     * read-only transaction (the Creative read) nothing can be written: the caller sees the revision as FAILED,
     * and the next edit, revision read or revision list stores it.
     */
    boolean resolveStale(CreativeRevision revision) {
        if (!CreativeRevision.STATUS_PENDING.equals(revision.getStatus())) {
            return true;
        }
        String reason = staleReason(revision);
        if (reason == null) {
            return false;
        }
        revision.setStatus(CreativeRevision.STATUS_FAILED);
        revision.setError(reason);
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            revisionRepository.saveAndFlush(revision);
        }
        return true;
    }

    private String staleReason(CreativeRevision revision) {
        if (revision.getWorkflowRunId() == null) {
            return revision.getCreatedAt() != null
                    && revision.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(ORPHAN_PENDING_MINUTES))
                    ? "The slide render never started. Make the edit again." : null;
        }
        Optional<CreativeRenderDispatchService.RenderRunView> run = dispatchService.findRun(revision.getWorkflowRunId());
        if (run.isEmpty()) {
            return "The render run no longer exists. Make the edit again.";
        }
        String status = run.get().status();
        if (WorkflowRunStatus.FAILED.name().equals(status) || WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT.name().equals(status)) {
            return run.get().error() != null ? run.get().error() : "The render run failed. Open the run for its log.";
        }
        if (WorkflowRunStatus.CANCELLED.name().equals(status)) {
            return "The render run was cancelled.";
        }
        if (WorkflowRunStatus.SUCCESS.name().equals(status)) {
            return "The render job finished without saving its result. Make the edit again.";
        }
        return null;
    }

    // ── alt text ────────────────────────────────────────────────────────────

    /**
     * The 1-based slides whose alt text quotes text this revision replaced: for each diff entry with a non-empty
     * old text, that slide is flagged when its alt text (normalised) contains it (normalised). Always an array.
     */
    static ArrayNode altTextFlags(JsonNode textDiff, List<String> slideAltText) {
        TreeSet<Integer> flagged = new TreeSet<>();
        if (textDiff != null) {
            for (JsonNode change : textDiff) {
                int slide = change.path("slide").asInt();
                String old = normalise(change.hasNonNull("old") ? change.get("old").asText() : "");
                if (old.isEmpty() || slide < 1 || slide > slideAltText.size()) {
                    continue;
                }
                String alt = slideAltText.get(slide - 1);
                if (alt != null && normalise(alt).contains(old)) {
                    flagged.add(slide);
                }
            }
        }
        ArrayNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        flagged.forEach(out::add);
        return out;
    }

    /**
     * The flagged slides of {@code creative}'s newest APPLIED revision whose alt text still quotes the text that
     * revision replaced, or null when that revision flagged nothing.
     */
    public StaleAltText staleAltText(Creative creative) {
        Optional<CreativeRevision> latest = revisionRepository.findFirstByCreativeIdAndStatusOrderByRevisionNoDesc(
                creative.getId(), CreativeRevision.STATUS_APPLIED);
        if (latest.isEmpty() || latest.get().getAltTextFlags() == null || latest.get().getAltTextFlags().isEmpty()) {
            return null;
        }
        // The slides whose alt text, as it is now, still contains text this revision replaced.
        Set<Integer> quoting = new TreeSet<>();
        altTextFlags(latest.get().getTextDiff(), toStringList(creative.getSlideAltText()))
                .forEach(n -> quoting.add(n.asInt()));
        List<Integer> stale = new ArrayList<>();
        latest.get().getAltTextFlags().forEach(n -> {
            if (quoting.contains(n.asInt())) {
                stale.add(n.asInt());
            }
        });
        return new StaleAltText(latest.get().getAltTextFlags().size(), stale);
    }

    /** {@code flagged} slides were flagged when the revision applied; {@code stale} still quote the old text. */
    public record StaleAltText(int flagged, List<Integer> stale) {
    }

    static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // ── slide media ─────────────────────────────────────────────────────────

    /** {ratio -> {slide -> mediaId}}; anything that is not a known ratio, a positive slide or an id is a 422. */
    private Map<String, Map<Integer, String>> parseSlideMedia(Map<String, Map<String, String>> raw) {
        Map<String, Map<Integer, String>> out = new LinkedHashMap<>();
        List<CreativeValidationException.Violation> problems = new ArrayList<>();
        if (raw != null) {
            raw.forEach((ratio, bySlide) -> {
                if (!RENDERED_RATIOS.contains(ratio)) {
                    problems.add(new CreativeValidationException.Violation("slideMedia." + ratio, RULE_SLIDE_MEDIA,
                            "ratio must be one of " + String.join(", ", RENDERED_RATIOS)));
                    return;
                }
                Map<Integer, String> parsed = new LinkedHashMap<>();
                (bySlide == null ? Map.<String, String>of() : bySlide).forEach((slide, mediaId) -> {
                    try {
                        int n = Integer.parseInt(slide);
                        if (n < 1 || mediaId == null || mediaId.isBlank()) {
                            throw new NumberFormatException();
                        }
                        parsed.put(n, mediaId);
                    } catch (NumberFormatException e) {
                        problems.add(new CreativeValidationException.Violation("slideMedia." + ratio + "." + slide,
                                RULE_SLIDE_MEDIA, "slide must be a 1-based number mapped to a media id"));
                    }
                });
                out.put(ratio, parsed);
            });
        }
        if (!problems.isEmpty()) {
            throw new CreativeValidationException(problems);
        }
        return out;
    }

    /** Why {@code slideMedia} is not exactly changed slides x needed ratios, or null when it is. */
    private static String coverageProblem(Map<String, Map<Integer, String>> slideMedia, Set<String> ratiosNeeded,
                                          List<Integer> changed) {
        for (String ratio : slideMedia.keySet()) {
            if (!ratiosNeeded.contains(ratio)) {
                return "Unexpected " + ratio + " slides: this Creative has no " + ratio + " slide list to replace";
            }
        }
        for (String ratio : ratiosNeeded) {
            Map<Integer, String> bySlide = slideMedia.getOrDefault(ratio, Map.of());
            for (int slide : changed) {
                if (!bySlide.containsKey(slide)) {
                    return "Slide " + slide + " was not re-rendered at " + ratio;
                }
            }
            for (int slide : bySlide.keySet()) {
                if (!changed.contains(slide)) {
                    return "Slide " + slide + " was re-rendered at " + ratio + " but its text did not change";
                }
            }
        }
        return null;
    }

    /**
     * Which of the Creative's lists the render job re-renders, each with its ratio. A {@code 9x16} / {@code 4x5}
     * list is that ratio; a {@code default} list takes the ratio of its images (within 2%). Any non-empty list that
     * is 1x1, or a shape that is neither 9:16 nor 4:5, makes the Creative unsupported: the composer cannot re-render
     * it, and applying the new text without it would leave its slides saying the old words.
     */
    RenderPlan planRenderLists(Map<String, List<String>> slides) {
        Set<String> imageIds = new LinkedHashSet<>();
        slides.forEach((key, list) -> {
            if ("default".equals(key)) {
                imageIds.addAll(list);
            }
        });
        Map<String, CreativePhoto> images = new HashMap<>();
        if (!imageIds.isEmpty()) {
            photoRepository.findAllByIdIn(imageIds).forEach(p -> images.put(p.getId(), p));
        }
        List<RenderList> lists = new ArrayList<>();
        for (Map.Entry<String, List<String>> list : slides.entrySet()) {
            if (list.getValue().isEmpty()) {
                continue;
            }
            String key = list.getKey();
            if ("1x1".equals(key) || ("default".equals(key) && isOneByOne(list.getValue(), images))) {
                return new RenderPlan(List.of(), UNSUPPORTED_1X1);
            }
            String ratio = "default".equals(key) ? ratioOfEntries(list.getValue(), images)
                    : RENDERED_RATIOS.contains(key) ? key : null;
            if (ratio == null) {
                return new RenderPlan(List.of(), "Text editing isn't available for Creatives whose " + key
                        + " slides are not 9:16 or 4:5 yet. Re-compose it locally with compose_slideshow.");
            }
            lists.add(new RenderList(key, ratio));
        }
        return new RenderPlan(lists, null);
    }

    private static boolean isOneByOne(List<String> ids, Map<String, CreativePhoto> images) {
        return ids.stream().allMatch(id -> "1x1".equals(classify(images.get(id))));
    }

    /** The one ratio every image of a list shares, or null when one is unknown, off-shape, 1x1 or they differ. */
    private static String ratioOfEntries(List<String> ids, Map<String, CreativePhoto> images) {
        String common = null;
        for (String id : ids) {
            String ratio = classify(images.get(id));
            if (ratio == null || (common != null && !common.equals(ratio))) {
                return null;
            }
            common = ratio;
        }
        return RENDERED_RATIOS.contains(common) ? common : null;
    }

    /** 9x16, 4x5 or 1x1 when an image's shape is within the tolerance of it; null when it is none or unknown. */
    static String classify(CreativePhoto photo) {
        if (photo == null) {
            return null;
        }
        return classify(photo.getWidth(), photo.getHeight());
    }

    static String classify(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return null;
        }
        double shape = (double) width / height;
        for (Map.Entry<String, Double> ratio : RATIO_VALUES.entrySet()) {
            if (Math.abs(shape - ratio.getValue()) / ratio.getValue() <= RATIO_TOLERANCE) {
                return ratio.getKey();
            }
        }
        return null;
    }

    private static String describe(List<SlideshowCheck> errors) {
        List<String> parts = new ArrayList<>();
        for (SlideshowCheck c : errors.subList(0, Math.min(3, errors.size()))) {
            parts.add("slide " + c.getSlide() + " " + (c.getRatio() != null ? c.getRatio().getValue() : "?") + " "
                    + c.getRule() + " (" + c.getMessage() + ")");
        }
        return String.join("; ", parts) + (errors.size() > 3 ? "; and " + (errors.size() - 3) + " more" : "");
    }

    // ── auth + lookups ──────────────────────────────────────────────────────

    /**
     * A person: a member to read, ADMIN or CREATOR to write (404 for a non-member, as everywhere). A run token:
     * only the revision its own run was dispatched for (the controller has already proven the run is a render run
     * of this Creative).
     */
    private void authorize(String projectId, String creativeId, CreativeRevision revision, RenderCaller caller,
                           boolean write) {
        switch (java.util.Objects.requireNonNull(caller, "caller")) {
            case RenderCaller.UserCaller u -> {
                if (write) {
                    requireEditor(projectId, u.user());
                } else {
                    requireMember(projectId, u.user());
                }
            }
            case RenderCaller.RunCaller r -> {
                if (!r.projectId().equals(projectId) || !r.creativeId().equals(creativeId)) {
                    throw new ForbiddenException("This run token is for another Creative");
                }
                if (revision.getWorkflowRunId() == null || !revision.getWorkflowRunId().equals(r.runId())) {
                    throw new ForbiddenException("This run token did not request revision " + revision.getId());
                }
            }
        }
    }

    private static void requirePending(CreativeRevision revision) {
        if (!CreativeRevision.STATUS_PENDING.equals(revision.getStatus())) {
            throw new ConflictException("Revision " + revision.getId() + " is no longer PENDING ("
                    + revision.getStatus() + ")");
        }
    }

    private Creative findCreative(String projectId, String creativeId) {
        return creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
    }

    private CreativeRevision findRevision(String creativeId, String revisionId) {
        return revisionRepository.findByIdAndCreativeId(revisionId, creativeId)
                .orElseThrow(() -> new EntityNotFoundException("Revision not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isEditor(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage Creatives");
        }
    }

    private static CreativeValidationException violation(String field, String ruleId, String message) {
        return new CreativeValidationException(List.of(new CreativeValidationException.Violation(field, ruleId, message)));
    }

    private Map<String, List<String>> toSlidesMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, List<String>>>() { });
    }

    private List<String> toStringList(JsonNode node) {
        return node == null || node.isNull() ? List.of()
                : objectMapper.convertValue(node, new TypeReference<List<String>>() { });
    }
}
