package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.generated.model.CreateUserApiKeyRequest;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CliLoginServiceTest {

    @Mock
    private UserApiKeyService userApiKeyService;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private CliLoginService cliLoginService;

    private User adminUser;
    private Project project;
    private CreateUserApiKeyResponse apiKeyResponse;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");

        project = new Project();
        project.setId("proj-1");
        project.setName("My Project");

        apiKeyResponse = new CreateUserApiKeyResponse("key-id-1", "uk_abc123", "****c123", OffsetDateTime.now())
                .label("CLI - admin@example.com");
    }

    @Test
    void generateCredentialsReturnsCorrectPayload() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(userApiKeyService.createUserApiKey(any(CreateUserApiKeyRequest.class), eq(adminUser)))
                .thenReturn(apiKeyResponse);

        CliCallbackResponse result = cliLoginService.generateCredentials(3131, "proj-1", adminUser);

        assertThat(result.getApiKey()).isEqualTo("uk_abc123");
        assertThat(result.getProjectId()).isEqualTo("proj-1");
        assertThat(result.getProjectName()).isEqualTo("My Project");
        assertThat(result.getEmail()).isEqualTo("admin@example.com");
        verify(userApiKeyService).createUserApiKey(any(CreateUserApiKeyRequest.class), eq(adminUser));
    }
}
