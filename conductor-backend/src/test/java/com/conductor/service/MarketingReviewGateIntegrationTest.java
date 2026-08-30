package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.view.AvailableTransitionsView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of the MARKETING lifecycle's review gate (COND-23 T1.5) against a real database and
 * the fully wired signal bus:
 *
 * <ul>
 *   <li>the review-gated {@code IN_REVIEW -> APPROVED} edge is both hidden from the doer projection and
 *       rejected by the engine until an APPROVED Review from a REVIEWER (or ADMIN) exists;</li>
 *   <li>a {@code CHANGES_REQUESTED} verdict routes the Post onto the Workflow's CHANGES_REQUESTED edge, and
 *       resubmitting to IN_REVIEW leaves the Approve edge blocked until a fresh approval lands;</li>
 *   <li>reaching APPROVED publishes {@code conductor.work_item.status_changed}, which
 *       {@code WorkflowAutomationSignalSubscriber} turns into a run of a status-filtered YAML automation.</li>
 * </ul>
 *
 * <p>Carries its own private {@code @Container} rather than extending {@code AbstractPostgresIntegrationTest}:
 * the automation assertion creates a WorkflowRun, which enqueues workflow jobs, and the job-queue scheduler
 * claims <em>any</em> ready job across the shared database (see {@code docs/testing-guidelines.md}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class MarketingReviewGateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    private static final String MARKETING = "MARKETING";

    @Autowired private WorkItemService workItemService;
    @Autowired private WorkItemWorkflowService workItemWorkflowService;
    @Autowired private ReviewService reviewService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemReviewerRepository workItemReviewerRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private ObjectMapper objectMapper;

    private User author;
    private User reviewer;
    private User writer;
    private Project project;
    private WorkItem post;

    @BeforeEach
    void setUp() {
        author = newUser("Marketing Admin");
        reviewer = newUser("Marketing Reviewer");
        writer = newUser("Marketing Writer");

        project = new Project();
        project.setName("Marketing Review Gate");
        project.setKey("MK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(author);
        project = projectRepository.save(project);

        addMember(author, MemberRole.ADMIN);
        addMember(reviewer, MemberRole.REVIEWER);
        addMember(writer, MemberRole.CREATOR);

        workflowSeeder.seedMarketing(project);

        post = workItemService.createWorkItem(project.getId(), "POST", "Launch teaser", "Body", MARKETING, author);
    }

    // [auto] The APPROVED transition is hidden and rejected without an APPROVED review

    @Test
    void approveEdgeIsHiddenAndRejectedWhileThePostHasNoApprovedReview() {
        moveTo("IN_REVIEW");

        assertThat(targetStatuses(author))
                .contains("CHANGES_REQUESTED")
                .doesNotContain("APPROVED");

        assertThatThrownBy(() -> moveTo("APPROVED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");
    }

    // [auto] The APPROVED transition is permitted with an APPROVED review from a REVIEWER

    @Test
    void approveEdgeAppearsAndIsPermittedOnceAReviewerApproves() {
        moveTo("IN_REVIEW");
        assignReviewer(reviewer);

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewer);

        assertThat(targetStatuses(author)).contains("APPROVED");
        assertThatCode(() -> moveTo("APPROVED")).doesNotThrowAnyException();
        assertThat(reload().getCurrentStatus()).isEqualTo("APPROVED");
    }

    @Test
    void nonReviewerApprovalDoesNotSatisfyTheGateButAnAdminApprovalDoes() {
        moveTo("IN_REVIEW");

        // A CREATOR is a project member but not the reviewerRole the transition declares.
        recordReview(writer, "APPROVED");
        assertThat(targetStatuses(author)).doesNotContain("APPROVED");
        assertThatThrownBy(() -> moveTo("APPROVED")).isInstanceOf(UnprocessableEntityException.class);

        // An ADMIN outranks any review role, so their approval satisfies a reviewerRole=REVIEWER gate.
        recordReview(author, "APPROVED");
        assertThat(targetStatuses(author)).contains("APPROVED");
        assertThatCode(() -> moveTo("APPROVED")).doesNotThrowAnyException();
    }

    // [auto] A CHANGES_REQUESTED verdict routes the Post to CHANGES_REQUESTED

    @Test
    void changesRequestedVerdictRoutesThePostToTheChangesRequestedStatus() {
        moveTo("IN_REVIEW");
        assignReviewer(reviewer);

        reviewService.submitReview(project.getId(), post.getId(), "CHANGES_REQUESTED", "tighten the hook", reviewer);

        assertThat(reload().getCurrentStatus()).isEqualTo("CHANGES_REQUESTED");
    }

    // [auto] Re-approval is required on resubmission

    @Test
    void resubmittingAfterChangesRequestedStillRequiresAFreshApproval() {
        moveTo("IN_REVIEW");
        assignReviewer(reviewer);
        reviewService.submitReview(project.getId(), post.getId(), "CHANGES_REQUESTED", "tighten the hook", reviewer);

        moveTo("IN_REVIEW");

        assertThat(targetStatuses(author)).doesNotContain("APPROVED");
        assertThatThrownBy(() -> moveTo("APPROVED")).isInstanceOf(UnprocessableEntityException.class);

        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "better", reviewer);

        assertThat(targetStatuses(author)).contains("APPROVED");
        assertThatCode(() -> moveTo("APPROVED")).doesNotThrowAnyException();
    }

    // [auto] Reaching APPROVED fires conductor.work_item.status_changed and starts a matching automation

    @Test
    void reachingApprovedStartsTheStatusFilteredAutomationAndOnlyThatOne() throws Exception {
        WorkflowDefinition onApproved = automationFilteredOn("APPROVED");
        WorkflowDefinition onPublished = automationFilteredOn("PUBLISHED");

        moveTo("IN_REVIEW");
        assignReviewer(reviewer);
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewer);
        moveTo("APPROVED");

        List<WorkflowRun> runs = workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(onApproved.getId());
        assertThat(runs).hasSize(1);
        WorkflowRun run = runs.getFirst();
        assertThat(run.getTriggerType()).isEqualTo("conductor.work_item.status_changed");

        JsonNode payload = objectMapper.readTree(run.getEventPayload());
        assertThat(payload.path("type").asText()).isEqualTo("conductor.work_item.status_changed");
        assertThat(payload.path("workItemId").asText()).isEqualTo(post.getId());
        assertThat(payload.path("toStatus").asText()).isEqualTo("APPROVED");
        assertThat(payload.path("workflow").asText()).isEqualTo(MARKETING);
        assertThat(payload.path("noun").asText()).isEqualTo("Post");

        assertThat(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(onPublished.getId())).isEmpty();
    }

    // --- helpers ---

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }

    private void addMember(User user, MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.save(member);
    }

    private void assignReviewer(User user) {
        WorkItemReviewer assignment = new WorkItemReviewer();
        assignment.setWorkItemId(post.getId());
        assignment.setUserId(user.getId());
        assignment.setAssignedBy(author.getId());
        workItemReviewerRepository.save(assignment);
    }

    private void recordReview(User user, String verdict) {
        Review review = new Review();
        review.setWorkItemId(post.getId());
        review.setReviewerId(user.getId());
        review.setVerdict(verdict);
        reviewRepository.save(review);
    }

    private void moveTo(String status) {
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, status, null, null, null, author);
    }

    private WorkItem reload() {
        return workItemRepository.findById(post.getId()).orElseThrow();
    }

    private List<String> targetStatuses(User caller) {
        AvailableTransitionsView view =
                workItemWorkflowService.availableTransitions(project.getId(), post.getId(), caller);
        return view.transitions().stream().map(AvailableTransitionsView.Transition::toStatus).toList();
    }

    private WorkflowDefinition automationFilteredOn(String status) {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setProject(project);
        workflow.setName("on-" + status);
        workflow.setEnabled(true);
        workflow.setYaml("""
                name: on-%s
                on:
                  conductor.work_item.status_changed:
                    filters:
                      status: [%s]
                jobs:
                  announce:
                    steps:
                      - type: http
                        url: http://127.0.0.1:1/unreachable
                """.formatted(status, status));
        return workflowDefinitionRepository.save(workflow);
    }
}
