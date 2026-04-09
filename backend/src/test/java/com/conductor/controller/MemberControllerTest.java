package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.MemberResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

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

    @Test
    void listMembersReturnsAllMembers() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        List<MemberResponse> members = List.of(
                new MemberResponse("admin-user-id", "ADMIN", now).name("Admin User").email("admin@example.com"),
                new MemberResponse("creator-user-id", "CREATOR", now).name("Creator User").email("creator@example.com")
        );

        when(projectService.listMembers("proj-1", adminUser)).thenReturn(members);

        mockMvc.perform(get("/api/v1/projects/proj-1/members")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value("admin-user-id"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].role").value("CREATOR"));
    }

    @Test
    void listMembersReturns404ForNonMember() throws Exception {
        when(projectService.listMembers("proj-1", adminUser))
                .thenThrow(new EntityNotFoundException("Project not found"));

        mockMvc.perform(get("/api/v1/projects/proj-1/members")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateMemberRoleByAdminSucceeds() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MemberResponse updated = new MemberResponse("creator-user-id", "REVIEWER", now)
                .name("Creator User").email("creator@example.com");

        when(projectService.updateMemberRole(eq("proj-1"), eq("creator-user-id"), any(), eq(adminUser)))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/v1/projects/proj-1/members/creator-user-id")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"REVIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test
    void updateMemberRoleByNonAdminReturns403() throws Exception {
        when(projectService.updateMemberRole(eq("proj-1"), eq("admin-user-id"), any(), eq(creatorUser)))
                .thenThrow(new AccessDeniedException("Only project admins can update member roles"));

        mockMvc.perform(patch("/api/v1/projects/proj-1/members/admin-user-id")
                        .header("Authorization", "Bearer creator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateLastAdminRoleReturns400() throws Exception {
        when(projectService.updateMemberRole(eq("proj-1"), eq("admin-user-id"), any(), eq(adminUser)))
                .thenThrow(new BusinessException("Cannot remove the last project admin"));

        mockMvc.perform(patch("/api/v1/projects/proj-1/members/admin-user-id")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"CREATOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot remove the last project admin"));
    }

    @Test
    void removeMemberByAdminSucceeds() throws Exception {
        doNothing().when(projectService).removeMember("proj-1", "creator-user-id", adminUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/members/creator-user-id")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeMemberByNonAdminReturns403() throws Exception {
        doThrow(new AccessDeniedException("Only project admins can remove members"))
                .when(projectService).removeMember("proj-1", "admin-user-id", creatorUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/members/admin-user-id")
                        .header("Authorization", "Bearer creator-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeLastAdminReturns400() throws Exception {
        doThrow(new BusinessException("Cannot remove the last project admin"))
                .when(projectService).removeMember("proj-1", "admin-user-id", adminUser);

        mockMvc.perform(delete("/api/v1/projects/proj-1/members/admin-user-id")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot remove the last project admin"));
    }

    @Test
    void creatorRoleUserCannotCallAdminOnlyPatchEndpoint() throws Exception {
        when(projectService.updateMemberRole(eq("proj-1"), any(), any(), eq(creatorUser)))
                .thenThrow(new AccessDeniedException("Only project admins can update member roles"));

        mockMvc.perform(patch("/api/v1/projects/proj-1/members/some-user-id")
                        .header("Authorization", "Bearer creator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"REVIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatorRoleUserCannotCallAdminOnlyDeleteEndpoint() throws Exception {
        doThrow(new AccessDeniedException("Only project admins can remove members"))
                .when(projectService).removeMember(eq("proj-1"), any(), eq(creatorUser));

        mockMvc.perform(delete("/api/v1/projects/proj-1/members/some-user-id")
                        .header("Authorization", "Bearer creator-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedMemberSubsequentRequestReturns403() throws Exception {
        // After removal, the user won't have a valid membership; the service throws AccessDeniedException
        // when checking membership for list members endpoint
        when(projectService.listMembers("proj-1", creatorUser))
                .thenThrow(new EntityNotFoundException("Project not found"));

        mockMvc.perform(get("/api/v1/projects/proj-1/members")
                        .header("Authorization", "Bearer creator-token"))
                .andExpect(status().isNotFound());
    }
}
