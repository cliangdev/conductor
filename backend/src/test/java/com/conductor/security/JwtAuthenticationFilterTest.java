package com.conductor.security;

import com.conductor.config.SecurityConfig;
import com.conductor.controller.AuthController;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AuthService;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-uuid-123");
        testUser.setEmail("test@example.com");
        testUser.setName("Test User");
        testUser.setAvatarUrl("https://example.com/avatar.png");
        testUser.setDisplayName("Test");
    }

    @Test
    void requestWithValidJwtPassesFilterAndGetsAuthenticated() throws Exception {
        when(jwtService.validateToken("valid-jwt")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-jwt")).thenReturn("user-uuid-123");
        when(userRepository.findById("user-uuid-123")).thenReturn(Optional.of(testUser));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer valid-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-uuid-123"))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    void requestWithoutJwtToProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAuthMeWithValidJwtReturnsCurrentUser() throws Exception {
        when(jwtService.validateToken("valid-jwt")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-jwt")).thenReturn("user-uuid-123");
        when(userRepository.findById("user-uuid-123")).thenReturn(Optional.of(testUser));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer valid-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-uuid-123"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.name").value("Test User"));
    }
}
