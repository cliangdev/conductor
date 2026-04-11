package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AuthService;
import com.conductor.service.CliLoginService;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CliCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CliLoginService cliLoginService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("admin@example.com");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    @Test
    void cliCallbackReturns200WithCredentials() throws Exception {
        CliCallbackResponse response = new CliCallbackResponse(
                "API key generated", "ck_abc123", "proj-1", "My Project", "admin@example.com");
        when(cliLoginService.generateCredentials(eq(8080), eq("proj-1"), eq(testUser)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .header("Authorization", "Bearer valid-token")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API key generated"))
                .andExpect(jsonPath("$.apiKey").value("ck_abc123"))
                .andExpect(jsonPath("$.projectId").value("proj-1"))
                .andExpect(jsonPath("$.projectName").value("My Project"))
                .andExpect(jsonPath("$.email").value("admin@example.com"));
    }

    @Test
    void cliCallbackReturns403ForNonAdmin() throws Exception {
        when(cliLoginService.generateCredentials(eq(8080), eq("proj-1"), eq(testUser)))
                .thenThrow(new ForbiddenException("Caller is not a project admin"));

        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .header("Authorization", "Bearer valid-token")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cliCallbackRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isUnauthorized());
    }
}
