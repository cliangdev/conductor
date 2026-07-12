package com.conductor.service;

import com.conductor.entity.WorkflowSecret;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowSecretRepository;
import com.conductor.workflow.WorkflowSecretsEncryptionService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access rules for the secrets surface: any project member may list KEY NAMES; a non-member gets
 * the same "Project not found" as every other membership-gated endpoint (existence not revealed).
 * Mutations require ADMIN/CREATOR and are covered by the role check inside the service.
 */
class WorkflowSecretsServiceTest {

    private WorkflowSecretRepository secretRepository;
    private ProjectSecurityService projectSecurityService;
    private WorkflowSecretsService service;

    @BeforeEach
    void setUp() {
        secretRepository = mock(WorkflowSecretRepository.class);
        projectSecurityService = mock(ProjectSecurityService.class);
        service = new WorkflowSecretsService(secretRepository, mock(ProjectRepository.class),
                projectSecurityService, mock(WorkflowSecretsEncryptionService.class));
    }

    @Test
    void listSecretKeys_memberSeesKeys() {
        when(projectSecurityService.isProjectMember("p1", "u1")).thenReturn(true);
        WorkflowSecret secret = new WorkflowSecret();
        secret.setKey("DISCORD_WEBHOOK_URL");
        when(secretRepository.findByProjectId("p1")).thenReturn(List.of(secret));

        List<WorkflowSecret> keys = service.listSecretKeys("p1", "u1");

        assertEquals(1, keys.size());
        assertEquals("DISCORD_WEBHOOK_URL", keys.get(0).getKey());
    }

    @Test
    void listSecretKeys_nonMemberGetsNotFound() {
        when(projectSecurityService.isProjectMember("p1", "outsider")).thenReturn(false);

        assertThrows(EntityNotFoundException.class, () -> service.listSecretKeys("p1", "outsider"));
        verify(secretRepository, never()).findByProjectId(anyString());
    }
}
