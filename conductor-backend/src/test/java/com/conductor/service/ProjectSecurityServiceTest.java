package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.security.ApiKeyAuthenticationToken;
import com.conductor.security.WorkflowRunAuthenticationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers the project-access gate that five controllers previously each carried their own copy of.
 */
@ExtendWith(MockitoExtension.class)
class ProjectSecurityServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    private ProjectSecurityService service() {
        return new ProjectSecurityService(projectMemberRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private User authenticateUser() {
        User user = new User();
        user.setId("user-1");
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return user;
    }

    private void authenticate(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void memberWithRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1"))
                .thenReturn(Optional.of(member));
    }

    @Test
    void resolvesAProjectMemberToAUserActor() {
        User user = authenticateUser();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);

        ProjectActor actor = service().requireProjectAccess(PROJECT_ID);

        assertThat(actor.user()).isEqualTo(user);
        assertThat(actor.isMachine()).isFalse();
        assertThat(actor.label()).isNull();
    }

    @Test
    void refusesAUserWhoIsNotAMember() {
        authenticateUser();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service().requireProjectAccess(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void resolvesAProjectApiKeyToAnAgentActor() {
        authenticate(new ApiKeyAuthenticationToken(PROJECT_ID));

        ProjectActor actor = service().requireProjectAccess(PROJECT_ID);

        assertThat(actor.isMachine()).isTrue();
        assertThat(actor.label()).isEqualTo("Agent");
    }

    @Test
    void namesTheRunInTheLabelSoAgentWritesStayTraceable() {
        authenticate(new WorkflowRunAuthenticationToken(PROJECT_ID, "abcdef1234567890"));

        assertThat(service().requireProjectAccess(PROJECT_ID).label()).isEqualTo("Agent (run abcdef12)");
    }

    @Test
    void refusesACredentialScopedToAnotherProject() {
        authenticate(new ApiKeyAuthenticationToken("proj-2"));

        assertThatThrownBy(() -> service().requireProjectAccess(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void refusesAnAnonymousCaller() {
        assertThatThrownBy(() -> service().requireProjectAccess(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editorGateAdmitsAdminsAndCreatorsButNotReviewers() {
        authenticateUser();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);

        memberWithRole(MemberRole.CREATOR);
        assertThat(service().requireProjectEditor(PROJECT_ID).userId()).isEqualTo("user-1");

        memberWithRole(MemberRole.REVIEWER);
        assertThatThrownBy(() -> service().requireProjectEditor(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN or CREATOR");
    }

    @Test
    void editorGateAdmitsAMachineCredentialWithoutARole() {
        authenticate(new ApiKeyAuthenticationToken(PROJECT_ID));

        // A project key has no membership row to hold a role; it is issued for the project and acts
        // on its behalf, which is what lets an agent maintain docs at all.
        assertThat(service().requireProjectEditor(PROJECT_ID).isMachine()).isTrue();
    }
}
