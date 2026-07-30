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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (mocked repos) for {@link WorkItemSnapshotService}: field mapping, the {@code
 * hasArtifacts()} significance-gate truth table, the 32 KB document truncation cap, and the batched
 * reviewer-name resolution. {@code JOIN FETCH} query correctness against real Postgres is out of scope
 * here -- see {@code WorkItemSnapshotServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class WorkItemSnapshotServiceTest {

    private static final String WORK_ITEM_ID = "wi-1";

    @Mock private WorkItemRepository workItemRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private UserRepository userRepository;

    private WorkItemSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new WorkItemSnapshotService(workItemRepository, documentRepository, commentRepository,
                assetRepository, reviewRepository, userRepository);
    }

    // ---- helpers ----

    private WorkItem workItem(String description) {
        Project project = new Project();
        project.setId("proj-1");
        project.setKey("ENG");

        WorkItem workItem = new WorkItem();
        workItem.setId(WORK_ITEM_ID);
        workItem.setProject(project);
        workItem.setTitle("Ship the thing");
        workItem.setDescription(description);
        workItem.setType("TASK");
        workItem.setWorkflow("ENGINEERING");
        workItem.setCurrentStatus("DONE");
        workItem.setSequenceNumber(42);
        workItem.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        workItem.setUpdatedAt(OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        return workItem;
    }

    private User user(String id, String name, String email) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        return user;
    }

    private Document document(WorkItem workItem, String filename, String content) {
        Document document = new Document();
        document.setWorkItem(workItem);
        document.setFilename(filename);
        document.setContentType("text/markdown");
        document.setContent(content);
        document.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        document.setUpdatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return document;
    }

    private Comment comment(WorkItem workItem, User author, String content) {
        Comment comment = new Comment();
        comment.setId("c-" + content.hashCode());
        comment.setWorkItem(workItem);
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return comment;
    }

    private Asset asset(WorkItem workItem) {
        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType("github_pr");
        asset.setLabel("Pull Request");
        asset.setKind("link");
        asset.setRef("https://github.com/x/y/pull/1");
        asset.setDone(true);
        asset.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return asset;
    }

    private Review review(String reviewerId, String verdict) {
        Review review = new Review();
        review.setWorkItemId(WORK_ITEM_ID);
        review.setReviewerId(reviewerId);
        review.setVerdict(verdict);
        review.setBody("a verdict");
        review.setSubmittedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return review;
    }

    /** Stubs every collaborator query to return empty for a Work Item -- callers override the ones they
     *  care about. */
    private void stubWorkItemWithEmptyCollections(WorkItem workItem) {
        when(workItemRepository.findByIdWithProjectAndAssignee(WORK_ITEM_ID)).thenReturn(Optional.of(workItem));
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of());
        when(commentRepository.findAllByWorkItemIdWithAuthorAndDocument(WORK_ITEM_ID)).thenReturn(List.of());
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of());
        when(reviewRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of());
    }

    // ---- missing work item ----

    @Test
    void missingWorkItem_returnsEmptyWithoutTouchingOtherRepos() {
        when(workItemRepository.findByIdWithProjectAndAssignee(WORK_ITEM_ID)).thenReturn(Optional.empty());

        assertThat(service.snapshot(WORK_ITEM_ID)).isEmpty();
        verifyNoInteractions(documentRepository, commentRepository, assetRepository, reviewRepository);
    }

    // ---- field mapping ----

    @Test
    void fieldMapping_mapsWorkItemProjectAndAssignee() {
        WorkItem workItem = workItem("the description");
        workItem.setAssignee(user("u-1", "Alice", "alice@example.com"));
        stubWorkItemWithEmptyCollections(workItem);

        WorkItemSnapshot snapshot = service.snapshot(WORK_ITEM_ID).orElseThrow();

        assertThat(snapshot.workItemId()).isEqualTo(WORK_ITEM_ID);
        assertThat(snapshot.projectId()).isEqualTo("proj-1");
        assertThat(snapshot.projectKey()).isEqualTo("ENG");
        assertThat(snapshot.sequenceNumber()).isEqualTo(42);
        assertThat(snapshot.title()).isEqualTo("Ship the thing");
        assertThat(snapshot.description()).isEqualTo("the description");
        assertThat(snapshot.type()).isEqualTo("TASK");
        assertThat(snapshot.workflow()).isEqualTo("ENGINEERING");
        assertThat(snapshot.currentStatus()).isEqualTo("DONE");
        assertThat(snapshot.assigneeName()).isEqualTo("Alice");
        assertThat(snapshot.createdAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(snapshot.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        assertThat(snapshot.key()).isEqualTo("ENG-42");
    }

    @Test
    void assigneeWithNoName_fallsBackToEmail() {
        WorkItem workItem = workItem(null);
        workItem.setAssignee(user("u-1", null, "alice@example.com"));
        stubWorkItemWithEmptyCollections(workItem);

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().assigneeName()).isEqualTo("alice@example.com");
    }

    @Test
    void noAssignee_assigneeNameIsNull() {
        stubWorkItemWithEmptyCollections(workItem(null));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().assigneeName()).isNull();
    }

    // ---- hasArtifacts() truth table ----

    @Test
    void allEmptyAndNoDescription_hasArtifactsFalse() {
        stubWorkItemWithEmptyCollections(workItem(null));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isFalse();
    }

    @Test
    void whitespaceOnlyDescription_hasArtifactsFalse() {
        stubWorkItemWithEmptyCollections(workItem("   "));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isFalse();
    }

    @Test
    void nonBlankDescriptionOnly_hasArtifactsTrue() {
        stubWorkItemWithEmptyCollections(workItem("real content"));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isTrue();
    }

    @Test
    void documentOnly_hasArtifactsTrue() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(document(workItem, "spec.md", "content")));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isTrue();
    }

    @Test
    void commentOnly_hasArtifactsTrue() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        when(commentRepository.findAllByWorkItemIdWithAuthorAndDocument(WORK_ITEM_ID))
                .thenReturn(List.of(comment(workItem, user("u-1", "Alice", "alice@example.com"), "looks good")));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isTrue();
    }

    @Test
    void assetOnly_hasArtifactsTrue() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(asset(workItem)));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isTrue();
    }

    @Test
    void reviewOnly_hasArtifactsTrue() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        Review review = review("u-1", "APPROVED");
        when(reviewRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(review));
        when(userRepository.findAllById(List.of("u-1"))).thenReturn(List.of(user("u-1", "Bob", "bob@example.com")));

        assertThat(service.snapshot(WORK_ITEM_ID).orElseThrow().hasArtifacts()).isTrue();
    }

    // ---- document truncation ----

    @Test
    void documentOver32Kb_isTruncatedAndFlagged() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        String bigContent = "a".repeat(40_000);
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(document(workItem, "spec.md", bigContent)));

        WorkItemSnapshot.Doc doc = service.snapshot(WORK_ITEM_ID).orElseThrow().documents().get(0);

        assertThat(doc.truncated()).isTrue();
        assertThat(doc.content().getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(bigContent.getBytes(StandardCharsets.UTF_8).length);
        assertThat(doc.content()).contains("truncated");
    }

    @Test
    void documentUnder32Kb_isNotTruncated() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        String smallContent = "a".repeat(100);
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(document(workItem, "spec.md", smallContent)));

        WorkItemSnapshot.Doc doc = service.snapshot(WORK_ITEM_ID).orElseThrow().documents().get(0);

        assertThat(doc.truncated()).isFalse();
        assertThat(doc.content()).isEqualTo(smallContent);
    }

    // ---- reviewer-name batch resolution ----

    @Test
    void reviewerNames_batchResolvedInOneCall_includingUnresolvableId() {
        WorkItem workItem = workItem(null);
        stubWorkItemWithEmptyCollections(workItem);
        Review known = review("u-1", "APPROVED");
        Review unresolvable = review("u-ghost", "CHANGES_REQUESTED");
        when(reviewRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(known, unresolvable));
        when(userRepository.findAllById(List.of("u-1", "u-ghost")))
                .thenReturn(List.of(user("u-1", "Bob", "bob@example.com")));

        List<WorkItemSnapshot.Verdict> verdicts = service.snapshot(WORK_ITEM_ID).orElseThrow().reviews();

        assertThat(verdicts).extracting(WorkItemSnapshot.Verdict::reviewerName)
                .containsExactlyInAnyOrder("Bob", "u-ghost"); // unresolvable id falls back to itself
    }

    // ---- deterministic ordering ----

    /**
     * The finders backing this service carry no {@code ORDER BY}, and Postgres guarantees no row order
     * without one. That matters because {@code KnowledgeSignalSink} hashes the assembled snapshot for its
     * dedup key: if two assemblies of the same unchanged Work Item can order these lists differently, the
     * two payloads hash differently and a lifecycle cascade double-files. Feeding every collaborator its
     * rows in reversed order and asserting the assembled snapshot is unchanged is the only way to pin
     * that -- asserting on a single assembly cannot, which is why this class of bug survives a green suite
     * and then shows up intermittently in production.
     */
    @Test
    void listsAreOrderedIndependentlyOfRepositoryRowOrder() {
        WorkItem workItem = workItem("has a description");
        User alice = user("u-1", "Alice", "alice@example.com");
        User bob = user("u-2", "Bob", "bob@example.com");

        Document apple = document(workItem, "apple.md", "first");
        Document banana = document(workItem, "banana.md", "second");
        Comment earlier = comment(workItem, alice, "earlier");
        earlier.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        Comment later = comment(workItem, bob, "later");
        later.setCreatedAt(OffsetDateTime.parse("2026-01-03T00:00:00Z"));
        Asset prAsset = asset(workItem);
        Asset docsAsset = asset(workItem);
        docsAsset.setType("published_url");
        docsAsset.setRef("https://example.com/docs");
        Review firstReview = review("u-1", "APPROVED");
        Review secondReview = review("u-2", "CHANGES_REQUESTED");
        secondReview.setSubmittedAt(OffsetDateTime.parse("2026-01-04T00:00:00Z"));

        when(workItemRepository.findByIdWithProjectAndAssignee(WORK_ITEM_ID)).thenReturn(Optional.of(workItem));
        // anyList(), not an exact list: reversing the reviews also reverses the distinct reviewer-id list
        // this is called with. That argument order is irrelevant to the result (the ids are resolved into a
        // map), so pinning it here would only make the test order-sensitive in a way the service isn't.
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice, bob));

        // First assembly: rows in one order.
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(apple, banana));
        when(commentRepository.findAllByWorkItemIdWithAuthorAndDocument(WORK_ITEM_ID))
                .thenReturn(List.of(earlier, later));
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(prAsset, docsAsset));
        when(reviewRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(firstReview, secondReview));
        WorkItemSnapshot first = service.snapshot(WORK_ITEM_ID).orElseThrow();

        // Second assembly: every list reversed, as an unlucky query plan or a moved heap tuple would.
        when(documentRepository.findByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(banana, apple));
        when(commentRepository.findAllByWorkItemIdWithAuthorAndDocument(WORK_ITEM_ID))
                .thenReturn(List.of(later, earlier));
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(docsAsset, prAsset));
        when(reviewRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(secondReview, firstReview));
        WorkItemSnapshot second = service.snapshot(WORK_ITEM_ID).orElseThrow();

        assertThat(second.documents()).isEqualTo(first.documents());
        assertThat(second.comments()).isEqualTo(first.comments());
        assertThat(second.assets()).isEqualTo(first.assets());
        assertThat(second.reviews()).isEqualTo(first.reviews());
        // Records are value types, so this pins the whole snapshot, not just the four lists.
        assertThat(second).isEqualTo(first);
    }
}
