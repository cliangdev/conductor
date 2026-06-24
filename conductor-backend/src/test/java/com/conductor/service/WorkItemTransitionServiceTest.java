package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.AvailableTransition;
import com.conductor.generated.model.AvailableTransitionsResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.conductor.workflow.lifecycle.WorkflowEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Exercises the transition seam with the REAL {@link WorkflowEngine} + built-in ENGINEERING statechart
 * (resolver backed by a mock repo → no DB rows → built-in). This is where the byte-for-byte no-regression
 * behavior (AC-P0-1.1) is verified end to end through the engine.
 */
class WorkItemTransitionServiceTest {

    private static final String PROJECT_ID = "proj-1";

    private IssueRepository issueRepository;
    private ProjectSecurityService projectSecurityService;
    private ProjectMemberRepository projectMemberRepository;
    private WorkItemTransitionService service;

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        projectMemberRepository = Mockito.mock(ProjectMemberRepository.class);
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(
                Mockito.mock(WorkflowDefinitionRepository.class), new ObjectMapper());
        service = new WorkItemTransitionService(
                issueRepository, projectSecurityService, projectMemberRepository, resolver, new WorkflowEngine());
    }

    private Issue issueAt(IssueStatus status) {
        Issue issue = new Issue();
        issue.setId("issue-1");
        Project project = new Project();
        project.setId(PROJECT_ID);
        issue.setProject(project);
        issue.setStatus(status);
        issue.setWorkflow("ENGINEERING");
        return issue;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void allowsTransitionOnTheEngineeringEdge() {
        assertThatCode(() -> service.validateTransition(PROJECT_ID, issueAt(IssueStatus.DRAFT), IssueStatus.IN_REVIEW))
                .doesNotThrowAnyException();
        // the IN_REVIEW -> DRAFT back-edge
        assertThatCode(() -> service.validateTransition(PROJECT_ID, issueAt(IssueStatus.IN_REVIEW), IssueStatus.DRAFT))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIllegalTransitionWithTheExactLegacyMessage() {
        assertThatThrownBy(() ->
                service.validateTransition(PROJECT_ID, issueAt(IssueStatus.DRAFT), IssueStatus.READY_FOR_DEVELOPMENT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT");
    }

    @Test
    void defaultsToEngineeringWhenUnbound() {
        Issue unbound = issueAt(IssueStatus.DRAFT);
        unbound.setWorkflow(null);
        assertThatCode(() -> service.validateTransition(PROJECT_ID, unbound, IssueStatus.IN_REVIEW))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateTransition(PROJECT_ID, unbound, IssueStatus.DONE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void availableTransitionsReturnsEdgesForNonReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issueAt(IssueStatus.DRAFT)));
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

    @Test
    void availableTransitionsIsEmptyForReviewer() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issueAt(IssueStatus.DRAFT)));
        ProjectMember member = new ProjectMember();
        member.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));

        AvailableTransitionsResponse response = service.availableTransitions(PROJECT_ID, "issue-1", caller());

        assertThat(response.getTransitions()).isEmpty();
    }
}
