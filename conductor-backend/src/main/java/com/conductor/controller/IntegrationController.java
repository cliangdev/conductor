package com.conductor.controller;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.entity.User;
import com.conductor.entity.WebhookEvent;
import com.conductor.exception.BusinessException;
import com.conductor.generated.api.IntegrationsApi;
import com.conductor.generated.model.BqDatasetsResponse;
import com.conductor.generated.model.BqDatasetsResponseDatasetsInner;
import com.conductor.generated.model.ConnectionDataResponse;
import com.conductor.generated.model.ConnectionHealthResponse;
import com.conductor.generated.model.ConnectionResponse;
import com.conductor.generated.model.ConnectionSummary;
import com.conductor.generated.model.ConnectorCatalogConfigFieldDto;
import com.conductor.generated.model.ConnectorCatalogEntryDto;
import com.conductor.generated.model.ConnectorConfigFieldDto;
import com.conductor.generated.model.CreateConnectionRequest;
import com.conductor.generated.model.GcpProjectsResponse;
import com.conductor.generated.model.GcpProjectsResponseProjectsInner;
import com.conductor.generated.model.GscSitesResponse;
import com.conductor.generated.model.GscSitesResponseSitesInner;
import com.conductor.generated.model.IntegrationListItem;
import com.conductor.generated.model.IntegrationToolItem;
import com.conductor.generated.model.OAuthAuthorizeResponse;
import com.conductor.generated.model.UpdateConnectionRequest;
import com.conductor.generated.model.WebhookEventSummary;
import com.conductor.integration.AuthType;
import com.conductor.integration.Capability;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.OAuthFlowService;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.RuntimeTargetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@RestController
public class IntegrationController implements IntegrationsApi {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final IntegrationFetchService fetchService;
    private final OAuthFlowService oAuthFlowService;
    private final ConnectionDataCacheRepository cacheRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;
    /**
     * Present only outside the {@code local} profile (the real {@link GcpBillingConnector} is
     * {@code @Profile("!local")}); empty locally — matching the previous {@code @Profile("!local")}
     * GcpBillingController that served these two endpoints.
     */
    private final Optional<GcpBillingConnector> gcpBillingConnector;
    /** Present only outside the {@code local} profile (the real {@link GscConnector} is {@code @Profile("!local")}). */
    private final Optional<GscConnector> gscConnector;
    private final RuntimeTargetService runtimeTargetService;

    @Value("${BACKEND_URL:}")
    private String backendUrl;

