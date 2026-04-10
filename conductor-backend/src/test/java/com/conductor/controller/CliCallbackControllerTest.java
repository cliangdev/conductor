package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.CliNotReachableException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AuthService;
import com.conductor.service.CliLoginService;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @MockBean
    private AuthService authService;

    @MockBean
    private CliLoginService cliLoginService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockBean
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
    void cliCallbackReturns200WithSuccessMessage() throws Exception {
        when(cliLoginService.sendApiKeyToCli(eq(8080), eq("proj-1"), eq(testUser)))
                .thenReturn("API key sent to CLI");

        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .header("Authorization", "Bearer valid-token")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API key sent to CLI"));
    }

    @Test
    void cliCallbackReturns403ForNonAdmin() throws Exception {
        when(cliLoginService.sendApiKeyToCli(eq(8080), eq("proj-1"), eq(testUser)))
                .thenThrow(new ForbiddenException("Caller is not a project admin"));

        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .header("Authorization", "Bearer valid-token")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cliCallbackReturns502WhenCliNotReachable() throws Exception {
        when(cliLoginService.sendApiKeyToCli(eq(9999), eq("proj-1"), eq(testUser)))
                .thenThrow(new CliNotReachableException("CLI callback server not reachable"));

        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .header("Authorization", "Bearer valid-token")
                        .param("port", "9999")
                        .param("projectId", "proj-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("CLI callback server not reachable"));
    }

    @Test
    void cliCallbackRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/cli-callback")
                        .param("port", "8080")
                        .param("projectId", "proj-1"))
                .andExpect(status().isUnauthorized());
    }
}
