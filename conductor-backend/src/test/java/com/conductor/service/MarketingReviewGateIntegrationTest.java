package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * <p>Every Post here is set up with a complete publish bundle (see {@code completePublishBundle}) because
 * {@code PostScheduleValidator} refuses the approval gate without one. That is deliberate rather than
 * incidental for {@code approveEdgeIsHiddenAndRejectedWhileThePostHasNoApprovedReview}: with the bundle
 * complete, the "requires an approved review" rejection can only be the review gate, which
 * {@code WorkItemWorkflowService#validateTransition} evaluates before the schedule validator.
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
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private PostPublishTargetRepository postPublishTargetRepository;
    @Autowired private AssetRepository assetRepository;
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
        completePublishBundle();
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
        // The approval itself moves the Post (review_approved cascades IN_REVIEW → APPROVED → SCHEDULED),
        // and the APPROVED hop fires status_changed exactly once.
        reviewService.submitReview(project.getId(), post.getId(), "APPROVED", "ship it", reviewer);
        assertThat(reload().getCurrentStatus()).isEqualTo("SCHEDULED");

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

    // [auto] One approval schedules the Post; a refused hop leaves the approval standing and says why

    @Test
    void oneApprovalSchedulesThePostAndReportsIt() {
        moveTo("IN_REVIEW");
        assignReviewer(reviewer);

        ReviewService.ReviewSubmission submission = reviewService.submitReviewWithOutcome(
                project.getId(), post.getId(), "APPROVED", "ship it", reviewer);

        assertThat(submission.autoTransition()).isPresent();
        assertThat(submission.autoTransition().get().applied()).isTrue();
        assertThat(submission.autoTransition().get().blocked()).isFalse();
        assertThat(submission.autoTransition().get().fromStatus()).isEqualTo("IN_REVIEW");
        assertThat(submission.autoTransition().get().toStatus()).isEqualTo("SCHEDULED");
        assertThat(reload().getCurrentStatus()).isEqualTo("SCHEDULED");
        assertThat(postPublishTargetRepository.findAllByWorkItemId(post.getId()))
                .allSatisfy(target -> assertThat(target.getFireTime()).isEqualTo(reload().getScheduledFor()));

        // A human Unschedule is a plain status change: nothing re-fires review_approved.
        moveTo("APPROVED");
        assertThat(reload().getCurrentStatus()).isEqualTo("APPROVED");
    }

    @Test
    void anApprovalTheGateRefusesStandsButMovesNothing() {
        // Fire time inside Facebook's floor: the bundle hash is what the approval binds to, and the review
        // gate is satisfied, but the publish gate on the very first hop refuses it.
        schedule(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        moveTo("IN_REVIEW");
        assignReviewer(reviewer);

        ReviewService.ReviewSubmission submission = reviewService.submitReviewWithOutcome(
                project.getId(), post.getId(), "APPROVED", "ship it", reviewer);

        assertThat(submission.autoTransition()).isPresent();
        assertThat(submission.autoTransition().get().blocked()).isTrue();
        assertThat(submission.autoTransition().get().applied()).isFalse();
        assertThat(submission.autoTransition().get().blockedReason()).contains("less than 10 minutes");
        assertThat(reload().getCurrentStatus()).isEqualTo("IN_REVIEW");
        // The approval stands: once the schedule is fixed the gate opens without a second review.
        assertThat(reviewRepository.findAllByWorkItemId(post.getId()))
                .anySatisfy(review -> assertThat(review.getVerdict()).isEqualTo("APPROVED"));
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

    /**
     * Everything {@link PostScheduleValidator} requires before a Post may cross the MARKETING approval gate:
     * a publish target, a fire time well clear of the ten-minute floor with a valid IANA zone, and an
     * uploaded media file. Applied at setup, before any review round opens, so an approval's bundle hash
     * covers the finished bundle and nothing here invalidates it later.
     */
    private void completePublishBundle() {
        addTarget("meta", "facebook", null);
        schedule(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        addUploadedAsset("teaser.png");
    }

    /** A publish target plus the {@code connection} row {@code post_publish_target.connection_id} points at. */
    private void addTarget(String connectorId, String platform, String captionOverride) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setAuthType("oauth2");
        connection.setStatus("ACTIVE");
        connection = connectionRepository.save(connection);

        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(reload());
        target.setConnectorId(connectorId);
        target.setConnectionId(connection.getId());
        target.setPlatform(platform);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PENDING);
        target.setCaptionOverride(captionOverride);
        target.setIdempotencyKey("key-" + UUID.randomUUID());
        postPublishTargetRepository.save(target);
    }

    /**
     * An UPLOADED {@code file} Asset, shaped the way {@link AssetService#createFileAsset} plus
     * {@link AssetService#confirmUpload} leave one. {@code gcs_path} and {@code content_type} are mandatory
     * for an UPLOADED row (the V132 {@code chk_assets_uploaded_has_storage} check), and {@code ref} is NOT NULL.
     */
    private void addUploadedAsset(String filename) {
        String assetId = UUID.randomUUID().toString();
        String gcsPath = "marketing-assets/" + project.getId() + "/" + post.getId() + "/" + assetId + "-" + filename;

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(reload());
        asset.setType("instagram_post");
        asset.setLabel(filename);
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(gcsPath);
        asset.setGcsPath(gcsPath);
        asset.setContentType("image/png");
        asset.setSizeBytes(2048L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        assetRepository.save(asset);
    }

    private void schedule(OffsetDateTime fireTime) {
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null, fireTime,
                "America/New_York", author);
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
