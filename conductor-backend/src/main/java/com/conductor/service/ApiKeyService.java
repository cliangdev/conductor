package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ApiKeyResponse;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;

    public ApiKeyService(
            ProjectApiKeyRepository projectApiKeyRepository,
            ProjectRepository projectRepository,
            ProjectSecurityService projectSecurityService) {
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
    }

    @Transactional
    public CreateApiKeyResponse generateApiKey(String projectId, String name, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            log.warn("generateApiKey denied: user={} is not admin of project={}", caller.getEmail(), projectId);
            throw new ForbiddenException("Caller is not a project admin");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        String rawKey = "ck_" + UUID.randomUUID().toString().replace("-", "");

        ProjectApiKey apiKey = new ProjectApiKey();
        apiKey.setProject(project);
        apiKey.setName(name);
        apiKey.setKeyValue(rawKey);

        projectApiKeyRepository.save(apiKey);

        return new CreateApiKeyResponse(apiKey.getId(), apiKey.getName(), rawKey, apiKey.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }

        return projectApiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId)
                .stream()
                .map(this::toApiKeyResponse)
                .toList();
    }

    @Transactional
    public void revokeApiKey(String projectId, String keyId, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new ForbiddenException("Caller is not a project admin");
        }

        ProjectApiKey apiKey = projectApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new EntityNotFoundException("API key not found"));

        if (!apiKey.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("API key not found");
        }

        apiKey.setRevokedAt(OffsetDateTime.now());
        projectApiKeyRepository.save(apiKey);
    }

    private ApiKeyResponse toApiKeyResponse(ProjectApiKey apiKey) {
        return new ApiKeyResponse(apiKey.getId(), apiKey.getName(), apiKey.getCreatedAt())
                .lastUsedAt(apiKey.getLastUsedAt());
    }
}
