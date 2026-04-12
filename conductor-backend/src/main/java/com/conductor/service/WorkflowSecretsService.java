package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowSecret;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowSecretRepository;
import com.conductor.workflow.WorkflowSecretsEncryptionService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkflowSecretsService {

    private static final int MAX_SECRETS_PER_PROJECT = 50;
    private static final java.util.regex.Pattern KEY_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final WorkflowSecretRepository secretRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowSecretsEncryptionService encryptionService;

    public WorkflowSecretsService(WorkflowSecretRepository secretRepository,
                                   ProjectRepository projectRepository,
                                   ProjectSecurityService projectSecurityService,
                                   WorkflowSecretsEncryptionService encryptionService) {
        this.secretRepository = secretRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public WorkflowSecret createSecret(String projectId, String userId, String key, String value) {
        requireAdminOrCreator(projectId, userId);
        validateKey(key);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (secretRepository.countByProjectId(projectId) >= MAX_SECRETS_PER_PROJECT) {
            throw new BusinessException("Secret limit (50) reached for this project");
        }
        if (secretRepository.findByProjectIdAndKey(projectId, key).isPresent()) {
            throw new BusinessException("A secret with key '" + key + "' already exists");
        }

        WorkflowSecret secret = new WorkflowSecret();
        secret.setProject(project);
        secret.setKey(key);
        secret.setEncryptedValue(encryptionService.encrypt(value));
        return secretRepository.save(secret);
    }

    @Transactional
    public WorkflowSecret updateSecret(String projectId, String userId, String key, String value) {
        requireAdminOrCreator(projectId, userId);
        WorkflowSecret secret = secretRepository.findByProjectIdAndKey(projectId, key)
                .orElseThrow(() -> new EntityNotFoundException("Secret not found: " + key));
        secret.setEncryptedValue(encryptionService.encrypt(value));
        return secretRepository.save(secret);
    }

    @Transactional
    public void deleteSecret(String projectId, String userId, String key) {
        requireAdminOrCreator(projectId, userId);
        secretRepository.findByProjectIdAndKey(projectId, key)
                .orElseThrow(() -> new EntityNotFoundException("Secret not found: " + key));
        secretRepository.deleteByProjectIdAndKey(projectId, key);
    }

    public List<WorkflowSecret> listSecretKeys(String projectId) {
        return secretRepository.findByProjectId(projectId);
    }

    /** For internal execution use only — never expose via API */
    public Map<String, String> resolveSecrets(String projectId) {
        return secretRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        WorkflowSecret::getKey,
                        s -> encryptionService.decrypt(s.getEncryptedValue())
                ));
    }

    private void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessException("Secret key must match [A-Z][A-Z0-9_]*, max 64 characters");
        }
    }

    private void requireAdminOrCreator(String projectId, String userId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, userId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage workflow secrets");
        }
    }
}
