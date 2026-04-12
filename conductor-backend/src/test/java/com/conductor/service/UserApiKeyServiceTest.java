package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.entity.UserApiKey;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateUserApiKeyRequest;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.generated.model.UserApiKeyResponse;
import com.conductor.repository.UserApiKeyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserApiKeyServiceTest {

    @Mock
    private UserApiKeyRepository userApiKeyRepository;

    private UserApiKeyService userApiKeyService;

    private User testUser;

    @BeforeEach
    void setUp() {
        userApiKeyService = new UserApiKeyService(userApiKeyRepository);

        testUser = new User();
        testUser.setId("user-1");
        testUser.setEmail("user@example.com");
    }

    @Test
    void listUserApiKeysReturnsMaskedKeys() {
        UserApiKey key = new UserApiKey();
        key.setId("key-1");
        key.setUser(testUser);
        key.setLabel("CLI Key");
        key.setKeyHash("hash");
        key.setKeySuffix("abcd");
        key.setCreatedAt(OffsetDateTime.now());

        when(userApiKeyRepository.findByUserIdAndRevokedAtIsNull("user-1")).thenReturn(List.of(key));

        List<UserApiKeyResponse> result = userApiKeyService.listUserApiKeys(testUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMaskedKey()).isEqualTo("****abcd");
        assertThat(result.get(0).getLabel()).isEqualTo("CLI Key");
    }

    @Test
    void createUserApiKeyReturnsFullKeyOnce() {
        when(userApiKeyRepository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey k = invocation.getArgument(0);
            if (k.getCreatedAt() == null) k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        CreateUserApiKeyRequest request = new CreateUserApiKeyRequest().label("My Key");
        CreateUserApiKeyResponse response = userApiKeyService.createUserApiKey(request, testUser);

        assertThat(response.getKey()).startsWith("uk_");
        assertThat(response.getMaskedKey()).startsWith("****");
        assertThat(response.getMaskedKey()).hasSize(8);
        assertThat(response.getLabel()).isEqualTo("My Key");
    }

    @Test
    void createUserApiKeyUsesDefaultLabelWhenNullRequest() {
        when(userApiKeyRepository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey k = invocation.getArgument(0);
            if (k.getCreatedAt() == null) k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        CreateUserApiKeyResponse response = userApiKeyService.createUserApiKey(null, testUser);

        assertThat(response.getLabel()).isEqualTo("CLI Key");
    }

    @Test
    void createUserApiKeyStoresHash() {
        when(userApiKeyRepository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey k = invocation.getArgument(0);
            if (k.getCreatedAt() == null) k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        CreateUserApiKeyResponse response = userApiKeyService.createUserApiKey(null, testUser);

        ArgumentCaptor<UserApiKey> captor = ArgumentCaptor.forClass(UserApiKey.class);
        verify(userApiKeyRepository).save(captor.capture());
        UserApiKey saved = captor.getValue();

        String expectedHash = userApiKeyService.sha256(response.getKey());
        assertThat(saved.getKeyHash()).isEqualTo(expectedHash);
    }

    @Test
    void deleteUserApiKeySetsRevokedAt() {
        UserApiKey key = new UserApiKey();
        key.setId("key-1");
        key.setUser(testUser);
        key.setLabel("CLI Key");
        key.setKeyHash("hash");
        key.setKeySuffix("abcd");
        key.setCreatedAt(OffsetDateTime.now());

        when(userApiKeyRepository.findById("key-1")).thenReturn(Optional.of(key));
        when(userApiKeyRepository.save(any())).thenReturn(key);

        userApiKeyService.deleteUserApiKey("key-1", testUser);

        assertThat(key.getRevokedAt()).isNotNull();
        verify(userApiKeyRepository).save(key);
    }

    @Test
    void deleteUserApiKeyThrows403WhenNotOwner() {
        User otherUser = new User();
        otherUser.setId("other-user");

        UserApiKey key = new UserApiKey();
        key.setId("key-1");
        key.setUser(otherUser);
        key.setCreatedAt(OffsetDateTime.now());

        when(userApiKeyRepository.findById("key-1")).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> userApiKeyService.deleteUserApiKey("key-1", testUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("You do not own this API key");
    }

    @Test
    void deleteUserApiKeyThrows404WhenNotFound() {
        when(userApiKeyRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userApiKeyService.deleteUserApiKey("nonexistent", testUser))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("API key not found");
    }

    @Test
    void createUserApiKeyStoresRawKeyValue() {
        when(userApiKeyRepository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey k = invocation.getArgument(0);
            if (k.getCreatedAt() == null) k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        CreateUserApiKeyRequest request = new CreateUserApiKeyRequest().label("CLI key");
        CreateUserApiKeyResponse response = userApiKeyService.createUserApiKey(request, testUser);

        ArgumentCaptor<UserApiKey> captor = ArgumentCaptor.forClass(UserApiKey.class);
        verify(userApiKeyRepository).save(captor.capture());
        UserApiKey saved = captor.getValue();

        assertThat(saved.getKeyValue()).isEqualTo(response.getKey());
    }

    @Test
    void listUserApiKeysReturnsKeyValueWhenPresent() {
        UserApiKey key = new UserApiKey();
        key.setId("key-1");
        key.setUser(testUser);
        key.setLabel("CLI key");
        key.setKeyHash("hash");
        key.setKeySuffix("abcd");
        key.setKeyValue("uk_abc123abcd");
        key.setCreatedAt(OffsetDateTime.now());

        when(userApiKeyRepository.findByUserIdAndRevokedAtIsNull("user-1")).thenReturn(List.of(key));

        List<UserApiKeyResponse> result = userApiKeyService.listUserApiKeys(testUser);

        assertThat(result.get(0).getKey()).isEqualTo("uk_abc123abcd");
    }

    @Test
    void listUserApiKeysReturnsNullKeyWhenKeyValueNotSet() {
        UserApiKey key = new UserApiKey();
        key.setId("key-1");
        key.setUser(testUser);
        key.setLabel("CLI key");
        key.setKeyHash("hash");
        key.setKeySuffix("abcd");
        key.setCreatedAt(OffsetDateTime.now());

        when(userApiKeyRepository.findByUserIdAndRevokedAtIsNull("user-1")).thenReturn(List.of(key));

        List<UserApiKeyResponse> result = userApiKeyService.listUserApiKeys(testUser);

        assertThat(result.get(0).getKey()).isNull();
    }
}
