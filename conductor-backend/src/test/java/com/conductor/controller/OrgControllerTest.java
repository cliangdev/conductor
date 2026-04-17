package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.OrgResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.OrgService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrgController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class OrgControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrgService orgService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("user@example.com");
        testUser.setName("Test User");

        testOrg = new Organization();
        testOrg.setId("org-1");
        testOrg.setName("My Org");
        testOrg.setSlug("my-org");
        testOrg.setCreatedAt(OffsetDateTime.now());
        testOrg.setUpdatedAt(OffsetDateTime.now());

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    @Test
    void createOrgReturns201WithOrgResponse() throws Exception {
        when(orgService.createOrg(eq("user-id-123"), eq("My Org"), eq("my-org")))
                .thenReturn(testOrg);

        mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Org\", \"slug\": \"my-org\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("org-1"))
                .andExpect(jsonPath("$.name").value("My Org"))
                .andExpect(jsonPath("$.slug").value("my-org"));
    }

    @Test
    void createOrgReturns409OnSlugConflict() throws Exception {
        when(orgService.createOrg(any(), any(), any()))
                .thenThrow(new ConflictException("Slug already taken: my-org"));

        mockMvc.perform(post("/api/v1/orgs")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Org\", \"slug\": \"my-org\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createOrgRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Org\", \"slug\": \"my-org\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrgReturns200ForMember() throws Exception {
        when(orgService.getOrg("org-1", "user-id-123")).thenReturn(testOrg);

        mockMvc.perform(get("/api/v1/orgs/org-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("org-1"))
                .andExpect(jsonPath("$.slug").value("my-org"));
    }

    @Test
    void getOrgReturns403ForNonMember() throws Exception {
        when(orgService.getOrg("org-1", "user-id-123"))
                .thenThrow(new ForbiddenException("You are not a member of this org"));

        mockMvc.perform(get("/api/v1/orgs/org-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOrgReturns404WhenNotFound() throws Exception {
        when(orgService.getOrg("nonexistent", "user-id-123"))
                .thenThrow(new EntityNotFoundException("Org not found"));

        mockMvc.perform(get("/api/v1/orgs/nonexistent")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listMyOrgsReturnsAllUserOrgs() throws Exception {
        Organization org2 = new Organization();
        org2.setId("org-2");
        org2.setName("Second Org");
        org2.setSlug("second-org");
        org2.setCreatedAt(OffsetDateTime.now());
        org2.setUpdatedAt(OffsetDateTime.now());

        when(orgService.getOrgsForUser("user-id-123")).thenReturn(List.of(testOrg, org2));

        mockMvc.perform(get("/api/v1/users/me/orgs")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("org-1"))
                .andExpect(jsonPath("$[1].id").value("org-2"));
    }
}