    public IntegrationController(ConnectorRegistry connectorRegistry,
                                ConnectionService connectionService,
                                IntegrationFetchService fetchService,
                                OAuthFlowService oAuthFlowService,
                                ConnectionDataCacheRepository cacheRepository,
                                WebhookEventRepository webhookEventRepository,
                                ProjectSecurityService projectSecurityService,
                                Optional<GcpBillingConnector> gcpBillingConnector,
                                Optional<GscConnector> gscConnector,
                                RuntimeTargetService runtimeTargetService,
                                ObjectMapper objectMapper) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.fetchService = fetchService;
        this.oAuthFlowService = oAuthFlowService;
        this.cacheRepository = cacheRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.projectSecurityService = projectSecurityService;
        this.gcpBillingConnector = gcpBillingConnector;
        this.gscConnector = gscConnector;
        this.runtimeTargetService = runtimeTargetService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<IntegrationListItem>> listIntegrations(String projectId) {
        requireMember(projectId);
        List<IntegrationListItem> items = new ArrayList<>();
        for (Connector connector : connectorRegistry.getAll()) {
            ConnectorMetadata meta = connector.getMetadata();
            ConnectorSpec spec = connector.getSpec();
            List<Connection> connections = connectionService.list(projectId, connector.getId());

            IntegrationListItem item = new IntegrationListItem()
                    .connectorId(connector.getId())
                    .name(meta.name())
                    .category(meta.category().name())
                    .authType(spec.authType().name())
                    .capabilities(connectorRegistry.capabilitiesOf(connector).stream()
                            .map(Capability::name).toList())
                    .singleInstance(spec.singleInstance())
                    .description(meta.description())
                    .iconLabel(meta.iconLabel())
                    .connected(!connections.isEmpty())
                    .configFields(toConfigFieldDtos(spec))
                    .connections(connections.stream().map(this::toConnectionSummary).toList());
            items.add(item);
        }
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<List<ConnectorCatalogEntryDto>> listConnectorCatalog(String projectId) {
        requireMember(projectId);
        List<ConnectorCatalogEntryDto> items = new ArrayList<>();
        for (Connector connector : connectorRegistry.getAll()) {
            ConnectorMetadata meta = connector.getMetadata();
            ConnectorSpec spec = connector.getSpec();
            // "Connected"/active here means a usable connection (ACTIVE status), not merely a row
            // existing — this catalog tells the agent what it can act on right now.
            List<String> activeConnectionIds = connectionService.list(projectId, connector.getId()).stream()
                    .filter(c -> "ACTIVE".equals(c.getStatus()))
                    .map(Connection::getId)
                    .toList();

            items.add(new ConnectorCatalogEntryDto()
                    .id(connector.getId())
                    .name(meta.name())
                    .description(meta.description())
                    .category(meta.category().name())
                    .authType(spec.authType().name())
                    .capabilities(connectorRegistry.capabilitiesOf(connector).stream()
                            .map(Capability::name).toList())
                    .configFields(toCatalogConfigFieldDtos(spec))
                    .connected(!activeConnectionIds.isEmpty())
                    .activeConnectionIds(activeConnectionIds));
        }
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<List<IntegrationToolItem>> listIntegrationTools(String projectId) {
        requireMember(projectId);
        List<IntegrationToolItem> result = connectionService.listForProject(projectId).stream()
            .filter(c -> "ACTIVE".equals(c.getStatus()))
            .flatMap(c -> connectionService.computeToolMetadata(c).map(meta -> {
                IntegrationToolItem item = new IntegrationToolItem();
                item.setConnectionId(c.getId());
                item.setConnectorId(c.getConnectorId());
                item.setDisplayLabel(c.getDisplayLabel());
                List<String> caps = connectorRegistry.getById(c.getConnectorId())
                    .map(connector -> connectorRegistry.capabilitiesOf(connector).stream()
                        .map(cap -> cap.name()).toList())
                    .orElse(List.of());
                item.setCapabilities(caps);
                item.setToolMetadata(meta);
                return item;
            }).stream())
            .toList();
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<List<ConnectionSummary>> listConnections(String projectId, String connectorId) {
        requireMember(projectId);
        List<ConnectionSummary> summaries = connectionService.list(projectId, connectorId).stream()
                .map(this::toConnectionSummary).toList();
        return ResponseEntity.ok(summaries);
    }

    @Override
    public ResponseEntity<ConnectionResponse> createConnection(
            String projectId, String connectorId, CreateConnectionRequest request) {
        requireAdminOrCreator(projectId);
        Connector connector = requireConnector(connectorId);
        ConnectorSpec spec = connector.getSpec();
        String label = request != null ? request.getLabel() : null;

        Connection conn;
        if (spec.singleInstance()) {
            conn = connectionService.getOrCreateSingle(projectId, connectorId, spec.authType());
            if (label != null) {
                conn.setDisplayLabel(label);
            }
        } else {
            conn = connectionService.create(projectId, connectorId, spec.authType(),
                    label, currentUser().getId());
        }

        // Apply non-secret config (e.g. repoFullName, projectId).
        if (request != null && request.getConfigJson() != null && !request.getConfigJson().isEmpty()) {
            connectionService.updateConfig(conn, request.getConfigJson());
        }

        String generatedSecret = null;
        if (spec.authType() == AuthType.API_KEY && request != null && request.getApiKey() != null) {
            connectionService.storeTokens(conn, request.getApiKey(), null, null);
        } else if (spec.authType() == AuthType.SERVICE_ACCOUNT
                && request != null && request.getServiceAccountKey() != null) {
            requireValidServiceAccountKey(request.getServiceAccountKey());
            // The SA JSON key rides the encrypted accessToken slot — same crypto path as API_KEY,
            // no CredentialService change needed.
            connectionService.storeTokens(conn, request.getServiceAccountKey(), null, null);
        } else if (spec.authType() == AuthType.WEBHOOK) {
            generatedSecret = randomSecret();
            connectionService.storeWebhookSecret(conn, generatedSecret);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toConnectionResponse(conn, generatedSecret));
    }

    @Override
    public ResponseEntity<ConnectionResponse> updateConnection(
            String projectId, String connectorId, String connectionId, UpdateConnectionRequest request) {
        requireAdminOrCreator(projectId);
        Connection conn = requireConnection(projectId, connectorId, connectionId);
        if (request != null && request.getLabel() != null) {
            connectionService.updateLabel(conn, request.getLabel());
        }
        if (request != null && request.getConfig() != null && !request.getConfig().isEmpty()) {
            connectionService.updateConfig(conn, request.getConfig());
        }
        return ResponseEntity.ok(toConnectionResponse(conn, null));
    }

    @Override
    public ResponseEntity<Void> deleteConnection(String projectId, String connectorId, String connectionId) {
        requireAdminOrCreator(projectId);
        requireConnection(projectId, connectorId, connectionId);
        // Before the row goes away (runtime_targets.connection_id is ON DELETE SET NULL): flip
        // referencing runtime targets to ERROR and close their cached Cloud Run clients.
        runtimeTargetService.onConnectionDeleted(connectionId);
        connectionService.delete(connectionId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ConnectionDataResponse> getConnectionData(
            String projectId, String connectorId, String connectionId) {
        requireMember(projectId);
        requireConnection(projectId, connectorId, connectionId);
        Optional<ConnectionDataCache> cached = cacheRepository.findByConnectionId(connectionId);
        if (cached.isEmpty()) {
            return ResponseEntity.ok(new ConnectionDataResponse()
                    .connectionId(connectionId).connectorId(connectorId));
        }
        ConnectionDataCache cache = cached.get();
        ConnectorData data = new ConnectorData(parseJson(cache.getDataJson()),
                ConnectorHealth.valueOf(cache.getHealthStatus()), cache.getFetchedAt().toInstant(),
                cache.getErrorMessage());
        return ResponseEntity.ok(connectorDataToResponse(connectorId, connectionId, data));
    }

    @Override
    public ResponseEntity<ConnectionDataResponse> fetchConnectionData(
            String projectId, String connectorId, String connectionId) {
        requireMember(projectId);
        requireConnection(projectId, connectorId, connectionId);
        ConnectorData data = fetchService.fetchData(connectionId, true);
        return ResponseEntity.ok(connectorDataToResponse(connectorId, connectionId, data));
    }

    @Override
    public ResponseEntity<List<WebhookEventSummary>> listConnectionWebhookEvents(
            String projectId, String connectorId, String connectionId) {
        requireMember(projectId);
        requireConnection(projectId, connectorId, connectionId);
        List<WebhookEventSummary> events = webhookEventRepository
                .findTop20ByConnectionIdOrderByReceivedAtDesc(connectionId).stream()
                .map(this::toWebhookEventSummary).toList();
        return ResponseEntity.ok(events);
    }

    @Override
    public ResponseEntity<OAuthAuthorizeResponse> authorizeOAuth(String projectId, String connectorId, Object body) {
        requireAdminOrCreator(projectId);
        requireConnector(connectorId);
        String authUrl = oAuthFlowService.buildAuthorizationUrl(
                projectId, connectorId, oAuthFlowService.oauthCallbackUri());
        return ResponseEntity.ok(new OAuthAuthorizeResponse().authorizationUrl(authUrl));
    }

    @Override
    public ResponseEntity<Void> handleOAuthCallback(String code, String state) {
        String frontendUrl = oAuthFlowService.handleCallback(code, state, oAuthFlowService.oauthCallbackUri());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl)).build();
    }

    @Override
    public ResponseEntity<GcpProjectsResponse> listGcpProjects(String projectId) {
        requireMember(projectId);
        GcpBillingConnector connector = requireGcpBillingConnector();
        String accessToken = requireGcpAccessToken(projectId, "gcp-billing");
        List<Map<String, String>> projects = connector.listGcpProjects(accessToken);
        GcpProjectsResponse response = new GcpProjectsResponse();
        projects.forEach(p -> response.addProjectsItem(
                new GcpProjectsResponseProjectsInner()
                        .projectId(p.get("projectId")).name(p.get("name"))));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<BqDatasetsResponse> listBqDatasets(String projectId, String gcpProjectId) {
        requireMember(projectId);
        GcpBillingConnector connector = requireGcpBillingConnector();
        if (gcpProjectId == null || !gcpProjectId.matches("[a-z0-9A-Z:_\\-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid gcpProjectId format");
        }
        String accessToken = requireGcpAccessToken(projectId, "gcp-billing");
        List<Map<String, String>> datasets = connector.listBqDatasets(accessToken, gcpProjectId);
        BqDatasetsResponse response = new BqDatasetsResponse();
        datasets.forEach(d -> response.addDatasetsItem(
                new BqDatasetsResponseDatasetsInner()
                        .datasetId(d.get("datasetId")).location(d.get("location"))));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<GscSitesResponse> listGscSites(String projectId) {
        requireMember(projectId);
        GscConnector connector = requireGscConnector();
        String accessToken = requireGcpAccessToken(projectId, "gsc");
        List<Map<String, String>> sites = connector.listSites(accessToken);
        GscSitesResponse response = new GscSitesResponse();
        sites.forEach(s -> response.addSitesItem(
                new GscSitesResponseSitesInner()
                        .siteUrl(s.get("siteUrl")).permissionLevel(s.get("permissionLevel"))));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ConnectionHealthResponse> getConnectionHealth(
            String projectId, String connectorId, String connectionId) {
        requireMember(projectId);
        Connection conn = requireConnection(projectId, connectorId, connectionId);
        ConnectionContext ctx = connectionService.toContext(conn);

        boolean oauthConnected = ctx.accessToken() != null;
        String siteUrl = ctx.config() != null ? (String) ctx.config().get("siteUrl") : null;
        boolean configured = siteUrl != null && !siteUrl.isBlank();
        String status = ConnectorHealth.SETUP_REQUIRED.name();
        Boolean propertyAccessible = null;
        String errorMessage = null;

        if (!oauthConnected) {
            errorMessage = "OAuth credentials not stored — reconnect the integration";
        } else if (!configured) {
            errorMessage = "No property configured";
        } else {
            // Refresh token if close to expiry before probing the third-party API
            String token = requireGcpAccessToken(projectId, connectorId);
            ConnectionContext refreshed = new ConnectionContext(
                    ctx.projectId(), ctx.connectorId(), ctx.connectionId(),
                    token, ctx.refreshToken(), null, ctx.config(), ctx.webhookSecret());
            Optional<FetchConnector> connector = connectorRegistry.findFetch(connectorId);
            if (connector.isEmpty()) {
                return ResponseEntity.ok(new ConnectionHealthResponse()
                        .oauthConnected(true).configured(true).siteUrl(siteUrl)
                        .status(ConnectorHealth.DEGRADED.name())
                        .errorMessage("Connector does not support health checks"));
            }
            try {
                ConnectorHealth health = connector.get().checkHealth(refreshed);
                status = health.name();
                propertyAccessible = health == ConnectorHealth.HEALTHY;
                if (health == ConnectorHealth.SETUP_REQUIRED) {
                    errorMessage = "Property \"" + siteUrl
                            + "\" not accessible — check URL format and verify ownership in Search Console";
                } else if (health == ConnectorHealth.DEGRADED) {
                    errorMessage = "Could not verify property access — try again or re-connect";
                }
            } catch (Exception e) {
                status = ConnectorHealth.DEGRADED.name();
                errorMessage = e.getMessage();
            }
        }

        return ResponseEntity.ok(new ConnectionHealthResponse()
                .oauthConnected(oauthConnected)
                .configured(configured)
                .siteUrl(siteUrl)
                .propertyAccessible(propertyAccessible)
                .status(status)
                .errorMessage(errorMessage));
    }

    private GcpBillingConnector requireGcpBillingConnector() {
        return gcpBillingConnector.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "GCP Billing connector is not available"));
    }

    private GscConnector requireGscConnector() {
        return gscConnector.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Search Console connector is not available"));
    }

    /** Resolves a usable GCP OAuth access token for the project's single gcp-billing connection,
     *  refreshing it if expiring. Mirrors the former GcpBillingController behavior. */
    private String requireGcpAccessToken(String projectId, String connectorId) {
        Connection conn = connectionService.findSingle(projectId, connectorId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "No OAuth credentials stored — complete OAuth first"));
        DecryptedCredentials creds = connectionService.decrypt(conn);
        if (creds.accessToken() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No OAuth credentials stored — complete OAuth first");
        }
        if (creds.expiresAt() != null && creds.expiresAt().isBefore(Instant.now().plusSeconds(60))) {
            return oAuthFlowService.refreshAccessToken(conn, creds.refreshToken());
        }
        return creds.accessToken();
    }

    // ---- helpers ----

    private Connector requireConnector(String connectorId) {
        return connectorRegistry.getById(connectorId)
                .orElseThrow(() -> new EntityNotFoundException("Connector not found: " + connectorId));
    }

    private Connection requireConnection(String projectId, String connectorId, String connectionId) {
        Connection conn = connectionService.getById(connectionId, connectorId)
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + connectionId));
        if (!conn.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found in project");
        }
        return conn;
    }

    /** SERVICE_ACCOUNT connectors (GCP) require a well-formed GCP service-account JSON key. */
    private void requireValidServiceAccountKey(String key) {
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(key, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException("Invalid service-account key: not valid JSON");
        }
        if (!"service_account".equals(parsed.get("type"))) {
            throw new BusinessException("Invalid service-account key: expected \"type\": \"service_account\"");
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private List<ConnectorConfigFieldDto> toConfigFieldDtos(ConnectorSpec spec) {
        return spec.fields().stream()
                .map(f -> new ConnectorConfigFieldDto()
                        .key(f.key())
                        .label(f.label())
                        .hint(f.hint())
                        .type(f.type().name())
                        .source(f.source().name())
                        .required(f.required())
                        .secret(f.secret()))
                .toList();
    }

    private List<ConnectorCatalogConfigFieldDto> toCatalogConfigFieldDtos(ConnectorSpec spec) {
        return spec.fields().stream()
                .map(f -> new ConnectorCatalogConfigFieldDto()
                        .name(f.key())
                        .label(f.label())
                        .type(f.type().name())
                        .required(f.required())
                        .secret(f.secret()))
                .toList();
    }

    private ConnectionSummary toConnectionSummary(Connection conn) {
        ConnectionSummary summary = new ConnectionSummary()
                .id(conn.getId())
                .label(conn.getDisplayLabel())
                .status(conn.getStatus())
                .authType(conn.getAuthType())
                .tokenExpiresAt(conn.getTokenExpiresAt());
        cacheRepository.findByConnectionId(conn.getId()).ifPresent(cache -> {
            summary.setHealthStatus(cache.getHealthStatus());
            summary.setFetchedAt(cache.getFetchedAt());
        });
        return summary;
    }

    private ConnectionResponse toConnectionResponse(Connection conn, String generatedSecret) {
        ConnectionResponse resp = new ConnectionResponse()
                .id(conn.getId())
                .connectorId(conn.getConnectorId())
                .label(conn.getDisplayLabel())
                .status(conn.getStatus())
                .authType(conn.getAuthType())
                .connectedAt(conn.getCreatedAt());
        if (AuthType.WEBHOOK.name().equals(conn.getAuthType())) {
            resp.setWebhookUrl(webhookUrl(conn.getConnectorId(), conn.getId()));
        }
        // The signing secret is returned exactly once, at creation.
        resp.setWebhookSecret(generatedSecret);
        return resp;
    }

    private String webhookUrl(String connectorId, String connectionId) {
        String base = backendUrl != null ? backendUrl : "";
        return base + "/api/v1/webhooks/" + connectorId + "/" + connectionId;
    }

    private WebhookEventSummary toWebhookEventSummary(WebhookEvent e) {
        return new WebhookEventSummary()
                .id(e.getId())
                .deliveryId(e.getDeliveryId())
                .eventType(e.getEventType())
                .status(WebhookEventSummary.StatusEnum.fromValue(e.getStatus().name()))
                .attempts(e.getAttempts())
                .errorMessage(e.getErrorMessage())
                .receivedAt(e.getReceivedAt());
    }

    private ConnectionDataResponse connectorDataToResponse(String connectorId, String connectionId, ConnectorData data) {
        OffsetDateTime fetchedAt = data.fetchedAt() != null
                ? OffsetDateTime.ofInstant(data.fetchedAt(), ZoneOffset.UTC) : null;
        return new ConnectionDataResponse()
                .connectionId(connectionId)
                .connectorId(connectorId)
                .data(data.data())
                .healthStatus(data.healthStatus().name())
                .fetchedAt(fetchedAt)
                .isStale(isStale(connectorId, fetchedAt))
                .errorMessage(data.errorMessage());
    }

    private Boolean isStale(String connectorId, OffsetDateTime fetchedAt) {
        if (fetchedAt == null) {
            return null;
        }
        java.time.Duration maxCacheAge = connectorRegistry.findFetch(connectorId)
                .map(FetchConnector::getMaxCacheAge)
                .orElse(java.time.Duration.ofHours(1));
        return OffsetDateTime.now().isAfter(fetchedAt.plus(maxCacheAge));
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Member-level gate: accepts either a {@link User} principal or a project-scoped machine
     * principal ({@link ProjectScopedPrincipal} -- a project API key or a run-scoped MCP token)
     * whose {@code projectId} matches the requested project -- mirroring
     * {@code KnowledgeController#requireProjectAccess}.
     */
    private void requireMember(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (principal instanceof User user) {
            if (!projectSecurityService.isProjectMember(projectId, user.getId())) {
                throw new AccessDeniedException("Not a member of this project");
            }
            return;
        }
        if (auth instanceof ProjectScopedPrincipal scoped && projectId.equals(scoped.getProjectId())) {
            return;
        }
        throw new AccessDeniedException("Not a member of this project");
    }

    /**
     * Admin/creator-level gate: only a real {@link User} principal can hold a project role, so
     * project-scoped machine principals are rejected with a clean 403 here -- mirroring
     * {@code KnowledgeController#requireProjectAdmin}. {@link #currentUser()} is safe to call after
     * this gate passes, since it guarantees the principal is a {@link User}.
     */
    private void requireAdminOrCreator(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user) || !projectSecurityService.isAdminOrCreator(projectId, user.getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
