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
import com.conductor.repository.WorkItemReviewerRepository;
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
import org.springframework.transaction.support.TransactionSynchronization;
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
    /** The 409 {@code code} for "a slide-text revision is still rendering". */
    public static final String CODE_EDIT_ACTIVE = "SLIDE_EDIT_ACTIVE";

    static final String VERSION_MOVED_ON = "The Creative changed while the slides were rendering. Make the edit again.";
    /** A PENDING revision with no workflow run (it never got one) is abandoned after this long. */
    static final long ORPHAN_PENDING_MINUTES = 35;

    private static final List<String> RENDERED_RATIOS = List.of("9x16", "4x5");
    private static final Map<String, Double> RATIO_VALUES = Map.of("9x16", 9.0 / 16.0, "4x5", 4.0 / 5.0, "1x1", 1.0);

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
    private final WorkItemReviewerRepository reviewerRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final WorkflowDefinitionResolver workflowResolver;
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
                                   WorkItemReviewerRepository reviewerRepository,
                                   AssetRepository assetRepository,
                                   UserRepository userRepository,
                                   CreativeRenderFrameRepository frameRepository,
                                   WorkflowDefinitionResolver workflowResolver,
                                   PlatformTransactionManager transactionManager) {
        this.cancellationService = cancellationService;
        this.workItemRepository = workItemRepository;
        this.reviewerRepository = reviewerRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
        this.frameRepository = frameRepository;
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
                                 JsonNode slideStyle) {
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
        WorkItem post = null;
        if (postId == null) {
            requireEditor(projectId, caller);
        } else {
            requireMember(projectId, caller);
            post = workItemRepository.findById(postId)
                    .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                    .orElseThrow(() -> new EntityNotFoundException("Post not found"));
            reviewerOfPost = reviewerRepository.findByWorkItemIdAndUserId(postId, caller.getId()).isPresent();
            if (!reviewerOfPost && !projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
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
        if (post != null) {
            requirePostInReviewUsing(projectId, creative, post);
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
        List<String> frameIds = frameRepository.findAllByCreativeId(creative.getId()).stream()
                .map(CreativeRenderFrame::getId).toList();
        boolean uses = !frameIds.isEmpty() && assetRepository.findAllByWorkItemId(post.getId()).stream()
                .anyMatch(a -> a.getCreativeFrameId() != null && frameIds.contains(a.getCreativeFrameId()));
        if (!uses) {
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
        return new RevisionDetail(revision, changedSlides(revision), creative.getSlides(), creative.getSlideStyle());
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
    @Transactional
    public CreativeRevision complete(String projectId, String creativeId, String revisionId,
                                     CompleteCreativeRevisionRequest request, RenderCaller caller) {
        Creative creative = creativeRepository.findByIdAndProjectIdForUpdate(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        CreativeRevision revision = findRevision(creativeId, revisionId);
        authorize(projectId, creativeId, revision, caller, true);
        requirePending(revision);
        releaseUnstartedRun(revision, caller);

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

        // Which lists get which ratio's slides.
        Map<String, List<String>> slides = toSlidesMap(creative.getSlides());
        Map<String, String> ratioOfList = new LinkedHashMap<>();
        Set<String> ratiosNeeded = new LinkedHashSet<>();
        boolean leftAlone = false;
        Map<String, CreativePhoto> sizeProbe = firstImages(slides);
        for (Map.Entry<String, List<String>> list : slides.entrySet()) {
            if (list.getValue().isEmpty()) {
                continue;
            }
            String ratio = "default".equals(list.getKey())
                    ? ratioOfImage(sizeProbe.get(list.getValue().get(0))) : list.getKey();
            if (ratio == null || "1x1".equals(ratio)) {
                leftAlone = true;
            } else {
                ratioOfList.put(list.getKey(), ratio);
                ratiosNeeded.add(ratio);
            }
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
        for (String id : mediaIds) {
            // The composer's own output: nothing in it to check for burned-in text.
            photos.get(id).setChecked(true);
        }
        photoRepository.saveAll(photos.values());

        // Replace the changed entries of every list that gets re-rendered.
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
        if (leftAlone) {
            // No slide / ratio: it is about the Creative's 1x1 list(s) as a whole.
            stored.addObject().put("rule", "render").put("severity", "warning")
                    .put("message", "1x1 slides were not re-rendered");
        }
        revision.setChecks(stored.isEmpty() ? null : stored);
        revision.setError(null);
        revision = revisionRepository.save(revision);

        render(revision, creative);
        return revision;
    }

    /** The job could not produce the slides. */
    @Transactional
    public CreativeRevision fail(String projectId, String creativeId, String revisionId,
                                 FailCreativeRevisionRequest request, RenderCaller caller) {
        findCreative(projectId, creativeId);
        CreativeRevision revision = findRevision(creativeId, revisionId);
        authorize(projectId, creativeId, revision, caller, true);
        requirePending(revision);
        releaseUnstartedRun(revision, caller);
        return failed(revision, request.getError(), request.getChecks());
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
    private void render(CreativeRevision revision, Creative creative) {
        if (!renderService.needsLocalRender(creative)) {
            try {
                CreativeRender render = renderService.renderCarouselForRevision(creative.getProjectId(), creative,
                        revision.getAuthorUserId());
                renderLinker.link(revision, render);
            } catch (CreativeValidationException | com.conductor.exception.BusinessException e) {
                note(revision, "The slides were updated, but the carousel could not be rendered: " + e.getMessage());
            }
            return;
        }
        String ignoreRunId = revision.getWorkflowRunId();
        String projectId = revision.getProjectId();
        String creativeId = revision.getCreativeId();
        String revisionId = revision.getId();
        Runnable dispatch = () -> {
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
        };
        afterCommit(dispatch);
    }

    /**
     * A person who finishes a revision with their own key (no daemon, no runtime target) leaves the run that was
     * dispatched for it waiting for a runner that is no longer needed, and a waiting run blocks the next edit and
     * every Render click for up to two days. Once the answer has committed, cancel that run if nothing has
     * started it; a run that is already going is left to finish (it will find the revision settled).
     */
    private void releaseUnstartedRun(CreativeRevision revision, RenderCaller caller) {
        if (!(caller instanceof RenderCaller.UserCaller) || revision.getWorkflowRunId() == null) {
            return;
        }
        String runId = revision.getWorkflowRunId();
        afterCommit(() -> {
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

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void note(CreativeRevision revision, String message) {
        revision.setError(message);
        revisionRepository.save(revision);
    }

    // ── stale PENDING revisions ─────────────────────────────────────────────

    /**
     * A PENDING revision whose workflow run is over without having completed it (failed, cancelled, never picked
     * up, or finished without reporting) can never finish: it becomes FAILED with the run's reason, stored in its
     * own transaction so it also holds from a read-only caller. Returns whether {@code revision} was settled.
     */
    boolean resolveStale(CreativeRevision revision) {
        if (!CreativeRevision.STATUS_PENDING.equals(revision.getStatus())) {
            return true;
        }
        String reason = staleReason(revision);
        if (reason == null) {
            return false;
        }
        String id = revision.getId();
        // The row as the other transaction leaves it: FAILED by us, or already settled by someone else.
        CreativeRevision settled = newTx.execute(status -> revisionRepository.findById(id).map(r -> {
            if (CreativeRevision.STATUS_PENDING.equals(r.getStatus())) {
                r.setStatus(CreativeRevision.STATUS_FAILED);
                r.setError(reason);
                revisionRepository.save(r);
            }
            return r;
        }).orElse(null));
        if (settled != null) {
            revision.setStatus(settled.getStatus());
            revision.setError(settled.getError());
        }
        return !CreativeRevision.STATUS_PENDING.equals(revision.getStatus());
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

    /** The first image of every non-empty list, loaded in one query (for the ratio of a {@code default} list). */
    private Map<String, CreativePhoto> firstImages(Map<String, List<String>> slides) {
        Set<String> ids = new LinkedHashSet<>();
        slides.values().forEach(list -> {
            if (!list.isEmpty()) {
                ids.add(list.get(0));
            }
        });
        Map<String, CreativePhoto> out = new HashMap<>();
        if (!ids.isEmpty()) {
            photoRepository.findAllByIdIn(ids).forEach(p -> out.put(p.getId(), p));
        }
        return out;
    }

    /** The closest of 9x16 / 4x5 / 1x1 to an image's shape, or null when its size is unknown. */
    static String ratioOfImage(CreativePhoto photo) {
        if (photo == null || photo.getWidth() == null || photo.getHeight() == null
                || photo.getWidth() <= 0 || photo.getHeight() <= 0) {
            return null;
        }
        double shape = (double) photo.getWidth() / photo.getHeight();
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Double> ratio : RATIO_VALUES.entrySet()) {
            double distance = Math.abs(Math.log(shape / ratio.getValue()));
            if (distance < bestDistance) {
                best = ratio.getKey();
                bestDistance = distance;
            }
        }
        return best;
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
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
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
