package com.conductor.service;

import com.conductor.entity.IntegrationCredential;
import com.conductor.integration.AuthType;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalCredentialServiceTest {

    @Mock
    private IntegrationCredentialRepository credentialRepository;

    @Mock
    private IntegrationDataCacheRepository cacheRepository;

    private LocalCredentialService service;

    private static final String PROJECT_ID = "project-1";
    private static final String CONNECTOR_ID = "github";
    private static final String ACCESS_TOKEN = "super-secret-access-token";
    private static final String REFRESH_TOKEN = "super-secret-refresh-token";

    @BeforeEach
    void setUp() {
        service = new LocalCredentialService(
                credentialRepository,
                cacheRepository,
                new ObjectMapper(),
                "test-encryption-key-for-unit-tests");
    }

    @Test
    void storeThenGet_roundTripsTokens() {
        when(credentialRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.empty());

        ArgumentCaptor<IntegrationCredential> captor = ArgumentCaptor.forClass(IntegrationCredential.class);

        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);
        service.storeCredentials(PROJECT_ID, CONNECTOR_ID, AuthType.OAUTH2,
                ACCESS_TOKEN, REFRESH_TOKEN, expiresAt, Map.of("foo", "bar"));

        verify(credentialRepository).save(captor.capture());
        IntegrationCredential saved = captor.getValue();

        // On retrieval the stored entity decrypts back to the originals
        when(credentialRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.of(saved));

        Optional<DecryptedCredentials> result = service.getCredentials(PROJECT_ID, CONNECTOR_ID);

        assertThat(result).isPresent();
        assertThat(result.get().accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.get().refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(result.get().expiresAt()).isEqualTo(expiresAt.toInstant());
        assertThat(result.get().configJson()).containsEntry("foo", "bar");
    }

    @Test
    void storedValue_doesNotContainPlaintextToken() {
        when(credentialRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(Optional.empty());

        ArgumentCaptor<IntegrationCredential> captor = ArgumentCaptor.forClass(IntegrationCredential.class);

        service.storeCredentials(PROJECT_ID, CONNECTOR_ID, AuthType.OAUTH2,
                ACCESS_TOKEN, REFRESH_TOKEN, OffsetDateTime.now().plusHours(1), null);

        verify(credentialRepository).save(captor.capture());
        IntegrationCredential saved = captor.getValue();

        assertThat(saved.getEncryptedAccessToken()).isNotNull();
        assertThat(saved.getEncryptedAccessToken()).doesNotContain(ACCESS_TOKEN);
        assertThat(saved.getEncryptedRefreshToken()).isNotNull();
        assertThat(saved.getEncryptedRefreshToken()).doesNotContain(REFRESH_TOKEN);
    }

    @Test
    void deleteCredentials_removesCredentialAndCacheRows() {
        service.deleteCredentials(PROJECT_ID, CONNECTOR_ID);

        verify(credentialRepository).deleteByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID);
        verify(cacheRepository).deleteByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID);
    }
}
