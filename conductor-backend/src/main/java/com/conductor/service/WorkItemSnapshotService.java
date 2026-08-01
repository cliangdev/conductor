package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Comment;
import com.conductor.entity.Document;
import com.conductor.entity.Project;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.WorkItemSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles a {@link WorkItemSnapshot} -- a Work Item plus everything it produced -- in five fixed
 * queries, no N+1. Lives in {@code service}, not {@code knowledge}: pulling five JPA repositories into
 * the knowledge bounded context would defeat the point of {@code KnowledgeSignalSink} existing as that
 * context's anti-corruption boundary. Also deliberately NOT a method on {@link WorkItemService}: {@code
 * WorkItemService -> SignalBus -> KnowledgeSignalSink -> WorkItemService} would be a real bean cycle the
 * moment the sink called back into it.
 */
@Service
public class WorkItemSnapshotService {

    /** Per-document content cap -- see the class-level rationale on {@link #truncate}. */
    static final int MAX_DOCUMENT_CONTENT_BYTES = 32 * 1024;

    private static final String TRUNCATION_MARKER =
            "\n\n[... truncated at " + (MAX_DOCUMENT_CONTENT_BYTES / 1024) + " KB; content continues beyond this point ...]";

    /*
     * Every list this service returns is sorted on a stable business key, because a consumer hashes the
     * assembled snapshot: KnowledgeSignalSink's dedup key is sha256(payload), and that only collapses two
     * dispatches about the same Work Item (a LifecycleTriggerDispatcher cascade) if both assemble to the
     * exact same bytes. The finders below (findByWorkItemId / findAllByWorkItemId) carry no ORDER BY, and
     * Postgres guarantees no order without one -- same-transaction repeats usually agree, which is exactly
     * what would make the resulting double-file intermittent rather than obvious. Sorting here rather than
     * adding ORDER BY to the shared finders keeps their semantics untouched for their other callers, and
     * puts the ordering next to the contract that depends on it.
     *
     * nullsFirst throughout: none of these keys is non-null in the schema.
     */
    private static final Comparator<WorkItemSnapshot.Doc> DOC_ORDER =
            Comparator.comparing(WorkItemSnapshot.Doc::filename, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<WorkItemSnapshot.Note> NOTE_ORDER =
            Comparator.comparing(WorkItemSnapshot.Note::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(WorkItemSnapshot.Note::id, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<WorkItemSnapshot.Artifact> ARTIFACT_ORDER =
            Comparator.comparing(WorkItemSnapshot.Artifact::type, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(WorkItemSnapshot.Artifact::ref, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<WorkItemSnapshot.Verdict> VERDICT_ORDER =
            Comparator.comparing(WorkItemSnapshot.Verdict::submittedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(WorkItemSnapshot.Verdict::reviewerName, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final WorkItemRepository workItemRepository;
    private final DocumentRepository documentRepository;
    private final CommentRepository commentRepository;
    private final AssetRepository assetRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;

    public WorkItemSnapshotService(WorkItemRepository workItemRepository,
                                   DocumentRepository documentRepository,
                                   CommentRepository commentRepository,
                                   AssetRepository assetRepository,
                                   ReviewRepository reviewRepository,
                                   UserRepository userRepository) {
        this.workItemRepository = workItemRepository;
        this.documentRepository = documentRepository;
        this.commentRepository = commentRepository;
        this.assetRepository = assetRepository;
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
    }

    /**
     * Assembles a full snapshot of a Work Item, or empty if it no longer exists (e.g. deleted between a
     * status-change publish and this call landing).
     *
     * <p>{@code readOnly = true} is load-bearing, not decorative. {@code REQUIRED} propagation (the
     * default) means this joins the caller's already-open transaction when there is one -- which is the
     * common case: {@code KnowledgeSignalSink} calls this synchronously off {@code WorkItemService}'s own
     * write transaction (see {@code InProcessSignalBus}, which dispatches signals on the calling thread,
     * inside the publisher's transaction). Joining that transaction means (a) the open Hibernate session
     * lets the five queries below resolve every {@code LAZY} association without a
     * {@code LazyInitializationException}, and (b) Hibernate's auto-flush makes the caller's own
     * not-yet-flushed sibling writes (e.g. the merged-PR Asset {@code WorkItemService#completeFromPullRequest}
     * just inserted) visible to this method's SELECTs. Spring does not downgrade an already-open
     * read-write transaction just because a participant declares {@code readOnly = true} -- that flag only
     * takes effect when this method opens its own transaction, e.g. a unit test calling it directly with
     * no transaction already active.
     */
    @Transactional(readOnly = true)
    public Optional<WorkItemSnapshot> snapshot(String workItemId) {
        return workItemRepository.findByIdWithProjectAndAssignee(workItemId).map(this::assemble);
    }

    private WorkItemSnapshot assemble(WorkItem workItem) {
        List<Document> documents = documentRepository.findByWorkItemId(workItem.getId());
        List<Comment> comments = commentRepository.findAllByWorkItemIdWithAuthorAndDocument(workItem.getId());
        List<Asset> assets = assetRepository.findAllByWorkItemId(workItem.getId());
        List<Review> reviews = reviewRepository.findAllByWorkItemId(workItem.getId());
        Map<String, String> reviewerNames = resolveReviewerNames(reviews);

        Project project = workItem.getProject();
        User assignee = workItem.getAssignee();

        return new WorkItemSnapshot(
                workItem.getId(),
                project != null ? project.getId() : null,
                project != null ? project.getKey() : null,
                workItem.getSequenceNumber(),
                workItem.getTitle(),
                workItem.getDescription(),
                workItem.getType(),
                workItem.getWorkflow(),
                workItem.getCurrentStatus(),
                assignee != null ? displayName(assignee) : null,
                workItem.getCreatedAt(),
                workItem.getUpdatedAt(),
                documents.stream().map(this::toDoc).sorted(DOC_ORDER).toList(),
                comments.stream().map(this::toNote).sorted(NOTE_ORDER).toList(),
                assets.stream().map(this::toArtifact).sorted(ARTIFACT_ORDER).toList(),
                reviews.stream().map(r -> toVerdict(r, reviewerNames)).sorted(VERDICT_ORDER).toList());
    }

    /**
     * {@code Review.reviewerId} is a raw {@code String} column, not a JPA association (see
     * {@link Review}), so there is no {@code JOIN FETCH} available for it. Resolved here with one batched
     * {@link UserRepository#findAllById}, never per-review -- a Work Item with N reviews must not cost N
     * user lookups. A reviewer id with no matching row (a deleted user) falls back to the raw id itself,
     * so the verdict stays attributable even without a display name.
     */
    private Map<String, String> resolveReviewerNames(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return Map.of();
        }
        List<String> reviewerIds = reviews.stream().map(Review::getReviewerId).distinct().toList();
        Map<String, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(reviewerIds)) {
            names.put(user.getId(), displayName(user));
        }
        return names;
    }

    private WorkItemSnapshot.Doc toDoc(Document document) {
        String content = document.getContent();
        boolean truncated = false;
        if (content != null) {
            String truncatedContent = truncate(content);
            truncated = truncatedContent != content; // reference inequality: truncate() returns the same instance when under the cap
            content = truncated ? truncatedContent + TRUNCATION_MARKER : truncatedContent;
        }
        return new WorkItemSnapshot.Doc(document.getFilename(), document.getContentType(), content, truncated,
                document.getCreatedAt(), document.getUpdatedAt());
    }

    /**
     * Caps document content at {@link #MAX_DOCUMENT_CONTENT_BYTES} before it ever reaches a submission
     * payload. {@code read_knowledge_sources} inlines a source's whole payload into the librarian's
     * context window, and {@code Document.content} has no size limit of its own (up to
     * {@code DocumentService.MAX_CONTENT_BYTES}, 50 MB) -- without this cap, one oversized document would
     * dominate (or blow) the librarian's context budget for a batch that also carries every other pending
     * source. Trims back from the byte cap to the start of a UTF-8 sequence so multi-byte characters are
     * never split.
     */
    private String truncate(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_DOCUMENT_CONTENT_BYTES) {
            return content;
        }
        int end = MAX_DOCUMENT_CONTENT_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private WorkItemSnapshot.Note toNote(Comment comment) {
        User author = comment.getAuthor();
        Document document = comment.getDocument();
        return new WorkItemSnapshot.Note(
                comment.getId(),
                author != null ? displayName(author) : null,
                document != null ? document.getFilename() : null,
                comment.getContent(),
                comment.getCreatedAt());
    }

    private WorkItemSnapshot.Artifact toArtifact(Asset asset) {
        return new WorkItemSnapshot.Artifact(asset.getType(), asset.getLabel(), asset.getKind(), asset.getRef(),
                asset.isDone(), asset.getCreatedAt());
    }

    private WorkItemSnapshot.Verdict toVerdict(Review review, Map<String, String> reviewerNames) {
        String reviewerName = reviewerNames.getOrDefault(review.getReviewerId(), review.getReviewerId());
        return new WorkItemSnapshot.Verdict(reviewerName, review.getVerdict(), review.getBody(), review.getSubmittedAt());
    }

    /** Same convention as {@code WorkItemService#publishStatusChanged}'s {@code assigneeName}: prefer the
     *  display name, fall back to email when unset. */
    private static String displayName(User user) {
        return user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getEmail();
    }
}
