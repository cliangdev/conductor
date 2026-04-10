package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.exception.InviteExpiredException;
import com.conductor.generated.model.AcceptInviteResponse;
import com.conductor.generated.model.InviteResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.InviteService;
import com.conductor.service.JwtService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InviteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InviteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InviteService inviteService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockBean
    private UserApiKeyRepository userApiKeyRepository;

    private User adminUser;
    private User creatorUser;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-user-id");
        adminUser.setEmail("admin@example.com");
        adminUser.setName("Admin User");

        creatorUser = new User();
        creatorUser.setId("creator-user-id");
        creatorUser.setEmail("creator@example.com");
        creatorUser.setName("Creator User");

        when(jwtService.validateToken("admin-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("admin-token")).thenReturn("admin-user-id");
        when(userRepository.findById("admin-user-id")).thenReturn(Optional.of(adminUser));

        when(jwtService.validateToken("creator-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("creator-token")).thenReturn("creator-user-id");
        when(userRepository.findById("creator-user-id")).thenReturn(Optional.of(creatorUser));
    }

    // ─── POST /projects/{projectId}/invites ───────────────────────────────────

    @Test
    void createInviteReturns201WithInviteObject() throws Exception {
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(72);
        InviteResponse response = new InviteResponse("invite-1", "invitee@example.com", "CREATOR", expiresAt)
                .status("PENDING");

        when(inviteService.createInvite(eq("proj-1"), eq("invitee@example.com"), eq("CREATOR"), eq(adminUser)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"invitee@example.com\", \"role\": \"CREATOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("invite-1"))
                .andExpect(jsonPath("$.email").value("invitee@example.com"))
                .andExpect(jsonPath("$.role").value("CREATOR"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void createInviteDuplicateEmailReturns409() throws Exception {
        when(inviteService.createInvite(any(), any(), any(), any()))
                .thenThrow(new ConflictException("Invite already pending for this email"));

        mockMvc.perform(post("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"invitee@example.com\", \"role\": \"CREATOR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Invite already pending for this email"));
    }

    @Test
    void createInviteByNonAdminReturns403() throws Exception {
        when(inviteService.createInvite(any(), any(), any(), eq(creatorUser)))
                .thenThrow(new AccessDeniedException("Only project admins can send invites"));

        mockMvc.perform(post("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer creator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"invitee@example.com\", \"role\": \"CREATOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createInviteWithAdminRoleReturns400() throws Exception {
        when(inviteService.createInvite(any(), any(), eq("ADMIN"), any()))
                .thenThrow(new BusinessException("Cannot invite with ADMIN role"));

        mockMvc.perform(post("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"invitee@example.com\", \"role\": \"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot invite with ADMIN role"));
    }

    @Test
    void createInviteRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/projects/proj-1/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"invitee@example.com\", \"role\": \"CREATOR\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /projects/{projectId}/invites ────────────────────────────────────

    @Test
    void listInvitesReturnsOnlyPendingInvites() throws Exception {
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(72);
        List<InviteResponse> invites = List.of(
                new InviteResponse("invite-1", "user1@example.com", "CREATOR", expiresAt).status("PENDING"),
                new InviteResponse("invite-2", "user2@example.com", "REVIEWER", expiresAt).status("PENDING")
        );

        when(inviteService.listPendingInvites("proj-1", adminUser)).thenReturn(invites);

        mockMvc.perform(get("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
    }

    @Test
    void listInvitesByNonAdminReturns403() throws Exception {
        when(inviteService.listPendingInvites("proj-1", creatorUser))
                .thenThrow(new AccessDeniedException("Only project admins can list invites"));

        mockMvc.perform(get("/api/v1/projects/proj-1/invites")
                        .header("Authorization", "Bearer creator-token"))
                .andExpect(status().isForbidden());
    }

    // ─── DELETE /projects/{projectId}/invites/{inviteId} ──────────────────────

    @Test
    void cancelInviteReturns204() throws Exception {
        doNothing().when(inviteService).cancelInvite("proj-1", "invite-1", adminUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/invites/invite-1")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelInviteByNonAdminReturns403() throws Exception {
        doThrow(new AccessDeniedException("Only project admins can cancel invites"))
                .when(inviteService).cancelInvite("proj-1", "invite-1", creatorUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/invites/invite-1")
                        .header("Authorization", "Bearer creator-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelInviteNotFoundReturns404() throws Exception {
        doThrow(new EntityNotFoundException("Invite not found"))
                .when(inviteService).cancelInvite("proj-1", "missing-invite", adminUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/invites/missing-invite")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    // ─── POST /invites/{token}/accept ─────────────────────────────────────────

    @Test
    void acceptInviteValidTokenReturns200WithProjectInfo() throws Exception {
        AcceptInviteResponse response = new AcceptInviteResponse("proj-1", "Test Project", "CREATOR");

        when(inviteService.acceptInvite("valid-token", adminUser)).thenReturn(response);

        mockMvc.perform(post("/api/v1/invites/valid-token/accept")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("proj-1"))
                .andExpect(jsonPath("$.projectName").value("Test Project"))
                .andExpect(jsonPath("$.role").value("CREATOR"));
    }

    @Test
    void acceptInviteExpiredTokenReturns410() throws Exception {
        when(inviteService.acceptInvite("expired-token", adminUser))
                .thenThrow(new InviteExpiredException("Invite has expired"));

        mockMvc.perform(post("/api/v1/invites/expired-token/accept")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("Invite has expired"));
    }

    @Test
    void acceptInviteAlreadyUsedReturns409() throws Exception {
        when(inviteService.acceptInvite("used-token", adminUser))
                .thenThrow(new ConflictException("Invite already used"));

        mockMvc.perform(post("/api/v1/invites/used-token/accept")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Invite already used"));
    }

    @Test
    void acceptInviteRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/invites/some-token/accept"))
                .andExpect(status().isUnauthorized());
    }
}
