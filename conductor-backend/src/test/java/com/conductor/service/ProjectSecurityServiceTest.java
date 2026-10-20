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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        RequestContextHolder.resetRequestAttributes();
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

    // ── per-request membership cache ──────────────────────────────────────────────────────────

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test
    void withARequestBoundAPositiveMembershipIsAskedOnce() {
        bindRequest();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        ProjectSecurityService service = service();

        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();

        verify(projectMemberRepository, times(1)).existsByProjectIdAndUserId(PROJECT_ID, "user-1");
    }

    @Test
    void withARequestBoundARoleIsLoadedOnceAndAnswersMembershipToo() {
        bindRequest();
        memberWithRole(MemberRole.CREATOR);
        ProjectSecurityService service = service();

        assertThat(service.isAdminOrCreator(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectAdmin(PROJECT_ID, "user-1")).isFalse();
        assertThat(service.isAdminOrCreator(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();

        verify(projectMemberRepository, times(1)).findByProjectIdAndUserId(PROJECT_ID, "user-1");
        verify(projectMemberRepository, never()).existsByProjectIdAndUserId(PROJECT_ID, "user-1");
    }

    @Test
    void withNoRequestBoundNothingIsCached() {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        memberWithRole(MemberRole.ADMIN);
        ProjectSecurityService service = service();

        service.isProjectMember(PROJECT_ID, "user-1");
        service.isProjectMember(PROJECT_ID, "user-1");
        service.isProjectAdmin(PROJECT_ID, "user-1");
        service.isProjectAdmin(PROJECT_ID, "user-1");

        verify(projectMemberRepository, times(2)).existsByProjectIdAndUserId(PROJECT_ID, "user-1");
        verify(projectMemberRepository, times(2)).findByProjectIdAndUserId(PROJECT_ID, "user-1");
    }

    @Test
    void aNegativeAnswerIsNeverCachedSoAMidRequestGrantIsSeen() {
        bindRequest();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false, true);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());
        ProjectSecurityService service = service();

        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isFalse();
        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectAdmin(PROJECT_ID, "user-1")).isFalse();
        assertThat(service.isProjectAdmin(PROJECT_ID, "user-1")).isFalse();

        verify(projectMemberRepository, times(2)).existsByProjectIdAndUserId(PROJECT_ID, "user-1");
        // The admin check asked twice: the empty answer was not remembered.
        verify(projectMemberRepository, times(2)).findByProjectIdAndUserId(PROJECT_ID, "user-1");
    }

    @Test
    void theCacheIsKeyedByProjectAndUserSoNothingLeaksToAnotherPair() {
        bindRequest();
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-2")).thenReturn(false);
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-2", "user-1")).thenReturn(false);
        ProjectSecurityService service = service();

        assertThat(service.isProjectMember(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectMember(PROJECT_ID, "user-2")).isFalse();
        assertThat(service.isProjectMember("proj-2", "user-1")).isFalse();
    }

    @Test
    void aMemberRoleIsNotLeakedToAnotherProjectOrUser() {
        bindRequest();
        memberWithRole(MemberRole.ADMIN);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-2", "user-1")).thenReturn(Optional.empty());
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-2")).thenReturn(Optional.empty());
        ProjectSecurityService service = service();

        assertThat(service.isProjectAdmin(PROJECT_ID, "user-1")).isTrue();
        assertThat(service.isProjectAdmin("proj-2", "user-1")).isFalse();
        assertThat(service.isProjectAdmin(PROJECT_ID, "user-2")).isFalse();
    }

    @Test
    void aSecondRequestStartsWithAnEmptyCache() {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        ProjectSecurityService service = service();

        bindRequest();
        service.isProjectMember(PROJECT_ID, "user-1");
        bindRequest();
        service.isProjectMember(PROJECT_ID, "user-1");

        verify(projectMemberRepository, times(2)).existsByProjectIdAndUserId(PROJECT_ID, "user-1");
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

        memberWithRole(MemberRole.CREATOR);
        assertThat(service().requireProjectEditor(PROJECT_ID).userId()).isEqualTo("user-1");

        memberWithRole(MemberRole.REVIEWER);
        assertThatThrownBy(() -> service().requireProjectEditor(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN or CREATOR");
    }

    // --- interactive principal ---

    private void authenticateJwt(boolean fromHeader) {
        User user = new User();
        user.setId("user-1");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        auth.setDetails(new com.conductor.security.JwtAuthenticationDetails(
                new org.springframework.mock.web.MockHttpServletRequest(), fromHeader));
        authenticate(auth);
    }

    @Test
    void aHeaderJwtMemberIsAnInteractiveUser() {
        authenticateJwt(true);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);

        assertThat(service().requireInteractiveUser(PROJECT_ID).getId()).isEqualTo("user-1");
        assertThat(service().credentialType()).isEqualTo("JWT_HEADER");
    }

    @Test
    void aCookieOnlyJwtIsNotInteractive() {
        authenticateJwt(false);

        assertThatThrownBy(() -> service().requireInteractiveUser(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service().credentialType()).isEqualTo("JWT_COOKIE");
    }

    @Test
    void aUserApiKeyIsNotInteractive() {
        User user = new User();
        user.setId("user-1");
        authenticate(new com.conductor.security.UserApiKeyAuthenticationToken(user));

        assertThatThrownBy(() -> service().requireInteractiveUser(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service().credentialType()).isEqualTo("USER_API_KEY");
    }

    @Test
    void projectKeysAndRunTokensAreNotInteractive() {
        authenticate(new ApiKeyAuthenticationToken(PROJECT_ID));
        assertThatThrownBy(() -> service().requireInteractiveUser(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service().credentialType()).isEqualTo("PROJECT_API_KEY");

        authenticate(new WorkflowRunAuthenticationToken(PROJECT_ID, "run-1"));
        assertThatThrownBy(() -> service().requireInteractiveUser(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThat(service().credentialType()).isEqualTo("RUN_TOKEN");
    }

    @Test
    void aNonMemberInteractiveUserGetsTheMissingProjectNotFound() {
        authenticateJwt(true);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service().requireInteractiveUser(PROJECT_ID))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    void memberUserAdmitsAnApiKeyOwnerButNotAProjectKey() {
        User user = new User();
        user.setId("user-1");
        authenticate(new com.conductor.security.UserApiKeyAuthenticationToken(user));
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        assertThat(service().requireMemberUser(PROJECT_ID).getId()).isEqualTo("user-1");

        authenticate(new ApiKeyAuthenticationToken(PROJECT_ID));
        assertThatThrownBy(() -> service().requireMemberUser(PROJECT_ID)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editorGateAdmitsAMachineCredentialWithoutARole() {
        authenticate(new ApiKeyAuthenticationToken(PROJECT_ID));

        // A project key has no membership row to hold a role; it is issued for the project and acts
        // on its behalf, which is what lets an agent maintain docs at all.
        assertThat(service().requireProjectEditor(PROJECT_ID).isMachine()).isTrue();
    }
}
