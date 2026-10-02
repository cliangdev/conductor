package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.exception.EmailNotVerifiedException;
import com.conductor.generated.model.AuthResponse;
import com.conductor.repository.UserRepository;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private FirebaseTokenVerifier verifier;
    private JwtService jwtService;
    private UserRepository userRepository;
    private TransactionTemplate transactionTemplate;
    private ProjectService projectService;
    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifier = mock(FirebaseTokenVerifier.class);
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        projectService = mock(ProjectService.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any())).thenReturn("app-jwt");
        authService = new AuthService(verifier, jwtService, userRepository, transactionTemplate, projectService);
    }

    private FirebaseToken token(boolean verified, String name, String picture) throws Exception {
        FirebaseToken t = mock(FirebaseToken.class);
        when(t.getUid()).thenReturn("uid-1");
        when(t.getEmail()).thenReturn("a@example.com");
        when(t.isEmailVerified()).thenReturn(verified);
        when(t.getName()).thenReturn(name);
        when(t.getClaims()).thenReturn(picture == null ? Map.of() : Map.of("picture", picture));
        when(verifier.verifyToken("tok")).thenReturn(t);
        return t;
    }

    @Test
    void unverifiedTokenIsRefusedBeforeAnyUserOrWorkspaceIsCreated() throws Exception {
        token(false, "Alice", null);

        assertThrows(EmailNotVerifiedException.class, () -> authService.authenticateWithFirebase("tok"));

        verify(userRepository, never()).save(any());
        verifyNoInteractions(transactionTemplate, projectService, jwtService);
    }

    @Test
    void verifiedTokenCreatesUser() throws Exception {
        token(true, "Alice", "https://example.com/a.png");
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());

        AuthResponse response = authService.authenticateWithFirebase("tok");

        verify(userRepository).save(any(User.class));
        assertEquals("app-jwt", response.getAccessToken());
        assertEquals("a@example.com", response.getUser().getEmail());
        assertEquals("Alice", response.getUser().getName());
        assertEquals("https://example.com/a.png", response.getUser().getAvatarUrl());
    }

    @Test
    void newUserWithoutNameKeepsNullName() throws Exception {
        token(true, null, null);
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.empty());

        AuthResponse response = authService.authenticateWithFirebase("tok");

        assertEquals(null, response.getUser().getName());
    }

    @Test
    void returningUserWhoseTokenHasNoNameKeepsStoredProfile() throws Exception {
        User existing = new User();
        existing.setId("user-1");
        existing.setFirebaseUid("uid-1");
        existing.setEmail("a@example.com");
        existing.setName("Stored Name");
        existing.setAvatarUrl("https://example.com/stored.png");
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(existing));
        token(true, "  ", null);

        AuthResponse response = authService.authenticateWithFirebase("tok");

        assertEquals("Stored Name", response.getUser().getName());
        assertEquals("https://example.com/stored.png", response.getUser().getAvatarUrl());
    }

    @Test
    void returningGoogleUserGetsChangedNameAndPicture() throws Exception {
        User existing = new User();
        existing.setId("user-1");
        existing.setFirebaseUid("uid-1");
        existing.setName("Old");
        existing.setAvatarUrl("https://example.com/old.png");
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(existing));
        token(true, "New", "https://example.com/new.png");

        AuthResponse response = authService.authenticateWithFirebase("tok");

        assertEquals("New", response.getUser().getName());
        assertEquals("https://example.com/new.png", response.getUser().getAvatarUrl());
    }
}
