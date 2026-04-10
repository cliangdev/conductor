package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ApiKeysApi;
import com.conductor.generated.model.ApiKeyResponse;
import com.conductor.generated.model.CreateApiKeyRequest;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.generated.model.CreateUserApiKeyRequest;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.generated.model.UserApiKeyResponse;
import com.conductor.service.ApiKeyService;
import com.conductor.service.UserApiKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController implements ApiKeysApi {

    private final ApiKeyService apiKeyService;
    private final UserApiKeyService userApiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService, UserApiKeyService userApiKeyService) {
        this.apiKeyService = apiKeyService;
        this.userApiKeyService = userApiKeyService;
    }

    @Override
    public ResponseEntity<CreateApiKeyResponse> createApiKey(String projectId, CreateApiKeyRequest createApiKeyRequest) {
        User caller = currentUser();
        CreateApiKeyResponse response = apiKeyService.generateApiKey(projectId, createApiKeyRequest.getName(), caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys(String projectId) {
        User caller = currentUser();
        List<ApiKeyResponse> keys = apiKeyService.listApiKeys(projectId, caller);
        return ResponseEntity.ok(keys);
    }

    @Override
    public ResponseEntity<Void> revokeApiKey(String projectId, String keyId) {
        User caller = currentUser();
        apiKeyService.revokeApiKey(projectId, keyId, caller);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<UserApiKeyResponse>> listUserApiKeys() {
        User caller = currentUser();
        List<UserApiKeyResponse> keys = userApiKeyService.listUserApiKeys(caller);
        return ResponseEntity.ok(keys);
    }

    @Override
    public ResponseEntity<CreateUserApiKeyResponse> createUserApiKey(CreateUserApiKeyRequest createUserApiKeyRequest) {
        User caller = currentUser();
        CreateUserApiKeyResponse response = userApiKeyService.createUserApiKey(createUserApiKeyRequest, caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<Void> deleteUserApiKey(String keyId) {
        User caller = currentUser();
        userApiKeyService.deleteUserApiKey(keyId, caller);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
