package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.entity.User;
import com.conductor.generated.model.GithubRepositoriesResponse;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class GitHubAppControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String FRONTEND = "https://app.example.com";

    @Mock private GitHubAppService gitHubAppService;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private IntegrationOAuthStateRepository stateRepository;
    @Mock private ProjectSecurityService projectSecurityService;

    private GitHubAppController controller;

    @BeforeEach
    void setUp() {
        controller = new GitHubAppController(gitHubAppService, connectionService, connectionRepository,
                stateRepository, projectSecurityService, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "frontendUrl", FRONTEND);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    // ---- #13 / #17: listRepositories is ADMIN/CREATOR only ----

    @Test
    void listRepositories_rejectsReviewer_withAccessDenied() {
        authenticateAs("reviewer-1");
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "reviewer-1")).thenReturn(false);

        assertThatThrownBy(() -> controller.listGithubRepositories(PROJECT_ID, "conn-1"))
                .isInstanceOf(AccessDeniedException.class);

        verify(gitHubAppService, never()).listInstallationRepositories(anyString());
    }

    @Test
    void listRepositories_allowsAdminOrCreator_andReturnsRepos() {
        authenticateAs("admin-1");
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "admin-1")).thenReturn(true);

        Connection conn = new Connection();
        conn.setProjectId(PROJECT_ID);
        conn.setConfigJson("{\"installationId\":\"42\",\"accountLogin\":\"acme\","
                + "\"installationHtmlUrl\":\"https://github.com/x\",\"repositorySelection\":\"all\"}");
        when(connectionService.getById("conn-1", "github")).thenReturn(Optional.of(conn));
        when(gitHubAppService.listInstallationRepositories("42"))
                .thenReturn(List.of(new GitHubAppService.Repo("acme/secret", true)));

        ResponseEntity<GithubRepositoriesResponse> resp = controller.listGithubRepositories(PROJECT_ID, "conn-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        GithubRepositoriesResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getRepositories()).singleElement()
                .satisfies(r -> {
                    assertThat(r.getFullName()).isEqualTo("acme/secret");
                    assertThat(r.getPrivate()).isTrue();
                });
        assertThat(body.getAccountLogin()).isEqualTo("acme");
        assertThat(body.getRepositorySelection()).isEqualTo("all");
    }

    // ---- #14: callback redirects gracefully (no 500) when getInstallation throws, and consumes state ----

    @Test
    void callback_whenGetInstallationThrows_redirectsAndConsumesState() {
        IntegrationOAuthState row = new IntegrationOAuthState();
        row.setState("st-1");
        row.setProjectId(PROJECT_ID);
        row.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        row.setConfigJson(Map.of("connectedBy", "admin-1"));
        when(stateRepository.findById("st-1")).thenReturn(Optional.of(row));
        when(gitHubAppService.getInstallation("99"))
                .thenThrow(new RuntimeException("GitHub API call failed"));

        ResponseEntity<Void> resp = controller.handleGithubSetupCallback("99", "install", "st-1");

        // Graceful 302 back to the project integrations page, not a 500.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString())
                .isEqualTo(FRONTEND + "/app/projects/" + PROJECT_ID + "/integrations/github");
        // State consumed before the external call, so it can never be replayed.
        verify(stateRepository).deleteById("st-1");
        verify(connectionService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void callback_invalidState_bouncesToApp() {
        when(stateRepository.findById("nope")).thenReturn(Optional.empty());

        ResponseEntity<Void> resp = controller.handleGithubSetupCallback("99", "install", "nope");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo(FRONTEND + "/app");
        verify(gitHubAppService, never()).getInstallation(anyString());
    }

    @Test
    void callback_happyPath_upsertsConnectionAndConsumesState() {
        IntegrationOAuthState row = new IntegrationOAuthState();
        row.setState("st-2");
        row.setProjectId(PROJECT_ID);
        row.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        row.setConfigJson(Map.of("connectedBy", "admin-1"));
        when(stateRepository.findById("st-2")).thenReturn(Optional.of(row));
        when(gitHubAppService.getInstallation("42"))
                .thenReturn(new GitHubAppService.InstallationInfo("acme", "https://github.com/x", "all"));
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of());
        Connection created = new Connection();
        created.setProjectId(PROJECT_ID);
        lenient().when(connectionService.create(eq(PROJECT_ID), eq("github"), any(), eq("acme"), eq("admin-1")))
                .thenReturn(created);

        ResponseEntity<Void> resp = controller.handleGithubSetupCallback("42", "install", "st-2");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        verify(stateRepository).deleteById("st-2");
        verify(connectionService).create(eq(PROJECT_ID), eq("github"), any(), eq("acme"), eq("admin-1"));
    }

    // ---- #17: startInstallation requires ADMIN/CREATOR ----

    @Test
    void startInstallation_rejectsNonAdminCreator() {
        authenticateAs("reviewer-1");
        when(projectSecurityService.isAdminOrCreator(PROJECT_ID, "reviewer-1")).thenReturn(false);

        assertThatThrownBy(() -> controller.startGithubInstallation(PROJECT_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(stateRepository, never()).save(any());
    }
}
