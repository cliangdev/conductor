package com.conductor.controller;

import com.conductor.entity.Connection;
import com.conductor.entity.ConnectionDataCache;
import com.conductor.entity.User;
import com.conductor.entity.WebhookEvent;
import com.conductor.generated.api.IntegrationsApi;
import com.conductor.generated.model.BqDatasetsResponse;
import com.conductor.generated.model.BqDatasetsResponseDatasetsInner;
import com.conductor.generated.model.ConnectionDataResponse;
import com.conductor.generated.model.ConnectionResponse;
import com.conductor.generated.model.ConnectionSummary;
import com.conductor.generated.model.ConnectorConfigFieldDto;
import com.conductor.generated.model.CreateConnectionRequest;
import com.conductor.generated.model.GcpProjectsResponse;
import com.conductor.generated.model.GcpProjectsResponseProjectsInner;
import com.conductor.generated.model.IntegrationListItem;
import com.conductor.generated.model.OAuthAuthorizeResponse;
import com.conductor.generated.model.UpdateConnectionRequest;
import com.conductor.generated.model.WebhookEventSummary;
import com.conductor.integration.AuthType;
import com.conductor.integration.Capability;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
                                ObjectMapper objectMapper) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.fetchService = fetchService;
        this.oAuthFlowService = oAuthFlowService;
        this.cacheRepository = cacheRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.projectSecurityService = projectSecurityService;
        this.gcpBillingConnector = gcpBillingConnector;
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

    private GcpBillingConnector requireGcpBillingConnector() {
        return gcpBillingConnector.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "GCP Billing connector is not available"));
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

    private ConnectionSummary toConnectionSummary(Connection conn) {
        ConnectionSummary summary = new ConnectionSummary()
                .id(conn.getId())
                .label(conn.getDisplayLabel())
                .status(conn.getStatus());
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

    private void requireMember(String projectId) {
        if (!projectSecurityService.isProjectMember(projectId, currentUser().getId())) {
            throw new AccessDeniedException("Not a member of this project");
        }
    }

    private void requireAdminOrCreator(String projectId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, currentUser().getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
