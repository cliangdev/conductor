package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.CliNotReachableException;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Service
public class CliLoginService {

    private final ApiKeyService apiKeyService;
    private final ProjectRepository projectRepository;
    private final RestTemplate restTemplate;

    public CliLoginService(
            ApiKeyService apiKeyService,
            ProjectRepository projectRepository,
            RestTemplate restTemplate) {
        this.apiKeyService = apiKeyService;
        this.projectRepository = projectRepository;
        this.restTemplate = restTemplate;
    }

    public String sendApiKeyToCli(int port, String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        String keyName = "CLI - " + caller.getEmail() + " - " + Instant.now().toEpochMilli();
        CreateApiKeyResponse apiKeyResponse = apiKeyService.generateApiKey(projectId, keyName, caller);

        Map<String, Object> payload = Map.of(
                "apiKey", apiKeyResponse.getKey(),
                "projectId", projectId,
                "projectName", project.getName(),
                "email", caller.getEmail()
        );

        String callbackUrl = "http://localhost:" + port + "/oauth/callback";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForEntity(callbackUrl, request, Void.class);
        } catch (ResourceAccessException e) {
            throw new CliNotReachableException("CLI callback server not reachable", e);
        }

        return "API key sent to CLI";
    }
}
