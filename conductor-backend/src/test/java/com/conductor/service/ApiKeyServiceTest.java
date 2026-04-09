package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.ApiKeyResponse;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
class ApiKeyServiceTest {

    @Mock
    private ProjectApiKeyRepository projectApiKeyRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @InjectMocks
    private ApiKeyService apiKeyService;

    private User adminUser;
    private User memberUser;
    private Project project;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");

        memberUser = new User();
        memberUser.setId("member-1");
        memberUser.setEmail("member@example.com");

        project = new Project();
        project.setId("proj-1");
        project.setName("Test Project");
    }

    @Test
    void generateApiKeyReturnsRawKeyAndStoresHash() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-1")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(projectApiKeyRepository.save(any(ProjectApiKey.class))).thenAnswer(invocation -> {
            ProjectApiKey key = invocation.getArgument(0);
            key.setId("key-id-1");
            key.setCreatedAt(OffsetDateTime.now());
            return key;
        });

        CreateApiKeyResponse response = apiKeyService.generateApiKey("proj-1", "My Key", adminUser);

        ArgumentCaptor<ProjectApiKey> captor = ArgumentCaptor.forClass(ProjectApiKey.class);
        verify(projectApiKeyRepository).save(captor.capture());
        ProjectApiKey saved = captor.getValue();

        assertThat(response.getKey()).startsWith("ck_");
        assertThat(response.getKey()).hasSize(35);
        assertThat(saved.getKeyHash()).isNotEqualTo(response.getKey());
        assertThat(saved.getKeyHash()).isEqualTo(apiKeyService.sha256(response.getKey()));
        assertThat(saved.getKeyHash()).hasSize(64);
        assertThat(response.getName()).isEqualTo("My Key");
    }

    @Test
    void generateApiKeyThrows403ForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "member-1")).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.generateApiKey("proj-1", "Key", memberUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listApiKeysReturnsKeysWithoutHashValues() {
        when(projectSecurityService.isProjectMember("proj-1", "member-1")).thenReturn(true);

        ProjectApiKey key = buildKey("key-1", "API Key 1");
        when(projectApiKeyRepository.findByProjectIdAndRevokedAtIsNull("proj-1")).thenReturn(List.of(key));

        List<ApiKeyResponse> result = apiKeyService.listApiKeys("proj-1", memberUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("key-1");
        assertThat(result.get(0).getName()).isEqualTo("API Key 1");
    }

    @Test
    void listApiKeysThrows404ForNonMember() {
        when(projectSecurityService.isProjectMember("proj-1", "member-1")).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.listApiKeys("proj-1", memberUser))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void revokeApiKeySetsRevokedAt() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-1")).thenReturn(true);

        ProjectApiKey key = buildKey("key-1", "My Key");
        when(projectApiKeyRepository.findById("key-1")).thenReturn(Optional.of(key));
        when(projectApiKeyRepository.save(any(ProjectApiKey.class))).thenReturn(key);

        apiKeyService.revokeApiKey("proj-1", "key-1", adminUser);

        assertThat(key.getRevokedAt()).isNotNull();
        assertThat(key.isRevoked()).isTrue();
        verify(projectApiKeyRepository).save(key);
    }

    @Test
    void revokeApiKeyThrows403ForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "member-1")).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.revokeApiKey("proj-1", "key-1", memberUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void revokeApiKeyThrows404WhenKeyBelongsToDifferentProject() {
        when(projectSecurityService.isProjectAdmin("proj-1", "admin-1")).thenReturn(true);

        Project otherProject = new Project();
        otherProject.setId("other-proj");
        ProjectApiKey key = new ProjectApiKey();
        key.setId("key-1");
        key.setProject(otherProject);
        key.setName("Key");
        key.setKeyHash("hash");
        key.setCreatedAt(OffsetDateTime.now());

        when(projectApiKeyRepository.findById("key-1")).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.revokeApiKey("proj-1", "key-1", adminUser))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void sha256ProducesDeterministicHash() {
        String hash1 = apiKeyService.sha256("some-key");
        String hash2 = apiKeyService.sha256("some-key");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    private ProjectApiKey buildKey(String id, String name) {
        ProjectApiKey key = new ProjectApiKey();
        key.setId(id);
        key.setProject(project);
        key.setName(name);
        key.setKeyHash("hash-" + id);
        key.setCreatedAt(OffsetDateTime.now());
        return key;
    }
}
