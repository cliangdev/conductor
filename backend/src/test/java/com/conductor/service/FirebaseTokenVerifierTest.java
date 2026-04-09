package com.conductor.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirebaseTokenVerifierTest {

    @Mock
    private FirebaseAuth firebaseAuth;

    @InjectMocks
    private FirebaseTokenVerifier firebaseTokenVerifier;

    @Test
    void validTokenIsVerifiedSuccessfully() throws FirebaseAuthException {
        FirebaseToken mockToken = mock(FirebaseToken.class);
        when(firebaseAuth.verifyIdToken("valid-token")).thenReturn(mockToken);

        FirebaseToken result = firebaseTokenVerifier.verifyToken("valid-token");

        assertThat(result).isSameAs(mockToken);
        verify(firebaseAuth).verifyIdToken("valid-token");
    }

    @Test
    void invalidTokenThrowsFirebaseAuthException() throws FirebaseAuthException {
        when(firebaseAuth.verifyIdToken("invalid-token"))
                .thenThrow(mock(FirebaseAuthException.class));

        assertThatThrownBy(() -> firebaseTokenVerifier.verifyToken("invalid-token"))
                .isInstanceOf(FirebaseAuthException.class);
    }
}
