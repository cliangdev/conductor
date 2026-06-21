package com.conductor.controller;

import com.conductor.entity.IntegrationDataCache;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.generated.api.IntegrationsApi;
import com.conductor.generated.model.ConnectIntegrationRequest;
import com.conductor.generated.model.ConnectorConfigFieldDto;
import com.conductor.generated.model.IntegrationDataResponse;
import com.conductor.generated.model.IntegrationListItem;
import com.conductor.generated.model.IntegrationStatusResponse;
import com.conductor.generated.model.OAuthAuthorizeResponse;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.IntegrationConnector;
import com.conductor.repository.IntegrationCredentialRepository;
import com.conductor.repository.IntegrationDataCacheRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.service.CredentialService;
import com.conductor.service.IntegrationFetchService;
import com.conductor.service.OAuthFlowService;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class IntegrationController implements IntegrationsApi {

    private final ConnectorRegistry connectorRegistry;
    private final IntegrationFetchService fetchService;
    private final CredentialService credentialService;
    private final OAuthFlowService oAuthFlowService;
    private final IntegrationCredentialRepository credentialRepository;
    private final IntegrationDataCacheRepository cacheRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ObjectMapper objectMapper;

    public IntegrationController(ConnectorRegistry connectorRegistry,
                                IntegrationFetchService fetchService,
                                CredentialService credentialService,
                                OAuthFlowService oAuthFlowService,
                                IntegrationCredentialRepository credentialRepository,
                                IntegrationDataCacheRepository cacheRepository,
                                ProjectMemberRepository projectMemberRepository,
                                ObjectMapper objectMapper) {
        this.connectorRegistry = connectorRegistry;
        this.fetchService = fetchService;
        this.credentialService = credentialService;
        this.oAuthFlowService = oAuthFlowService;
        this.credentialRepository = credentialRepository;
        this.cacheRepository = cacheRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<IntegrationListItem>> listIntegrations(String projectId) {
        List<IntegrationListItem> items = new ArrayList<>();
        for (IntegrationConnector connector : connectorRegistry.getAll()) {
            ConnectorMetadata meta = connector.getMetadata();
            IntegrationListItem item = new IntegrationListItem()
                    .connectorId(connector.getId())
                    .name(meta.name())
                    .category(meta.category().name())
                    .authType(meta.authType().name())
                    .description(meta.description())
                    .iconLabel(meta.iconLabel())
                    .connected(false)
                    .configFields(toConfigFieldDtos(connector));

            boolean connected = credentialRepository
                    .findByProjectIdAndConnectorId(projectId, connector.getId()).isPresent();
            item.setConnected(connected);

            if (connected) {
                cacheRepository.findByProjectIdAndConnectorId(projectId, connector.getId())
                        .ifPresent(cache -> {
                            item.setHealthStatus(cache.getHealthStatus());
                            item.setFetchedAt(cache.getFetchedAt());
                        });
            }
            items.add(item);
        }
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<IntegrationStatusResponse> connectIntegration(
            String projectId, String connectorId, ConnectIntegrationRequest request) {
        requireAdminOrCreator(projectId);
        requireConnector(connectorId);
        credentialService.storeCredentials(projectId, connectorId, AuthType.API_KEY,
                request.getApiKey(), null, null, request.getConfigJson());
        return ResponseEntity.status(HttpStatus.CREATED).body(statusResponse(connectorId));
    }

    @Override
    public ResponseEntity<IntegrationStatusResponse> updateIntegrationCredentials(
            String projectId, String connectorId, ConnectIntegrationRequest request) {
        requireAdminOrCreator(projectId);
        requireConnector(connectorId);
        credentialService.storeCredentials(projectId, connectorId, AuthType.API_KEY,
                request.getApiKey(), null, null, request.getConfigJson());
        return ResponseEntity.ok(statusResponse(connectorId));
    }

    @Override
    public ResponseEntity<Void> disconnectIntegration(String projectId, String connectorId) {
        requireAdminOrCreator(projectId);
        credentialService.deleteCredentials(projectId, connectorId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<IntegrationDataResponse> getIntegrationData(String projectId, String connectorId) {
        requireMember(projectId);
        Optional<IntegrationDataCache> cached =
                cacheRepository.findByProjectIdAndConnectorId(projectId, connectorId);
        if (cached.isEmpty()) {
            return ResponseEntity.ok(new IntegrationDataResponse().connectorId(connectorId));
        }
        return ResponseEntity.ok(cacheToResponse(connectorId, cached.get()));
    }

    @Override
    public ResponseEntity<IntegrationDataResponse> fetchIntegrationData(String projectId, String connectorId) {
        requireMember(projectId);
        ConnectorData data = fetchService.fetchData(projectId, connectorId, false);
        return ResponseEntity.ok(connectorDataToResponse(connectorId, data));
    }

    @Override
    public ResponseEntity<OAuthAuthorizeResponse> authorizeOAuth(String projectId, String connectorId) {
        requireAdminOrCreator(projectId);
        requireConnector(connectorId);
        String authUrl = oAuthFlowService.buildAuthorizationUrl(
                projectId, connectorId, fixedOauthCallbackUri());
        return ResponseEntity.ok(new OAuthAuthorizeResponse().authorizationUrl(authUrl));
    }

    @Override
    public ResponseEntity<Void> handleOAuthCallback(String code, String state) {
        String frontendUrl = oAuthFlowService.handleCallback(code, state, fixedOauthCallbackUri());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendUrl))
                .build();
    }

    private String fixedOauthCallbackUri() {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String base = UriComponentsBuilder.fromUriString(req.getRequestURL().toString())
                .replacePath(null).replaceQuery(null).build().toUriString();
        return base + "/api/v1/oauth/callback";
    }

    private IntegrationConnector requireConnector(String connectorId) {
        return connectorRegistry.getById(connectorId)
                .orElseThrow(() -> new EntityNotFoundException("Connector not found: " + connectorId));
    }

    private List<ConnectorConfigFieldDto> toConfigFieldDtos(IntegrationConnector connector) {
        return connector.getConfigFields().stream()
                .map(f -> new ConnectorConfigFieldDto()
                        .fieldKey(f.fieldKey())
                        .label(f.label())
                        .hint(f.hint())
                        .secret(f.secret()))
                .toList();
    }

    private IntegrationStatusResponse statusResponse(String connectorId) {
        return new IntegrationStatusResponse()
                .connectorId(connectorId)
                .connected(true)
                .healthStatus("HEALTHY");
    }

    private IntegrationDataResponse cacheToResponse(String connectorId, IntegrationDataCache cache) {
        ConnectorData data = new ConnectorData(parseJson(cache.getDataJson()),
                ConnectorHealth.valueOf(cache.getHealthStatus()),
                cache.getFetchedAt().toInstant(), null);
        return connectorDataToResponse(connectorId, data);
    }

    private java.util.Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private IntegrationDataResponse connectorDataToResponse(String connectorId, ConnectorData data) {
        OffsetDateTime fetchedAt = data.fetchedAt() != null
                ? OffsetDateTime.ofInstant(data.fetchedAt(), java.time.ZoneOffset.UTC)
                : null;
        return new IntegrationDataResponse()
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
        Duration maxCacheAge = connectorRegistry.getById(connectorId)
                .map(IntegrationConnector::getMaxCacheAge)
                .orElse(Duration.ofHours(1));
        return OffsetDateTime.now().isAfter(fetchedAt.plus(maxCacheAge));
    }

    private void requireMember(String projectId) {
        member(projectId);
    }

    private void requireAdminOrCreator(String projectId) {
        ProjectMember member = member(projectId);
        if (member.getRole() != MemberRole.ADMIN && member.getRole() != MemberRole.CREATOR) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }

    private ProjectMember member(String projectId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser().getId())
                .orElseThrow(() -> new AccessDeniedException("Not a member of this project"));
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
