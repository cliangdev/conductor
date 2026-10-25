package com.conductor.controller;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.security.ApiKeyAuthenticationToken;
import com.conductor.security.UserApiKeyAuthenticationToken;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.conductor.service.DaemonEventService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.JobDispatchPayloadService;
import com.conductor.workflow.WorkflowJobOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may claim and complete a run's job on the daemon endpoints: the project's key, the run's own token,
 * and (a daemon signed in with {@code conductor login}) a user API key of an ADMIN or CREATOR of the run's
 * project. A REVIEWER, a non-member and another project's key are refused.
 */
class DaemonEventsControllerAuthTest {

    private final JobDispatchPayloadService payloads = Mockito.mock(JobDispatchPayloadService.class);
    private final WorkflowRunRepository runRepository = Mockito.mock(WorkflowRunRepository.class);
    private final ProjectMemberRepository memberRepository = Mockito.mock(ProjectMemberRepository.class);
    private DaemonEventsController controller;

    @BeforeEach
    void setUp() {
        Project project = new Project();
        project.setId("proj-1");
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setProject(project);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(workflow);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));
        controller = new DaemonEventsController(Mockito.mock(DaemonEventService.class), payloads,
                Mockito.mock(WorkflowJobOrchestrator.class), runRepository,
                new ProjectSecurityService(memberRepository));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void membership(String userId, MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(memberRepository.findByProjectIdAndUserId("proj-1", userId)).thenReturn(Optional.of(member));
    }

    private static User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @Test
    void aUserApiKeyOfAnAdminMayClaimTheJob() {
        membership("u-admin", MemberRole.ADMIN);
        authenticate(new UserApiKeyAuthenticationToken(user("u-admin")));

        controller.getJobDispatchPayload("run-1", "render");

        verify(payloads).buildPayload("run-1", "render");
    }

    @Test
    void aUserApiKeyOfACreatorMayCompleteTheJob() {
        membership("u-creator", MemberRole.CREATOR);
        authenticate(new UserApiKeyAuthenticationToken(user("u-creator")));

        controller.getJobDispatchPayload("run-1", "render");

        verify(payloads).buildPayload("run-1", "render");
    }

    @Test
    void aBrowserJwtSessionOfAnAdminIsRefused() {
        membership("u-admin", MemberRole.ADMIN);
        User admin = user("u-admin");
        authenticate(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                admin, null, java.util.List.of()));

        assertThatThrownBy(() -> controller.getJobDispatchPayload("run-1", "render"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.completeWorkflowJob("run-1", "render",
                new com.conductor.generated.model.CompleteWorkflowJobRequest().status("SUCCEEDED")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aReviewerIsRefused() {
        membership("u-reviewer", MemberRole.REVIEWER);
        authenticate(new UserApiKeyAuthenticationToken(user("u-reviewer")));

        assertThatThrownBy(() -> controller.getJobDispatchPayload("run-1", "render"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aNonMemberIsRefused() {
        when(memberRepository.findByProjectIdAndUserId("proj-1", "u-stranger")).thenReturn(Optional.empty());
        authenticate(new UserApiKeyAuthenticationToken(user("u-stranger")));

        assertThatThrownBy(() -> controller.getJobDispatchPayload("run-1", "render"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theProjectKeyStillWorksAndAnotherProjectsKeyDoesNot() {
        authenticate(new ApiKeyAuthenticationToken("proj-1"));
        controller.getJobDispatchPayload("run-1", "render");
        verify(payloads).buildPayload("run-1", "render");

        authenticate(new ApiKeyAuthenticationToken("proj-2"));
        assertThatThrownBy(() -> controller.getJobDispatchPayload("run-1", "render"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRunTokenIsStillPinnedToItsOwnRun() {
        authenticate(new WorkflowRunAuthenticationToken("proj-1", "run-OTHER"));

        assertThatThrownBy(() -> controller.getJobDispatchPayload("run-1", "render"))
                .isInstanceOf(ForbiddenException.class);
    }
}
