package com.conductor.service;

import com.conductor.service.publish.PublishGateEvaluator;
import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.BusinessException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.service.view.AvailableTransitionsView;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.SystemTriggerRegistry;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the Workflow seam with the REAL resolver + ENGINEERING statechart (resolver backed by a mock
 * version repo stubbed to return the seeded ENGINEERING published snapshot). This is where the byte-for-byte
 * no-regression behavior (AC-P0-1.1) plus the COND-18 generalizations (String statuses/types, system
 * transitions, the role-scoped Review gate) are verified end to end through the engine.
 */
class WorkItemWorkflowServiceTest {

    private static final String PROJECT_ID = "proj-1";

    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private ProjectMemberRepository projectMemberRepository;
    private ReviewRepository reviewRepository;
    private WorkflowDefinitionVersionRepository versionRepository;
    private PublishGateEvaluator publishGateEvaluator;
    private WorkItemWorkflowService service;

    @BeforeEach
    void setUp() {
        workItemRepository = Mockito.mock(WorkItemRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        projectMemberRepository = Mockito.mock(ProjectMemberRepository.class);
        reviewRepository = Mockito.mock(ReviewRepository.class);
        // Resolution is DB-only: back the resolver with a mock version repo returning the seeded ENGINEERING
        // published snapshot (v1) for both latest and version-pinned lookups.
        versionRepository = Mockito.mock(WorkflowDefinitionVersionRepository.class);
        WorkflowDefinitionVersion snapshot = engineeringSnapshot();
        when(versionRepository.findLatestPublished(any(), eq("ENGINEERING"))).thenReturn(Optional.of(snapshot));
        when(versionRepository.findByProjectSlugAndVersion(any(), eq("ENGINEERING"), eq(1)))
                .thenReturn(Optional.of(snapshot));
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository);
        SystemTriggerRegistry systemTriggerRegistry = new SystemTriggerRegistry(new ObjectMapper());
        PublishBundleHasher publishBundleHasher = Mockito.mock(PublishBundleHasher.class);
        publishGateEvaluator = Mockito.mock(PublishGateEvaluator.class);
        service = new WorkItemWorkflowService(workItemRepository, projectSecurityService, projectMemberRepository,
                reviewRepository, resolver, systemTriggerRegistry, publishBundleHasher, publishGateEvaluator);
    }

    /**
     * Pins the T4.3 wiring: {@code validateTransition} must hand every legal transition to
     * {@link PostScheduleValidator}, which decides for itself whether the publish-bundle rules apply. The
     * validator's own behavior is covered exhaustively by {@code PostScheduleValidatorTest}; what is asserted
     * here is only that the seam is actually called, since a silently-unwired validator would let an
     * incomplete Post reach APPROVED.
     */
    @Test
    void validateTransitionDelegatesToPostScheduleValidator() {
        WorkItem item = workItemAt("DRAFT");

        service.validateTransition(PROJECT_ID, item, "IN_REVIEW");

        verify(publishGateEvaluator).enforce(eq(item), any(), eq("IN_REVIEW"));
    }

    /** An illegal transition must fail before the publish-bundle validator is ever consulted. */
    @Test
    void validateTransitionSkipsPostScheduleValidatorOnAnIllegalMove() {
        WorkItem item = workItemAt("DRAFT");

        assertThatThrownBy(() -> service.validateTransition(PROJECT_ID, item, "DONE"))
                .isInstanceOf(BusinessException.class);

        verify(publishGateEvaluator, never()).enforce(any(), any(), any());
    }

