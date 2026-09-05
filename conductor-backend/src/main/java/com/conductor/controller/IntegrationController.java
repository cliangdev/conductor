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
import com.conductor.generated.model.ConnectorAppCredentialStatusDto;
import com.conductor.generated.model.ConnectorAppCredentialVerificationReport;
import com.conductor.generated.model.ConnectorCatalogConfigFieldDto;
import com.conductor.generated.model.ConnectorCatalogEntryDto;
import com.conductor.generated.model.ConnectorCatalogIngestDto;
import com.conductor.generated.model.ConnectorConfigFieldDto;
import com.conductor.generated.model.ConnectorFeedDto;
import com.conductor.generated.model.CreateConnectionRequest;
import com.conductor.generated.model.GcpProjectsResponse;
import com.conductor.generated.model.GcpProjectsResponseProjectsInner;
import com.conductor.generated.model.GscSitesResponse;
import com.conductor.generated.model.GscSitesResponseSitesInner;
import com.conductor.generated.model.IntegrationListItem;
import com.conductor.generated.model.IntegrationToolItem;
import com.conductor.generated.model.OAuthAccountDto;
import com.conductor.generated.model.OAuthAccountsResponse;
import com.conductor.generated.model.OAuthAuthorizeResponse;
import com.conductor.generated.model.SelectOAuthAccountRequest;
import com.conductor.generated.model.SetConnectorAppCredentialRequest;
import com.conductor.generated.model.UpdateConnectionRequest;
import com.conductor.generated.model.UpdateConnectorFeedRequest;
import com.conductor.generated.model.VerificationCheck;
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
import com.conductor.integration.IngestSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.integration.connector.gsc.GscConnector;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.repository.ConnectionDataCacheRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionHealthService;
import com.conductor.service.ConnectionService;
import com.conductor.service.ConnectorAppCredentialService;
import com.conductor.service.ConnectorAppCredentialService.AppCredentialStatus;
import com.conductor.service.ConnectorAppCredentialVerificationService;
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
    private final ConnectorFeedRepository connectorFeedRepository;
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
    private final ConnectorAppCredentialService appCredentialService;
    private final ConnectorAppCredentialVerificationService appCredentialVerificationService;

    @Value("${BACKEND_URL:}")
    private String backendUrl;

    public IntegrationController(ConnectorRegistry connectorRegistry,
                                ConnectionService connectionService,
                                IntegrationFetchService fetchService,
                                OAuthFlowService oAuthFlowService,
                                ConnectionDataCacheRepository cacheRepository,
                                WebhookEventRepository webhookEventRepository,
                                ProjectSecurityService projectSecurityService,
                                ConnectorFeedRepository connectorFeedRepository,
                                Optional<GcpBillingConnector> gcpBillingConnector,
                                Optional<GscConnector> gscConnector,
                                RuntimeTargetService runtimeTargetService,
                                ConnectorAppCredentialService appCredentialService,
                                ConnectorAppCredentialVerificationService appCredentialVerificationService,
                                ObjectMapper objectMapper) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.fetchService = fetchService;
        this.oAuthFlowService = oAuthFlowService;
        this.cacheRepository = cacheRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.projectSecurityService = projectSecurityService;
        this.connectorFeedRepository = connectorFeedRepository;
        this.gcpBillingConnector = gcpBillingConnector;
        this.gscConnector = gscConnector;
        this.runtimeTargetService = runtimeTargetService;
        this.appCredentialService = appCredentialService;
        this.appCredentialVerificationService = appCredentialVerificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<IntegrationListItem>> listIntegrations(String projectId) {
        requireMember(projectId);
        List<Connector> registered = connectorRegistry.getAll();
        // The hub view the Integrations UI reads, so readiness has to be here and not only on the
        // catalog: without it the browse grid offers "Authorize" for a connector whose consent flow
        // cannot start, and the failure surfaces as an exception naming an environment variable.
        Map<String, ConnectorAppCredentialStatusDto> appCredentials = appCredentialStatuses(projectId, registered);
        List<IntegrationListItem> items = new ArrayList<>();
        for (Connector connector : registered) {
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
                    .connections(connections.stream().map(this::toConnectionSummary).toList())
                    // Null for a non-OAuth2 connector -- it has no app credential to configure.
                    .appCredential(appCredentials.get(connector.getId()));
            items.add(item);
        }
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<List<ConnectorCatalogEntryDto>> listConnectorCatalog(String projectId) {
        requireMember(projectId);
        List<Connector> registered = connectorRegistry.getAll();
        Map<String, ConnectorAppCredentialStatusDto> appCredentials = appCredentialStatuses(projectId, registered);
        List<ConnectorCatalogEntryDto> items = new ArrayList<>();
        for (Connector connector : registered) {
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
                    .activeConnectionIds(activeConnectionIds)
                    .ingest(toCatalogIngestDtos(connector))
                    // Null for a non-OAuth2 connector -- it has no app credential to configure.
                    .appCredential(appCredentials.get(connector.getId())));
        }
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<ConnectorAppCredentialStatusDto> getConnectorAppCredential(
            String projectId, String connectorId) {
        requireMember(projectId);
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        return ResponseEntity.ok(toAppCredentialDto(appCredentialService.status(projectId, connector)));
    }

    /**
     * The ADMIN rule itself belongs to {@link ConnectorAppCredentialService#put} -- it owns why these
     * credentials are admin-only -- so this deliberately does not re-check the role. All the controller
     * adds is that the caller is a real {@link User}: a project-scoped machine principal holds no role
     * at all, and would otherwise reach the service as a null caller.
     */
    @Override
    public ResponseEntity<ConnectorAppCredentialStatusDto> setConnectorAppCredential(
            String projectId, String connectorId, SetConnectorAppCredentialRequest request) {
        requireMember(projectId);
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        appCredentialService.put(projectId, connectorId, request.getClientId(), request.getClientSecret(),
                requireUserPrincipal());
        return ResponseEntity.ok(toAppCredentialDto(appCredentialService.status(projectId, connector)));
    }

    @Override
    public ResponseEntity<ConnectorAppCredentialStatusDto> clearConnectorAppCredential(
            String projectId, String connectorId) {
        requireMember(projectId);
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        appCredentialService.clear(projectId, connectorId, requireUserPrincipal());
        return ResponseEntity.ok(toAppCredentialDto(appCredentialService.status(projectId, connector)));
    }

    /**
     * Always 200, even when the probe failed: the report's own {@code status} carries the outcome
     * (including {@code unknown}, meaning the probe could not tell), mirroring
     * {@code verifyProviderCredential}.
     */
    @Override
    public ResponseEntity<ConnectorAppCredentialVerificationReport> verifyConnectorAppCredential(
            String projectId, String connectorId) {
        requireAdminOrCreator(projectId);
        OAuth2Connector connector = requireOAuth2Connector(connectorId);
        return ResponseEntity.ok(toVerificationReport(
                appCredentialVerificationService.verify(projectId, connector)));
    }

    @Override
    public ResponseEntity<List<ConnectorFeedDto>> listConnectorFeeds(String projectId, String connectorId) {
        requireMember(projectId);
        Map<String, IngestSpec> specsById = ingestSpecsById(connectorId);
        List<ConnectorFeedDto> feeds = connectorFeedRepository.findByProjectIdAndConnectorId(projectId, connectorId)
                .stream()
                .map(feed -> toFeedDto(feed, specsById.get(feed.getIngestId())))
                .toList();
        return ResponseEntity.ok(feeds);
    }

    @Override
    public ResponseEntity<ConnectorFeedDto> updateConnectorFeed(
            String projectId, String connectorId, String feedId, UpdateConnectorFeedRequest request) {
        requireAdminOrCreator(projectId);
        ConnectorFeed feed = requireFeed(projectId, connectorId, feedId);
        if (request.getEnabled() != null) {
            feed.setEnabled(request.getEnabled());
            if (Boolean.TRUE.equals(request.getEnabled())) {
                resumeFeed(feed);
            }
        }
        if (request.getIntervalMinutes() != null) {
            feed.setIntervalMinutes(request.getIntervalMinutes());
        }
        connectorFeedRepository.save(feed);
        return ResponseEntity.ok(toFeedDto(feed, ingestSpecsById(connectorId).get(feed.getIngestId())));
    }

    @Override
    public ResponseEntity<ConnectorFeedDto> runConnectorFeedNow(String projectId, String connectorId, String feedId) {
        requireAdminOrCreator(projectId);
        ConnectorFeed feed = requireFeed(projectId, connectorId, feedId);
        // "Sync now" only re-dues the feed for the existing scheduler to pick up -- it must never run
        // the pull inline and block this request on an outbound HTTP call to the third party.
        resumeFeed(feed);
        feed.setNextRunAt(OffsetDateTime.now());
        connectorFeedRepository.save(feed);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(toFeedDto(feed, ingestSpecsById(connectorId).get(feed.getIngestId())));
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

        // Connector-specific post-creation setup (see Connector#onConnectionCreated's javadoc) -- a
        // no-op for every connector except the few that override it. A throw here means the connection
        // can never actually work as created (e.g. a vendor-side command registration that failed), so
        // the framework deletes the row rather than leaving a connection that looks connected but isn't.
        try {
            connector.onConnectionCreated(conn, connectionService.toContext(conn));
        } catch (Exception e) {
            connectionService.delete(conn.getId());
            throw new BusinessException("Connection setup failed: " + e.getMessage());
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
    public ResponseEntity<OAuthAccountsResponse> listOAuthAccounts(
            String projectId, String connectorId, String connectionId) {
        requireAdminOrCreator(projectId);
        Connection conn = requireConnection(projectId, connectorId, connectionId);
        OAuthAccountsResponse response = new OAuthAccountsResponse();
        oAuthFlowService.listAuthorizableAccounts(conn).forEach(account -> response.addAccountsItem(
                new OAuthAccountDto().id(account.id()).label(account.label())));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ConnectionResponse> selectOAuthAccount(
            String projectId, String connectorId, String connectionId, SelectOAuthAccountRequest request) {
        requireAdminOrCreator(projectId);
        Connection conn = requireConnection(projectId, connectorId, connectionId);
        String accountId = request != null ? request.getAccountId() : null;
        if (accountId == null || accountId.isBlank()) {
            throw new BusinessException("accountId is required");
        }
        Connection completed = oAuthFlowService.completeAccountSelection(conn, accountId);
        return ResponseEntity.ok(toConnectionResponse(completed, null));
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

    private ConnectorFeed requireFeed(String projectId, String connectorId, String feedId) {
        ConnectorFeed feed = connectorFeedRepository.findById(feedId)
                .orElseThrow(() -> new EntityNotFoundException("Feed not found: " + feedId));
        if (!feed.getProjectId().equals(projectId) || !feed.getConnectorId().equals(connectorId)) {
            // EntityNotFoundException for consistency with the rest of this controller's not-found paths.
            // (A ResponseStatusException would also render correctly now that GlobalExceptionHandler
            // handles ErrorResponseException — it used to fall through to the catch-all and render 500.)
            throw new EntityNotFoundException("Feed not found in project/connector: " + feedId);
        }
        return feed;
    }

    /**
     * Clears the scheduler-owned failure state so an operator explicitly resuming a feed actually gets
     * it picked up again. {@code ConnectorFeedRepository#claimDue} requires {@code status = 'ACTIVE'
     * AND enabled = true}, so without this a PATCH of {@code enabled: true} on a DEAD, PAUSED or
     * SETUP_REQUIRED feed -- or a "sync now" on one -- returns 200/202 and then silently never runs,
     * contradicting {@code ConnectorFeedStatus}'s own contract that a DEAD feed resumes once re-enabled.
     *
     * <p>Resetting {@code consecutiveFailures} matters as much as the status: leaving it at the
     * dead-letter threshold would send the feed straight back to DEAD on its first hiccup instead of
     * giving the operator a fresh budget. SETUP_REQUIRED is reset too -- if the underlying connection
     * is still unauthenticated the next pull re-marks it within one tick, which is honest feedback
     * rather than a permanently un-retryable row.
     */
    private void resumeFeed(ConnectorFeed feed) {
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setConsecutiveFailures(0);
        feed.setLastError(null);
    }

    /** The connector's currently-declared ingest feeds, keyed by ingest id -- used to enrich a
     *  {@code connector_feed} row with the label/description/isMetricFeed that live in the connector's
     *  own tool-spec JSON rather than being duplicated onto the row itself. */
    private Map<String, IngestSpec> ingestSpecsById(String connectorId) {
        return connectorRegistry.getById(connectorId)
                .map(connector -> connector.getToolSpec().ingest().stream()
                        .collect(java.util.stream.Collectors.toMap(IngestSpec::id, s -> s)))
                .orElseGet(Map::of);
    }

    /**
     * One credential query for a whole connector list: {@link ConnectorAppCredentialService#statuses}
     * loads the project's rows in a single query and resolves the rest from the deployment env, so the
     * cost stays flat as OAuth2 connectors are added. Calling {@code status()} per connector here
     * would reintroduce the N+1 that method exists to avoid.
     *
     * <p>Shared by {@link #listIntegrations} and {@link #listConnectorCatalog} deliberately: the two
     * endpoints list the same connectors and must never disagree about whether one is ready.
     */
    private Map<String, ConnectorAppCredentialStatusDto> appCredentialStatuses(
            String projectId, List<Connector> connectors) {
        List<OAuth2Connector> oauthConnectors = connectors.stream()
                .filter(OAuth2Connector.class::isInstance)
                .map(OAuth2Connector.class::cast)
                .toList();
        if (oauthConnectors.isEmpty()) {
            return Map.of();
        }
        Map<String, ConnectorAppCredentialStatusDto> byConnectorId = new java.util.LinkedHashMap<>();
        for (AppCredentialStatus status : appCredentialService.statuses(projectId, oauthConnectors)) {
            byConnectorId.put(status.connectorId(), toAppCredentialDto(status));
        }
        return byConnectorId;
    }

    /** Masked throughout -- {@code clientSecretLast4} is the only secret-derived value that leaves here. */
    private static ConnectorAppCredentialStatusDto toAppCredentialDto(AppCredentialStatus status) {
        return new ConnectorAppCredentialStatusDto()
                .connectorId(status.connectorId())
                .credentialSource(ConnectorAppCredentialStatusDto.CredentialSourceEnum
                        .fromValue(status.source().name()))
                .configured(status.configured())
                .clientId(status.clientId())
                .clientSecretLast4(status.clientSecretLast4())
                .missingProperties(status.missingProperties())
                .allowsDeploymentCredentials(status.allowsDeploymentCredentials())
                .updatedBy(status.updatedBy())
                .updatedAt(status.updatedAt());
    }

    private static ConnectorAppCredentialVerificationReport toVerificationReport(
            ConnectorAppCredentialVerificationService.VerificationReport report) {
        return new ConnectorAppCredentialVerificationReport()
                .connectorId(report.connectorId())
                .status(ConnectorAppCredentialVerificationReport.StatusEnum.fromValue(report.status().value()))
                .checkedAt(report.checkedAt())
                .checks(report.checks().stream()
                        .map(c -> new VerificationCheck()
                                .name(c.name())
                                .status(VerificationCheck.StatusEnum.fromValue(c.status().value()))
                                .message(c.message()))
                        .toList());
    }

    /**
     * 404 rather than a 500 mid-flow for a connector id that is unknown, or known but not OAuth2 --
     * {@link ConnectorAppCredentialService} has no registry and cannot make that distinction itself.
     */
    private OAuth2Connector requireOAuth2Connector(String connectorId) {
        return connectorRegistry.findOAuth2(connectorId).orElseThrow(() -> new EntityNotFoundException(
                "No OAuth2 connector with id '" + connectorId + "'"));
    }

    /** Only a real {@link User} can hold a project role, so a machine principal is refused here. */
    private User requireUserPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user)) {
            throw new AccessDeniedException("Requires a signed-in project ADMIN");
        }
        return user;
    }

    private List<ConnectorCatalogIngestDto> toCatalogIngestDtos(Connector connector) {
        return connector.getToolSpec().ingest().stream()
                .map(spec -> new ConnectorCatalogIngestDto()
                        .id(spec.id())
                        .label(spec.label())
                        .description(spec.description())
                        .isMetricFeed(spec.isMetricFeed()))
                .toList();
    }

    /** {@code spec} is null when the connector's tool-spec JSON dropped or renamed this ingest id
     *  after the {@code connector_feed} row was provisioned -- falls back to the raw ingest id rather
     *  than failing the whole list. */
    private ConnectorFeedDto toFeedDto(ConnectorFeed feed, IngestSpec spec) {
        return new ConnectorFeedDto()
                .id(feed.getId())
                .ingestId(feed.getIngestId())
                .label(spec != null ? spec.label() : feed.getIngestId())
                .description(spec != null ? spec.description() : null)
                .enabled(feed.isEnabled())
                .intervalMinutes(feed.getIntervalMinutes())
                .status(ConnectorFeedDto.StatusEnum.fromValue(feed.getStatus().name()))
                .lastRunAt(feed.getLastRunAt())
                .lastSuccessAt(feed.getLastSuccessAt())
                .lastError(feed.getLastError())
                .consecutiveFailures(feed.getConsecutiveFailures())
                .nextRunAt(feed.getNextRunAt())
                .isMetricFeed(spec != null && spec.isMetricFeed());
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
        // The cache grades the last data *fetch*; the connection carries its own health (can the
        // platform still be reached with these credentials at all). UNHEALTHY is the more actionable
        // of the two, so it wins; otherwise the connection's health only fills a gap the cache left.
        if (ConnectionHealthService.UNHEALTHY.equals(conn.getHealthStatus())
                || summary.getHealthStatus() == null) {
            summary.setHealthStatus(conn.getHealthStatus());
        }
        summary.setHealthCheckedAt(conn.getHealthCheckedAt());
        summary.setHealthMessage(conn.getHealthMessage());
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
     * whose {@code projectId} matches the requested project. The rule itself lives in
     * {@link ProjectSecurityService#requireProjectAccess}, shared with every other project-scoped
     * controller.
     */
    private void requireMember(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
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
