package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.UserApiKey;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CliLoginServiceTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserApiKeyRepository userApiKeyRepository;

    @InjectMocks
    private CliLoginService cliLoginService;

    private User adminUser;
    private Project project;
    private CreateApiKeyResponse apiKeyResponse;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");

        project = new Project();
        project.setId("proj-1");
        project.setName("My Project");

        apiKeyResponse = new CreateApiKeyResponse("key-id-1", "CLI Key", "ck_abc123", OffsetDateTime.now());
    }

    @Test
    void generateCredentialsReturnsCorrectPayload() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(userApiKeyRepository.findByUserIdAndRevokedAtIsNullAndLabelStartingWith(anyString(), anyString()))
                .thenReturn(List.of());
        when(apiKeyService.generateUserApiKey(anyString(), eq(adminUser))).thenReturn(apiKeyResponse);

        CliCallbackResponse result = cliLoginService.generateCredentials(3131, "proj-1", adminUser);

        assertThat(result.getApiKey()).isEqualTo("ck_abc123");
        assertThat(result.getProjectId()).isEqualTo("proj-1");
        assertThat(result.getProjectName()).isEqualTo("My Project");
        assertThat(result.getEmail()).isEqualTo("admin@example.com");
        verify(apiKeyService).generateUserApiKey(anyString(), eq(adminUser));
    }
}
