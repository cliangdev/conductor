package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AuthService;
import com.conductor.service.CliLoginService;
import com.conductor.service.FirebaseTokenVerifier;
import com.conductor.service.JwtService;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private CliLoginService cliLoginService;

    @MockBean
    private FirebaseTokenVerifier firebaseTokenVerifier;

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
        testUser.setId("user-uuid-123");
        testUser.setEmail("test@example.com");
        testUser.setName("Test User");
        testUser.setAvatarUrl("https://example.com/avatar.png");
    }

    @Test
    void validFirebaseTokenReturns200WithAccessTokenAndUser() throws Exception {
        FirebaseToken mockFirebaseToken = mock(FirebaseToken.class);
        when(mockFirebaseToken.getUid()).thenReturn("firebase-uid-abc");
        when(mockFirebaseToken.getEmail()).thenReturn("test@example.com");
        when(mockFirebaseToken.getName()).thenReturn("Test User");
        when(mockFirebaseToken.getClaims()).thenReturn(Map.of("picture", "https://example.com/avatar.png"));

        when(firebaseTokenVerifier.verifyToken("valid-firebase-token")).thenReturn(mockFirebaseToken);
        when(userRepository.findByFirebaseUid("firebase-uid-abc")).thenReturn(Optional.of(testUser));
        when(jwtService.generateToken("user-uuid-123")).thenReturn("app-jwt-token");

        com.conductor.generated.model.UserSummary userSummary =
                new com.conductor.generated.model.UserSummary("user-uuid-123", "test@example.com")
                        .name("Test User")
                        .avatarUrl("https://example.com/avatar.png");
        com.conductor.generated.model.AuthResponse authResponse =
                new com.conductor.generated.model.AuthResponse("app-jwt-token", userSummary);

        when(authService.authenticateWithFirebase("valid-firebase-token")).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/firebase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\": \"valid-firebase-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("app-jwt-token"))
                .andExpect(jsonPath("$.user.id").value("user-uuid-123"))
                .andExpect(jsonPath("$.user.email").value("test@example.com"));
    }

    @Test
    void invalidFirebaseTokenReturns401WithProblemDetail() throws Exception {
        when(authService.authenticateWithFirebase("invalid-token"))
                .thenThrow(new com.conductor.exception.FirebaseAuthenticationException(
                        "Token expired", new RuntimeException()));

        mockMvc.perform(post("/api/v1/auth/firebase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\": \"invalid-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Invalid Firebase token"));
    }
}
