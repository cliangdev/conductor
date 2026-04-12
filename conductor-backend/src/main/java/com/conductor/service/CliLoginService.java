package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.generated.model.CreateUserApiKeyRequest;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CliLoginService {

    private static final Logger log = LoggerFactory.getLogger(CliLoginService.class);
    private static final String CLI_KEY_LABEL_PREFIX = "CLI - ";

    private final UserApiKeyService userApiKeyService;
    private final ProjectRepository projectRepository;

    public CliLoginService(
            UserApiKeyService userApiKeyService,
            ProjectRepository projectRepository) {
        this.userApiKeyService = userApiKeyService;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public CliCallbackResponse generateCredentials(int port, String projectId, User caller) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        String keyName = CLI_KEY_LABEL_PREFIX + caller.getEmail();
        log.info("Generating CLI API key for user={} project={}", caller.getEmail(), projectId);
        CreateUserApiKeyRequest keyRequest = new CreateUserApiKeyRequest().label(keyName);
        CreateUserApiKeyResponse apiKeyResponse = userApiKeyService.createUserApiKey(keyRequest, caller);
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
