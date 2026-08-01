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
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.WorkItemSnapshot;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed test for {@link WorkItemSnapshotService}'s two new {@code JOIN FETCH} finders --
 * {@code WorkItemRepository#findByIdWithProjectAndAssignee} and
 * {@code CommentRepository#findAllByWorkItemIdWithAuthorAndDocument}. A malformed JPQL string in either
 * only fails at Spring context startup or query execution against a real schema, a class of bug a
 * mocked-repo unit test can never catch. See {@code WorkItemSnapshotServiceTest} for the field-mapping /
 * significance-gate / truncation / reviewer-resolution coverage; this class only proves the two queries
 * work end to end against real Postgres with no {@code LazyInitializationException}.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@link WorkItemSnapshotService#snapshot} is called here with
 * no surrounding transaction already open, exercising the "opens its own session" half of its
 * {@code readOnly = true} contract (the "joins the caller's open transaction" half is what
 * {@code KnowledgeSignalSink} exercises in production, off {@code WorkItemService}'s write transaction).
 * Isolation comes from each test using its own random project/work-item ids, per the shared-database
 * contract in {@code docs/testing-guidelines.md}.
 */
class WorkItemSnapshotServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private WorkItemSnapshotService snapshotService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private ReviewRepository reviewRepository;

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }

    @Test
    void missingWorkItem_returnsEmpty() {
        assertThat(snapshotService.snapshot(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void fullSnapshot_assemblesWithNoLazyInitializationException() {
        User creator = newUser("Creator");
        User assignee = newUser("Alice Assignee");
        User commentAuthor = newUser("Bob Commenter");
        User reviewer = newUser("Carol Reviewer");

        Project project = new Project();
        project.setName("Snapshot Test Project");
        project.setKey("SN" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        WorkItem workItem = new WorkItem();
        workItem.setProject(project);
        workItem.setType("TASK");
        workItem.setTitle("Ship the thing");
        workItem.setDescription("the description");
        workItem.setCreatedBy(creator);
        workItem.setAssignee(assignee);
        workItem.setWorkflow("ENGINEERING");
        workItem.setWorkflowVersion(1);
        workItem.setCurrentStatus("DONE");
        workItem.setSequenceNumber(1);
        workItem = workItemRepository.save(workItem);

        Document document = new Document();
        document.setWorkItem(workItem);
        document.setFilename("spec.md");
        document.setContent("the spec body");
        document = documentRepository.save(document);

        Comment comment = new Comment();
        comment.setWorkItem(workItem);
        comment.setDocument(document);
        comment.setAuthor(commentAuthor);
        comment.setContent("looks good");
        comment.setLineNumber(1);
        comment.setQuotedText("the spec body");
        commentRepository.save(comment);

        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType("github_pr");
        asset.setKind("link");
        asset.setRef("https://github.com/x/y/pull/1");
        assetRepository.save(asset);

        Review review = new Review();
        review.setWorkItemId(workItem.getId());
        review.setReviewerId(reviewer.getId());
        review.setVerdict("APPROVED");
        review.setBody("ship it");
        reviewRepository.save(review);

        WorkItemSnapshot snapshot = snapshotService.snapshot(workItem.getId()).orElseThrow();

        assertThat(snapshot.workItemId()).isEqualTo(workItem.getId());
        assertThat(snapshot.projectId()).isEqualTo(project.getId());
        assertThat(snapshot.projectKey()).isEqualTo(project.getKey());
        assertThat(snapshot.assigneeName()).isEqualTo("Alice Assignee");
        assertThat(snapshot.documents()).hasSize(1);
        assertThat(snapshot.documents().get(0).content()).isEqualTo("the spec body");
        assertThat(snapshot.comments()).hasSize(1);
        assertThat(snapshot.comments().get(0).authorName()).isEqualTo("Bob Commenter");
        assertThat(snapshot.comments().get(0).documentFilename()).isEqualTo("spec.md");
        assertThat(snapshot.assets()).hasSize(1);
        assertThat(snapshot.assets().get(0).ref()).isEqualTo("https://github.com/x/y/pull/1");
        assertThat(snapshot.reviews()).hasSize(1);
        assertThat(snapshot.reviews().get(0).reviewerName()).isEqualTo("Carol Reviewer");
        assertThat(snapshot.hasArtifacts()).isTrue();
    }
}
