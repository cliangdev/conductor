package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.entity.UserApiKey;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateUserApiKeyRequest;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.generated.model.UserApiKeyResponse;
import com.conductor.repository.UserApiKeyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class UserApiKeyService {

    private final UserApiKeyRepository userApiKeyRepository;

    public UserApiKeyService(UserApiKeyRepository userApiKeyRepository) {
        this.userApiKeyRepository = userApiKeyRepository;
    }

    @Transactional(readOnly = true)
    public List<UserApiKeyResponse> listUserApiKeys(User caller) {
        return userApiKeyRepository.findByUserIdAndRevokedAtIsNull(caller.getId())
                .stream()
                .map(this::toUserApiKeyResponse)
                .toList();
    }

    @Transactional
    public CreateUserApiKeyResponse createUserApiKey(CreateUserApiKeyRequest request, User caller) {
        String label = (request != null && request.getLabel() != null) ? request.getLabel() : "CLI Key";
        String rawKey = "uk_" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = sha256(rawKey);
        String keySuffix = rawKey.substring(rawKey.length() - 4);

        UserApiKey apiKey = new UserApiKey();
        apiKey.setUser(caller);
        apiKey.setLabel(label);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeySuffix(keySuffix);

        userApiKeyRepository.save(apiKey);

        return new CreateUserApiKeyResponse(apiKey.getId(), rawKey, "****" + keySuffix, apiKey.getCreatedAt())
                .label(label);
    }

    @Transactional
    public void deleteUserApiKey(String keyId, User caller) {
        UserApiKey apiKey = userApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new EntityNotFoundException("API key not found"));

        if (!apiKey.getUser().getId().equals(caller.getId())) {
            throw new ForbiddenException("You do not own this API key");
        }

        apiKey.setRevokedAt(OffsetDateTime.now());
        userApiKeyRepository.save(apiKey);
    }

    String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private UserApiKeyResponse toUserApiKeyResponse(UserApiKey apiKey) {
        return new UserApiKeyResponse(apiKey.getId(), "****" + apiKey.getKeySuffix(), apiKey.getCreatedAt())
                .label(apiKey.getLabel());
    }
}
