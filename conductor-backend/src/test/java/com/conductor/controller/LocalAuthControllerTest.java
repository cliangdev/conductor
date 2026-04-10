package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.model.AuthResponse;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAuthControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private LocalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new LocalAuthController(userRepository, jwtService, "conductor");
    }

    @Test
    void correctPasswordReturns200WithAccessTokenAndUser() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("dev@example.com");
        user.setName("dev@example.com");

        when(userRepository.findByFirebaseUid("local:dev@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken("user-1")).thenReturn("jwt-token");

        ResponseEntity<AuthResponse> response = controller.localAuth(
                Map.of("email", "dev@example.com", "password", "conductor"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isEqualTo("jwt-token");
        assertThat(response.getBody().getUser().getId()).isEqualTo("user-1");
        assertThat(response.getBody().getUser().getEmail()).isEqualTo("dev@example.com");
    }

    @Test
    void wrongPasswordReturns401() {
        ResponseEntity<AuthResponse> response = controller.localAuth(
                Map.of("email", "dev@example.com", "password", "wrong"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(userRepository, never()).findByFirebaseUid(any());
    }

    @Test
    void secondCallWithSameEmailReturnsTokenForExistingUser() {
        User existing = new User();
        existing.setId("user-existing");
        existing.setEmail("dev@example.com");
        existing.setName("dev@example.com");

        when(userRepository.findByFirebaseUid("local:dev@example.com")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken("user-existing")).thenReturn("token-1", "token-2");

        controller.localAuth(Map.of("email", "dev@example.com", "password", "conductor"));
        controller.localAuth(Map.of("email", "dev@example.com", "password", "conductor"));

        verify(userRepository, never()).save(any());
        verify(jwtService, times(2)).generateToken("user-existing");
    }
}
