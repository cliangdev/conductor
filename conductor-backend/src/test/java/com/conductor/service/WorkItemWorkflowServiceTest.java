package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.model.AvailableTransition;
import com.conductor.generated.model.AvailableTransitionsResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the Workflow seam with the REAL resolver + built-in ENGINEERING statechart (resolver backed by a
 * mock version repo → no published snapshots → built-in). This is where the byte-for-byte no-regression
 * behavior (AC-P0-1.1) plus the COND-18 generalizations (String statuses/types, system transitions, the
 * role-scoped Review gate) are verified end to end through the engine.
 */
class WorkItemWorkflowServiceTest {

    private static final String PROJECT_ID = "proj-1";

    private IssueRepository issueRepository;
    private ProjectSecurityService projectSecurityService;
    private ProjectMemberRepository projectMemberRepository;
    private ReviewRepository reviewRepository;
    private WorkItemWorkflowService service;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        projectMemberRepository = Mockito.mock(ProjectMemberRepository.class);
        reviewRepository = Mockito.mock(ReviewRepository.class);
        // No published snapshots → resolver falls back to the built-in ENGINEERING classpath definition.
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(
                Mockito.mock(WorkflowDefinitionVersionRepository.class), new ObjectMapper());
        service = new WorkItemWorkflowService(issueRepository, projectSecurityService, projectMemberRepository,
                reviewRepository, resolver);
    }

    private Issue issueAt(String status) {
        Issue issue = new Issue();
        issue.setId("issue-1");
        Project project = new Project();
        project.setId(PROJECT_ID);
        issue.setProject(project);
        issue.setCurrentStatus(status);
        issue.setWorkflow("ENGINEERING");
        issue.setWorkflowVersion(1);
        return issue;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    // --- validateTransition ---

    @Test
    void allowsTransitionOnTheEngineeringEdge() {
        assertThatCode(() -> service.validateTransition(PROJECT_ID, issueAt("DRAFT"), "IN_REVIEW"))
                .doesNotThrowAnyException();
        // the IN_REVIEW -> DRAFT back-edge
        assertThatCode(() -> service.validateTransition(PROJECT_ID, issueAt("IN_REVIEW"), "DRAFT"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIllegalTransitionWithTheExactLegacyMessage() {
        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, issueAt("DRAFT"), "READY_FOR_DEVELOPMENT"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT");
    }

    @Test
    void rejectsUnknownStatusWithWorkflowScopedMessage() {
        assertThatThrownBy(() -> service.validateTransition(PROJECT_ID, issueAt("DRAFT"), "BOGUS"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unknown status 'BOGUS' for workflow ENGINEERING");
    }

    @Test
    void defaultsToEngineeringWhenUnbound() {
        Issue unbound = issueAt("DRAFT");
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
        Issue issue = issueAt("CODE_REVIEW");
        // No approved review stubbed: the system trigger is the authority, so the gate must NOT apply.
        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, issue, WorkItemWorkflowService.TRIGGER_PR_MERGED);

        assertThat(applied).isPresent();
        assertThat(applied.get().to()).isEqualTo("DONE");
        assertThat(issue.getCurrentStatus()).isEqualTo("DONE");
        // gate bypassed → no review lookups at all
        verify(reviewRepository, never()).existsApprovedByReviewerRole(any(), any(), any(), any());
        verify(reviewRepository, never()).existsByIssueIdAndVerdict(any(), any());
    }

    @Test
    void applySystemTransitionReturnsEmptyWhenNoTriggeredEdge() {
        Issue issue = issueAt("DRAFT"); // DRAFT has no pr_merged edge
        Optional<StatechartTransition> applied = service.applySystemTransition(
                PROJECT_ID, issue, WorkItemWorkflowService.TRIGGER_PR_MERGED);

        assertThat(applied).isEmpty();
        assertThat(issue.getCurrentStatus()).isEqualTo("DRAFT");
    }

    // --- availableTransitions (doer projection) ---

    @Test
    void availableTransitionsReturnsEdgesForNonReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issueAt("DRAFT")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.CREATOR);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        AvailableTransitionsResponse response = service.availableTransitions(PROJECT_ID, "issue-1", caller());

        assertThat(response.getWorkflow()).isEqualTo("ENGINEERING");
        assertThat(response.getCurrentStatus()).isEqualTo("DRAFT");
        assertThat(response.getNoun()).isEqualTo("Issue");
        assertThat(response.getTransitions()).extracting(AvailableTransition::getToStatus)
                .containsExactlyInAnyOrder("IN_REVIEW", "CLOSED");
    }

    // --- role-scoped Review gate (Wave 4): CODE_REVIEW -> DONE has reviewerRole REVIEWER ---

    @Test
    void reviewGatedMergeIsBlockedWithoutReviewerApproval() {
        // Default false: no APPROVED review from a REVIEWER (or ADMIN) exists.
        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, issueAt("CODE_REVIEW"), "DONE"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");

        // The gate is role-scoped — it queries the REVIEWER-scoped projection, not the generic verdict check.
        verify(reviewRepository).existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", MemberRole.REVIEWER);
        verify(reviewRepository, never()).existsByIssueIdAndVerdict(any(), any());
    }

    @Test
    void reviewGatedMergeIsAllowedWithReviewerApproval() {
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", MemberRole.REVIEWER)).thenReturn(true);

        assertThatCode(() ->
                service.validateTransition(PROJECT_ID, issueAt("CODE_REVIEW"), "DONE"))
                .doesNotThrowAnyException();
    }

    @Test
    void reviewGatedMergeIsNotSatisfiedByNonReviewerApproval() {
        // A non-REVIEWER (e.g. CREATOR) APPROVED review does NOT satisfy a reviewerRole=REVIEWER gate, so the
        // role-scoped projection returns false and the move is blocked.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", MemberRole.REVIEWER)).thenReturn(false);

        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, issueAt("CODE_REVIEW"), "DONE"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("requires an approved review");
    }

    @Test
    void availableTransitionsHidesGatedMergeUntilApproved() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issueAt("CODE_REVIEW")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.CREATOR);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        // No approval: the gated DONE edge is hidden; only CLOSED shows.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", MemberRole.REVIEWER)).thenReturn(false);
        assertThat(service.availableTransitions(PROJECT_ID, "issue-1", caller()).getTransitions())
                .extracting(AvailableTransition::getToStatus).containsExactly("CLOSED");

        // Reviewer-approved: DONE appears.
        when(reviewRepository.existsApprovedByReviewerRole(
                "issue-1", PROJECT_ID, "APPROVED", MemberRole.REVIEWER)).thenReturn(true);
        assertThat(service.availableTransitions(PROJECT_ID, "issue-1", caller()).getTransitions())
                .extracting(AvailableTransition::getToStatus).containsExactlyInAnyOrder("DONE", "CLOSED");
    }

    @Test
    void availableTransitionsIsEmptyForReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issueAt("DRAFT")));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        AvailableTransitionsResponse response = service.availableTransitions(PROJECT_ID, "issue-1", caller());

        assertThat(response.getTransitions()).isEmpty();
    }
}
