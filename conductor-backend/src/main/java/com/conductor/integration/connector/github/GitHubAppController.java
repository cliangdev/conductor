package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.api.GithubApi;
import com.conductor.generated.model.BindGithubPatRequest;
import com.conductor.generated.model.ConnectionResponse;
import com.conductor.generated.model.GithubInstallUrlResponse;
import com.conductor.generated.model.GithubRepositoriesResponse;
import com.conductor.generated.model.GithubRepository;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub App install lifecycle + repo listing, isolated to the github connector. Implements the generated
 * {@link GithubApi} interface (OpenAPI-first); the same URLs/verbs are declared in {@code openapi.yaml}
 * under the {@code github} tag. RESTful: an installation is created via the install redirect and confirmed
 * on the setup callback; repositories are a read-only sub-resource of a connection. Generic — works for any
 * GitHub account and any project; a connection is keyed by (projectId, installationId).
 *
 * Access control goes through {@link ProjectSecurityService} (per CLAUDE.md — always check membership/role
 * there). Connection lifecycle (create/update/delete) is ADMIN/CREATOR, and so are starting an installation
 * and listing repositories (repo listing is part of configuring the connection, and exposes the
 * installation's private-repo inventory, so it must not be open to plain members/reviewers).
 */
@RestController
public class GitHubAppController implements GithubApi {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppController.class);
    private static final String CONNECTOR_ID = "github";

    private final GitHubAppService gitHubAppService;
    private final ConnectionService connectionService;
    private final ConnectionRepository connectionRepository;
    private final IntegrationOAuthStateRepository stateRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    @Value("${FRONTEND_URL:http://localhost:3000}")
    private String frontendUrl;

    public GitHubAppController(GitHubAppService gitHubAppService,
                               ConnectionService connectionService,
                               ConnectionRepository connectionRepository,
                               IntegrationOAuthStateRepository stateRepository,
                               ProjectSecurityService projectSecurityService,
                               ObjectMapper objectMapper) {
        this.gitHubAppService = gitHubAppService;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.stateRepository = stateRepository;
        this.projectSecurityService = projectSecurityService;
        this.objectMapper = objectMapper;
    }

    /** Begin an installation: returns the GitHub install URL (native account + repo picker). */
    @Override
    public ResponseEntity<GithubInstallUrlResponse> startGithubInstallation(String projectId) {
        User user = requireAdminOrCreator(projectId);
        if (!gitHubAppService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub App is not configured on this server");
        }
        stateRepository.deleteByExpiresAtBefore(OffsetDateTime.now());

        String state = randomState();
        IntegrationOAuthState row = new IntegrationOAuthState();
        row.setState(state);
        row.setProjectId(projectId);
        row.setConnectorId(CONNECTOR_ID);
        row.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        Map<String, Object> stateConfig = new HashMap<>();
        stateConfig.put("connectedBy", user.getId());
        row.setConfigJson(stateConfig);
        stateRepository.save(row);

        return ResponseEntity.ok(new GithubInstallUrlResponse()
                .installUrl(gitHubAppService.buildInstallUrl(state)));
    }

    /**
     * Bind a project-level GitHub Personal Access Token — a second, project-owned credential
     * alongside the App connection, taking precedence over it at credential-resolution time (see
     * {@link com.conductor.service.ActiveConnectionResolver}) while ACTIVE. Validates the token
     * against GitHub before storing it (rejects a bad/revoked token immediately). Finds the single
     * ACTIVE PAT connection for this project, if any, and updates it in place (rebind/rotate);
     * otherwise creates one.
     *
     * <p>Race-safe the same way {@code ConnectionService#getOrCreateSingle} is: the real guarantee
     * is the partial unique index {@code uq_connection_pat_per_project_connector}. If two binds race
     * past the fast-path read, the loser's insert (isolated in {@code createInNewTx}'s own {@code
     * REQUIRES_NEW} tx) throws {@link DataIntegrityViolationException}; caught here and resolved by
     * re-reading the winning row instead of surfacing a raw 500.
     */
    @Override
    public ResponseEntity<ConnectionResponse> bindGithubPat(String projectId, BindGithubPatRequest request) {
        User user = requireAdminOrCreator(projectId);
        if (request == null || request.getToken() == null || request.getToken().isBlank()) {
            throw new BusinessException("token is required");
        }

        GitHubAppService.PatValidationResult validation =
                gitHubAppService.validatePersonalAccessToken(request.getToken());

        OffsetDateTime expiresAt = validation.expiresAt() != null
                ? validation.expiresAt().atOffset(ZoneOffset.UTC)
                : request.getExpiresAt();
        String label = request.getLabel() != null && !request.getLabel().isBlank()
                ? request.getLabel()
                : "PAT (@" + validation.login() + ")";

        Connection conn = findActivePat(projectId).orElseGet(() -> {
            try {
                return connectionService.createInNewTx(projectId, CONNECTOR_ID, AuthType.PAT, label, user.getId());
            } catch (DataIntegrityViolationException e) {
                // Lost the insert race against a concurrent bind — the winning row now exists.
                return findActivePat(projectId).orElseThrow(() -> e);
            }
        });

        connectionService.updateLabel(conn, label);
        connectionService.storeTokens(conn, request.getToken(), null, expiresAt);

        return ResponseEntity.status(HttpStatus.CREATED).body(toConnectionResponse(conn));
    }

    private Optional<Connection> findActivePat(String projectId) {
        return connectionRepository.findByProjectIdAndConnectorId(projectId, CONNECTOR_ID).stream()
                .filter(c -> AuthType.PAT.name().equals(c.getAuthType()) && "ACTIVE".equals(c.getStatus()))
                .findFirst();
    }

    /**
     * GitHub's Setup-URL redirect target (public). Trust model:
     * <ul>
     *   <li>The single-use, short-TTL, random {@code state} is the CSRF binding to (projectId, connectedBy).
     *       The GitHub App setup-URL flow cannot know the GitHub account before the install completes, so the
     *       state row — created by an authenticated ADMIN/CREATOR in {@link #startGithubInstallation} — is the
     *       only thing that ties this anonymous browser redirect back to a project + actor.</li>
     *   <li>{@code installation_id} is trusted as delivered by GitHub's redirect; it is not independently
     *       verifiable here beyond the live {@code getInstallation} lookup (which only proves the id exists,
     *       not that this browser owns it). The state binding is what prevents cross-project abuse.</li>
     * </ul>
     * Hardening: the state row is consumed (deleted) before the external GitHub call, so it can never be
     * replayed even if anything downstream fails; a failed {@code getInstallation} redirects the user
     * gracefully back to the app instead of surfacing a raw 500.
     */
    @Override
    public ResponseEntity<Void> handleGithubSetupCallback(String installationId, String setupAction, String state) {
        Optional<IntegrationOAuthState> stateRow = state != null
                ? stateRepository.findById(state) : Optional.empty();
        if (stateRow.isEmpty() || stateRow.get().getExpiresAt().isBefore(OffsetDateTime.now())
                || installationId == null || installationId.isBlank()) {
            // Invalid/expired state, or no installation yet (e.g. setup_action=request): bounce to the app.
            stateRow.ifPresent(r -> stateRepository.deleteById(r.getState()));
            return redirect(frontendUrl + "/app");
        }

        IntegrationOAuthState s = stateRow.get();
        String projectId = s.getProjectId();
        String connectedBy = s.getConfigJson() != null
                ? String.valueOf(s.getConfigJson().getOrDefault("connectedBy", null)) : null;

        // Consume the single-use state up front: once we've read what we need, delete it so it can never be
        // replayed — regardless of whether the GitHub call below succeeds or throws.
        stateRepository.deleteById(s.getState());

        GitHubAppService.InstallationInfo info;
        try {
            info = gitHubAppService.getInstallation(installationId);
        } catch (RuntimeException e) {
            // A live GitHub API hiccup must not 500 the user's browser. State is already consumed; just
            // redirect back to the project's integrations page so they can retry the install.
            log.warn("GitHub setup callback: getInstallation({}) failed for project {} — {}",
                    installationId, projectId, e.getMessage());
            return redirect(frontendUrl + "/app/projects/" + projectId + "/integrations/github");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("installationId", installationId);
        config.put("accountLogin", info.accountLogin());
        config.put("installationHtmlUrl", info.htmlUrl());
        config.put("repositorySelection", info.repositorySelection());

        Optional<Connection> existing = connectionRepository
                .findByConnectorIdAndConfigValue(CONNECTOR_ID, "installationId", installationId).stream()
                .filter(c -> c.getProjectId().equals(projectId))
                .findFirst();

        if (existing.isPresent()) {
            Connection conn = existing.get();
            connectionService.updateLabel(conn, info.accountLogin());
            connectionService.updateConfig(conn, config);
        } else {
            Connection conn = connectionService.create(
                    projectId, CONNECTOR_ID, AuthType.APP, info.accountLogin(), connectedBy);
            connectionService.updateConfig(conn, config);
        }

        return redirect(frontendUrl + "/app/projects/" + projectId + "/integrations/github");
    }

    /** Repos the installation can access (read-only sub-resource; granting is done on GitHub). */
    @Override
    public ResponseEntity<GithubRepositoriesResponse> listGithubRepositories(String projectId, String connectionId) {
        // Listing repositories exposes the installation's full private-repo inventory and is part of
        // configuring the connection — gate it to ADMIN/CREATOR, consistent with connection management.
        requireAdminOrCreator(projectId);
        Connection conn = connectionService.getById(connectionId, CONNECTOR_ID)
                .filter(c -> c.getProjectId().equals(projectId))
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + connectionId));
        Map<String, Object> config = parseConfig(conn.getConfigJson());
        String installationId = String.valueOf(config.get("installationId"));
        List<GithubRepository> repositories = gitHubAppService
                .listInstallationRepositories(installationId).stream()
                .map(r -> new GithubRepository().fullName(r.fullName())._private(r.isPrivate()))
                .toList();
        GithubRepositoriesResponse response = new GithubRepositoriesResponse()
                .repositories(repositories)
                // The GitHub-side management page for this installation — the frontend's "Add/Remove
                // repositories" buttons deep-link here (GitHub's recommended pattern).
                .installationHtmlUrl(asString(config.get("installationHtmlUrl")))
                .accountLogin(asString(config.get("accountLogin")))
                .repositorySelection(asString(config.get("repositorySelection")));
        return ResponseEntity.ok(response);
    }

    // ---- helpers ----

    /** Mirrors {@code IntegrationController#toConnectionResponse}, minus the webhook fields (never
     *  applicable to a PAT connection). */
    private ConnectionResponse toConnectionResponse(Connection conn) {
        return new ConnectionResponse()
                .id(conn.getId())
                .connectorId(conn.getConnectorId())
                .label(conn.getDisplayLabel())
                .status(conn.getStatus())
                .authType(conn.getAuthType())
                .tokenExpiresAt(conn.getTokenExpiresAt())
                .connectedAt(conn.getCreatedAt());
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private String randomState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private User requireAdminOrCreator(String projectId) {
        User user = currentUser();
        if (!projectSecurityService.isAdminOrCreator(projectId, user.getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
        return user;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