    private WorkflowDefinitionVersion engineeringSnapshot() {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            WorkflowDefinitionVersion v = new WorkflowDefinitionVersion();
            v.setVersion(1);
            v.setDefinition(new ObjectMapper().readTree(in));
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkItem workItemAt(String status) {
        WorkItem workItem = new WorkItem();
        workItem.setId("issue-1");
        Project project = new Project();
        project.setId(PROJECT_ID);
        workItem.setProject(project);
        workItem.setCurrentStatus(status);
        workItem.setWorkflow("ENGINEERING");
        workItem.setWorkflowVersion(1);
        return workItem;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    // --- validateTransition ---

    @Test
    void allowsTransitionOnTheEngineeringEdge() {
        assertThatCode(() -> service.validateTransition(PROJECT_ID, workItemAt("DRAFT"), "IN_REVIEW"))
                .doesNotThrowAnyException();
        // the IN_REVIEW -> DRAFT back-edge
        assertThatCode(() -> service.validateTransition(PROJECT_ID, workItemAt("IN_REVIEW"), "DRAFT"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIllegalTransitionWithTheExactLegacyMessage() {
        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, workItemAt("DRAFT"), "READY_FOR_DEVELOPMENT"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT");
    }

    @Test
    void rejectsUnknownStatusWithWorkflowScopedMessage() {
        assertThatThrownBy(() -> service.validateTransition(PROJECT_ID, workItemAt("DRAFT"), "BOGUS"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unknown status 'BOGUS' for workflow ENGINEERING");
    }

    @Test
    void defaultsToEngineeringWhenUnbound() {
        WorkItem unbound = workItemAt("DRAFT");
        unbound.setWorkflow(null);
        unbound.setWorkflowVersion(null);
        assertThatCode(() -> service.validateTransition(PROJECT_ID, unbound, "IN_REVIEW"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateTransition(PROJECT_ID, unbound, "DONE"))
                .isInstanceOf(BusinessException.class);
    }

    // --- validateType ---

    @Test
    void validateTypeAllowsDeclaredTypes() {
        assertThatCode(() -> service.validateType(PROJECT_ID, "ENGINEERING", "PRD"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validateType(PROJECT_ID, "ENGINEERING", "FEATURE_REQUEST"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validateType(PROJECT_ID, "ENGINEERING", "BUG_REPORT"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateTypeRejectsDisallowedType() {
        assertThatThrownBy(() -> service.validateType(PROJECT_ID, "ENGINEERING", "EPIC"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Type 'EPIC' is not allowed by workflow ENGINEERING");
    }

    // --- initialStatus / boundVersion ---

    @Test
    void initialStatusIsDraftForEngineering() {
        assertThat(service.initialStatus(PROJECT_ID, "ENGINEERING")).isEqualTo("DRAFT");
    }

    @Test
    void boundVersionForEngineeringIsTheBuiltInVersion() {
        assertThat(service.boundVersion(PROJECT_ID, "ENGINEERING")).isEqualTo(1);
    }

    // --- applySystemTransition (e.g. a merged PR) ---

    @Test
    void applySystemTransitionAdvancesOnPrMergedAndBypassesReviewGate() {
        WorkItem workItem = workItemAt("CODE_REVIEW");
        // No approved review stubbed: the system trigger is the authority, so the gate must NOT apply.
        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, workItem, WorkItemWorkflowService.TRIGGER_PR_MERGED);

        assertThat(applied).isPresent();
        assertThat(applied.get().to()).isEqualTo("DONE");
        assertThat(workItem.getCurrentStatus()).isEqualTo("DONE");
        // gate bypassed → no review lookups at all
        verify(reviewRepository, never()).existsApprovedByReviewerRole(any(), any(), any(), any());
        verify(reviewRepository, never()).existsByWorkItemIdAndVerdict(any(), any());
    }

    @Test
    void applySystemTransitionRunsThePublishGateBeforeMoving() {
        WorkItem workItem = workItemAt("CODE_REVIEW");
        Mockito.doThrow(new com.conductor.exception.UnprocessableEntityException("Cannot move Item to DONE: nope"))
                .when(publishGateEvaluator).enforce(eq(workItem), any(), eq("DONE"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.applySystemTransition(
                PROJECT_ID, workItem, WorkItemWorkflowService.TRIGGER_PR_MERGED))
                .isInstanceOf(com.conductor.exception.UnprocessableEntityException.class);
        assertThat(workItem.getCurrentStatus()).as("refused before the status was set").isEqualTo("CODE_REVIEW");
    }

    @Test
    void applySystemTransitionReturnsEmptyWhenNoTriggeredEdge() {
        WorkItem workItem = workItemAt("DRAFT"); // DRAFT has no pr_merged edge
        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, workItem, WorkItemWorkflowService.TRIGGER_PR_MERGED);

        assertThat(applied).isEmpty();
        assertThat(workItem.getCurrentStatus()).isEqualTo("DRAFT");
    }

    // A custom lifecycle whose REVIEW -> DONE edge is review-gated AND fires on the status_changed trigger,
    // so we can prove status_changed (unlike pr_merged) HONORS the Review gate.
    private static final String STATUS_CHANGED_LIFECYCLE = """
            {
              "schemaVersion": 1, "id": "LC_TEST", "area": "LC", "version": 1, "state": "PUBLISHED",
              "noun": "Item", "default_view": "list", "types": ["TASK"],
              "statuses": [
                {"id": "OPEN", "category": "open", "initial": true},
                {"id": "REVIEW", "category": "in_progress"},
                {"id": "DONE", "category": "terminal", "terminal": true}
              ],
              "transitions": [
                {"from": "OPEN", "to": "REVIEW", "label": "Start"},
                {"from": "REVIEW", "to": "DONE", "label": "Auto-finish", "trigger": "status_changed",
                 "requiresReview": true, "reviewerRole": "REVIEWER", "reviewOutcomes": ["APPROVED"]}
              ]
            }
            """;

    private WorkItem lcTestWorkItemAtReview() {
        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setVersion(1);
        try {
            snapshot.setDefinition(new ObjectMapper().readTree(STATUS_CHANGED_LIFECYCLE));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(versionRepository.findLatestPublished(any(), eq("LC_TEST"))).thenReturn(Optional.of(snapshot));
        when(versionRepository.findByProjectSlugAndVersion(any(), eq("LC_TEST"), eq(1)))
                .thenReturn(Optional.of(snapshot));
        WorkItem workItem = workItemAt("REVIEW");
        workItem.setWorkflow("LC_TEST");
        return workItem;
    }

    @Test
    void statusChangedTriggerHonorsReviewGateAndDoesNotAdvanceWhenUnsatisfied() {
        WorkItem workItem = lcTestWorkItemAtReview();
        // No approved REVIEWER review stubbed -> the gate is unsatisfied, so status_changed must NOT advance.
        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, workItem, WorkItemWorkflowService.TRIGGER_STATUS_CHANGED);

        assertThat(applied).isEmpty();
        assertThat(workItem.getCurrentStatus()).isEqualTo("REVIEW");
        verify(reviewRepository).existsApprovedByReviewerRole("issue-1", PROJECT_ID, "APPROVED", "REVIEWER");
    }

    @Test
    void statusChangedTriggerAdvancesOnceReviewGateIsSatisfied() {
        WorkItem workItem = lcTestWorkItemAtReview();
        when(reviewRepository.existsApprovedByReviewerRole("issue-1", PROJECT_ID, "APPROVED", "REVIEWER"))
                .thenReturn(true);

        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, workItem, WorkItemWorkflowService.TRIGGER_STATUS_CHANGED);

        assertThat(applied).isPresent();
        assertThat(workItem.getCurrentStatus()).isEqualTo("DONE");
    }

    // --- availableTransitions (doer projection) ---

    @Test
    void availableTransitionsReturnsEdgesForNonReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(workItemAt("DRAFT")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.CREATOR);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        AvailableTransitionsView response = service.availableTransitions(PROJECT_ID, "issue-1", caller());

        assertThat(response.workflow()).isEqualTo("ENGINEERING");
        assertThat(response.currentStatus()).isEqualTo("DRAFT");
        assertThat(response.noun()).isEqualTo("Issue");
        assertThat(response.transitions()).extracting(AvailableTransitionsView.Transition::toStatus)
                .containsExactlyInAnyOrder("IN_REVIEW", "CLOSED");
    }

    // --- role-scoped Review gate (Wave 4): CODE_REVIEW -> DONE has reviewerRole REVIEWER ---

    @Test
    void reviewGatedMergeIsBlockedWithoutReviewerApproval() {
        // Default false: no APPROVED review from a REVIEWER (or ADMIN) exists.
        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, workItemAt("CODE_REVIEW"), "DONE"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");

        // The gate is role-scoped — it queries the REVIEWER-scoped projection, not the generic verdict check.
        verify(reviewRepository).existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", "REVIEWER");
        verify(reviewRepository, never()).existsByWorkItemIdAndVerdict(any(), any());
    }

    @Test
    void reviewGatedMergeIsAllowedWithReviewerApproval() {
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", "REVIEWER")).thenReturn(true);

        assertThatCode(() ->
                service.validateTransition(PROJECT_ID, workItemAt("CODE_REVIEW"), "DONE"))
                .doesNotThrowAnyException();
    }

    @Test
    void reviewGatedMergeIsNotSatisfiedByNonReviewerApproval() {
        // A non-REVIEWER (e.g. CREATOR) APPROVED review does NOT satisfy a reviewerRole=REVIEWER gate, so the
        // role-scoped projection returns false and the move is blocked.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", "REVIEWER")).thenReturn(false);

        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, workItemAt("CODE_REVIEW"), "DONE"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");
    }

    @Test
    void availableTransitionsHidesGatedMergeUntilApproved() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(workItemAt("CODE_REVIEW")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.CREATOR);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        // No approval: the gated DONE edge is hidden; only CLOSED shows.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", "REVIEWER")).thenReturn(false);
        assertThat(service.availableTransitions(PROJECT_ID, "issue-1", caller()).transitions())
                .extracting(AvailableTransitionsView.Transition::toStatus).containsExactly("CLOSED");

        // Reviewer-approved: DONE appears.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", "REVIEWER")).thenReturn(true);
        assertThat(service.availableTransitions(PROJECT_ID, "issue-1", caller()).transitions())
                .extracting(AvailableTransitionsView.Transition::toStatus).containsExactlyInAnyOrder("DONE", "CLOSED");
    }

    @Test
    void availableTransitionsIsEmptyForReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(workItemRepository.findById("issue-1")).thenReturn(Optional.of(workItemAt("DRAFT")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        AvailableTransitionsView response = service.availableTransitions(PROJECT_ID, "issue-1", caller());

        assertThat(response.transitions()).isEmpty();
    }
}
