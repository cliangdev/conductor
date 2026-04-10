package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.ApiKeysApi;
import com.conductor.generated.model.ApiKeyResponse;
import com.conductor.generated.model.CreateApiKeyRequest;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.service.ApiKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController implements ApiKeysApi {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
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

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
