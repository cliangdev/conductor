package com.conductor.service;

import com.conductor.entity.Invite;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.InviteExpiredException;
import com.conductor.generated.model.AcceptInviteResponse;
import com.conductor.generated.model.InviteResponse;
import com.conductor.repository.InviteRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private InviteRepository inviteRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private InviteService inviteService;

    private User admin;
    private User invitee;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId("admin-id");
        admin.setEmail("admin@example.com");
        admin.setName("Admin User");

        invitee = new User();
        invitee.setId("invitee-id");
        invitee.setEmail("invitee@example.com");
        invitee.setName("Invitee User");

        project = new Project();
        project.setId("proj-1");
        project.setName("Test Project");
        project.setCreatedBy(admin);
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());
    }

    // ─── createInvite tests ───────────────────────────────────────────────────

    @Test
    void createInviteReturns201WithInviteObject() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("invitee@example.com")).thenReturn(Optional.empty());
        when(inviteRepository.findByProjectIdAndEmailAndStatus("proj-1", "invitee@example.com", "PENDING"))
                .thenReturn(Optional.empty());
        when(inviteRepository.save(any(Invite.class))).thenAnswer(invocation -> {
            Invite inv = invocation.getArgument(0);
            inv.setId("invite-1");
            inv.setStatus("PENDING");
            return inv;
        });

        InviteResponse response = inviteService.createInvite("proj-1", "invitee@example.com", "CREATOR", admin);

        assertThat(response.getEmail()).isEqualTo("invitee@example.com");
        assertThat(response.getRole()).isEqualTo("CREATOR");
        assertThat(response.getExpiresAt()).isAfter(OffsetDateTime.now().plusHours(71));
        verify(emailService).sendInviteEmail(eq("invitee@example.com"), eq("Admin User"), eq("Test Project"), any());
    }

    @Test
    void createInviteDuplicateEmailReturnsWith409() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("invitee@example.com")).thenReturn(Optional.empty());

        Invite existing = new Invite();
        existing.setStatus("PENDING");
        when(inviteRepository.findByProjectIdAndEmailAndStatus("proj-1", "invitee@example.com", "PENDING"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> inviteService.createInvite("proj-1", "invitee@example.com", "CREATOR", admin))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Invite already pending for this email");
    }

    @Test
    void createInviteByNonAdminReturnsWith403() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(false);

        assertThatThrownBy(() -> inviteService.createInvite("proj-1", "invitee@example.com", "CREATOR", admin))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createInviteWithAdminRoleReturnsWith400() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);

        assertThatThrownBy(() -> inviteService.createInvite("proj-1", "invitee@example.com", "ADMIN", admin))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot invite with ADMIN role");
    }

    @Test
    void createInviteWhenUserAlreadyMemberReturnsWith409() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(userRepository.findByEmail("invitee@example.com")).thenReturn(Optional.of(invitee));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "invitee-id")).thenReturn(true);

        assertThatThrownBy(() -> inviteService.createInvite("proj-1", "invitee@example.com", "CREATOR", admin))
                .isInstanceOf(ConflictException.class)
                .hasMessage("User is already a project member");
    }

    // ─── acceptInvite tests ───────────────────────────────────────────────────

    @Test
    void acceptInviteValidTokenCreatesProjectMember() {
        Invite invite = buildPendingInvite("valid-token", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        User acceptor = new User();
        acceptor.setId("acceptor-id");
        acceptor.setEmail("acceptor@example.com");

        when(inviteRepository.findByToken("valid-token")).thenReturn(Optional.of(invite));
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(i -> i.getArgument(0));
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));

        AcceptInviteResponse response = inviteService.acceptInvite("valid-token", acceptor);

        assertThat(response.getProjectId()).isEqualTo("proj-1");
        assertThat(response.getProjectName()).isEqualTo("Test Project");
        assertThat(response.getRole()).isEqualTo("CREATOR");

        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getUser()).isEqualTo(acceptor);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(MemberRole.CREATOR);

        assertThat(invite.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptInviteExpiredTokenReturnsWith410() {
        Invite invite = buildPendingInvite("expired-token", MemberRole.REVIEWER, OffsetDateTime.now().minusHours(1));
        User acceptor = new User();
        acceptor.setId("acceptor-id");

        when(inviteRepository.findByToken("expired-token")).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> inviteService.acceptInvite("expired-token", acceptor))
                .isInstanceOf(InviteExpiredException.class)
                .hasMessage("Invite has expired");
    }

    @Test
    void acceptInviteAlreadyAcceptedReturnsWith409() {
        Invite invite = buildPendingInvite("used-token", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        invite.setStatus("ACCEPTED");
        User acceptor = new User();
        acceptor.setId("acceptor-id");

        when(inviteRepository.findByToken("used-token")).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> inviteService.acceptInvite("used-token", acceptor))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Invite already used");
    }

    @Test
    void acceptInviteCancelledReturnsWith409() {
        Invite invite = buildPendingInvite("cancelled-token", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        invite.setStatus("CANCELLED");
        User acceptor = new User();
        acceptor.setId("acceptor-id");

        when(inviteRepository.findByToken("cancelled-token")).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> inviteService.acceptInvite("cancelled-token", acceptor))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Invite already used");
    }

    @Test
    void acceptInviteNotFoundReturnsWith404() {
        when(inviteRepository.findByToken("bad-token")).thenReturn(Optional.empty());
        User acceptor = new User();
        acceptor.setId("acceptor-id");

        assertThatThrownBy(() -> inviteService.acceptInvite("bad-token", acceptor))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Invite not found");
    }

    // ─── listPendingInvites tests ─────────────────────────────────────────────

    @Test
    void listInvitesReturnsOnlyPendingInvites() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);

        Invite pending = buildPendingInvite("token-1", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        pending.setId("invite-pending");
        when(inviteRepository.findByProjectIdAndStatus("proj-1", "PENDING")).thenReturn(List.of(pending));

        List<InviteResponse> result = inviteService.listPendingInvites("proj-1", admin);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void listInvitesNonAdminReturnsWith403() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(false);

        assertThatThrownBy(() -> inviteService.listPendingInvites("proj-1", admin))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ─── cancelInvite tests ───────────────────────────────────────────────────

    @Test
    void cancelInviteSetsStatusToCancelled() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);
        Invite invite = buildPendingInvite("token-1", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        invite.setId("invite-1");
        when(inviteRepository.findById("invite-1")).thenReturn(Optional.of(invite));
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));

        inviteService.cancelInvite("proj-1", "invite-1", admin);

        assertThat(invite.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelledTokenIsSubsequentlyRejectedOnAccept() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-id")).thenReturn(true);
        Invite invite = buildPendingInvite("token-to-cancel", MemberRole.CREATOR, OffsetDateTime.now().plusHours(24));
        invite.setId("invite-cancel");
        when(inviteRepository.findById("invite-cancel")).thenReturn(Optional.of(invite));
        when(inviteRepository.save(any(Invite.class))).thenAnswer(i -> i.getArgument(0));

        inviteService.cancelInvite("proj-1", "invite-cancel", admin);
        assertThat(invite.getStatus()).isEqualTo("CANCELLED");

        when(inviteRepository.findByToken("token-to-cancel")).thenReturn(Optional.of(invite));
        User acceptor = new User();
        acceptor.setId("acceptor-id");

        assertThatThrownBy(() -> inviteService.acceptInvite("token-to-cancel", acceptor))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Invite already used");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Invite buildPendingInvite(String token, MemberRole role, OffsetDateTime expiresAt) {
        Invite invite = new Invite();
        invite.setProject(project);
        invite.setEmail("invitee@example.com");
        invite.setRole(role);
        invite.setToken(token);
        invite.setInvitedBy(admin);
        invite.setStatus("PENDING");
        invite.setExpiresAt(expiresAt);
        invite.setCreatedAt(OffsetDateTime.now());
        return invite;
    }
}
