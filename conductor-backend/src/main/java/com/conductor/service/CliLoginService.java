package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CliLoginService {

    private static final Logger log = LoggerFactory.getLogger(CliLoginService.class);

    private final ApiKeyService apiKeyService;
    private final ProjectRepository projectRepository;

    public CliLoginService(
            ApiKeyService apiKeyService,
            ProjectRepository projectRepository) {
        this.apiKeyService = apiKeyService;
        this.projectRepository = projectRepository;
    }

    public CliCallbackResponse generateCredentials(int port, String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        String keyName = "CLI - " + caller.getEmail() + " - " + Instant.now().toEpochMilli();
        log.info("Generating CLI API key for user={} project={}", caller.getEmail(), projectId);
        CreateApiKeyResponse apiKeyResponse = apiKeyService.generateUserApiKey(keyName, caller);
        log.info("CLI API key created id={} user={} project={}", apiKeyResponse.getId(), caller.getEmail(), projectId);

        return new CliCallbackResponse(
                "API key generated",
                apiKeyResponse.getKey(),
                projectId,
                project.getName(),
                caller.getEmail()
        );
    }
}
