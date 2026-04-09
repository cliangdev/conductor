package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.CliNotReachableException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private RestTemplate restTemplate;

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
    @SuppressWarnings("unchecked")
    void sendApiKeyToCliPostsCorrectPayload() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(apiKeyService.generateApiKey(eq("proj-1"), anyString(), eq(adminUser))).thenReturn(apiKeyResponse);
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class))).thenReturn(null);

        String result = cliLoginService.sendApiKeyToCli(8080, "proj-1", adminUser);

        assertThat(result).isEqualTo("API key sent to CLI");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), entityCaptor.capture(), eq(Void.class));

        assertThat(urlCaptor.getValue()).isEqualTo("http://localhost:8080/oauth/callback");

        Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertThat(body).containsEntry("apiKey", "ck_abc123");
        assertThat(body).containsEntry("projectId", "proj-1");
        assertThat(body).containsEntry("projectName", "My Project");
        assertThat(body).containsEntry("email", "admin@example.com");
    }

    @Test
    void sendApiKeyToCliThrows502WhenCliNotReachable() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(apiKeyService.generateApiKey(eq("proj-1"), anyString(), eq(adminUser))).thenReturn(apiKeyResponse);
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> cliLoginService.sendApiKeyToCli(9999, "proj-1", adminUser))
                .isInstanceOf(CliNotReachableException.class)
                .hasMessageContaining("CLI callback server not reachable");
    }

    @Test
    void sendApiKeyToCliThrows403WhenCallerNotAdmin() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(apiKeyService.generateApiKey(eq("proj-1"), anyString(), eq(adminUser)))
                .thenThrow(new ForbiddenException("Caller is not a project admin"));

        assertThatThrownBy(() -> cliLoginService.sendApiKeyToCli(8080, "proj-1", adminUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sendApiKeyToCliUsesCorrectPort() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(apiKeyService.generateApiKey(eq("proj-1"), anyString(), eq(adminUser))).thenReturn(apiKeyResponse);
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class))).thenReturn(null);

        cliLoginService.sendApiKeyToCli(12345, "proj-1", adminUser);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), any(), eq(Void.class));
        assertThat(urlCaptor.getValue()).isEqualTo("http://localhost:12345/oauth/callback");
    }
}
