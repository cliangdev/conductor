package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Health tracking for a connection, end to end against a real Postgres so the V114 columns, the
 * entity mapping, and the service that writes them are all proven together.
 *
 * <p>Connection health is deliberately NOT connection status: every assertion here also checks that
 * an unhealthy connection is still {@code ACTIVE} and still present.
 */
@Transactional
class ConnectionHealthServiceTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private ConnectionHealthService connectionHealthService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private String projectId;

    @BeforeEach
    void setUp() {
        User creator = new User();
        creator.setFirebaseUid("health-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Health Creator");
        creator = userRepository.save(creator);

        Project project = new Project();
        project.setName("Connection Health Test Project");
        project.setKey("CH" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        projectId = projectRepository.save(project).getId();
    }

    private Connection persistedConnection() {
        Connection conn = new Connection();
        conn.setProjectId(projectId);
        conn.setConnectorId("acme");
        conn.setAuthType("OAUTH2");
        conn.setStatus("ACTIVE");
        return connectionRepository.saveAndFlush(conn);
    }

    private Connection reload(String connectionId) {
        entityManager.flush();
        entityManager.clear();
        return connectionRepository.findById(connectionId).orElseThrow();
    }

    // ---- [auto] V114 applies and the three health columns round-trip on the Connection entity ----

    @Test
    void healthColumnsRoundTripOnTheConnectionEntity() {
        Connection conn = persistedConnection();
        OffsetDateTime checkedAt = OffsetDateTime.now().minusMinutes(3);

        conn.setHealthStatus("UNHEALTHY");
        conn.setHealthCheckedAt(checkedAt);
        conn.setHealthMessage("Token has been expired or revoked.");
        connectionRepository.saveAndFlush(conn);

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("UNHEALTHY");
        assertThat(reloaded.getHealthCheckedAt().toInstant())
                .isCloseTo(checkedAt.toInstant(), within(1, ChronoUnit.SECONDS));
        assertThat(reloaded.getHealthMessage()).isEqualTo("Token has been expired or revoked.");
    }

    @Test
    void healthColumnsDefaultToNullOnAFreshConnection() {
        Connection reloaded = reload(persistedConnection().getId());

        assertThat(reloaded.getHealthStatus()).isNull();
        assertThat(reloaded.getHealthCheckedAt()).isNull();
        assertThat(reloaded.getHealthMessage()).isNull();
    }

    // ---- markUnhealthy / markHealthy ----

    @Test
    void markUnhealthyRecordsTheReasonAndTheTimeButLeavesTheConnectionActive() {
        Connection conn = persistedConnection();

        connectionHealthService.markUnhealthy(conn.getId(), "Token has been expired or revoked.");

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("UNHEALTHY");
        assertThat(reloaded.getHealthMessage()).isEqualTo("Token has been expired or revoked.");
        assertThat(reloaded.getHealthCheckedAt()).isNotNull();
        // The invariant: health is not status. An unhealthy connection is still connected.
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
        assertThat(connectionRepository.findById(conn.getId())).isPresent();
    }

    @Test
    void markHealthyClearsAPreviouslyUnhealthyConnection() {
        Connection conn = persistedConnection();
        connectionHealthService.markUnhealthy(conn.getId(), "Token has been expired or revoked.");

        connectionHealthService.markHealthy(conn.getId());

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("HEALTHY");
        assertThat(reloaded.getHealthMessage()).isNull();
        assertThat(reloaded.getHealthCheckedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void markUnhealthyTruncatesAnOverlongProviderMessage() {
        Connection conn = persistedConnection();

        connectionHealthService.markUnhealthy(conn.getId(), "x".repeat(5_000));

        assertThat(reload(conn.getId()).getHealthMessage())
                .hasSizeLessThanOrEqualTo(ConnectionHealthService.MAX_MESSAGE_LENGTH);
    }

    @Test
    void markUnhealthyWithNoReasonStillRecordsAReadableFallback() {
        Connection conn = persistedConnection();

        connectionHealthService.markUnhealthy(conn.getId(), "   ");

        assertThat(reload(conn.getId()).getHealthMessage()).isNotBlank();
    }

    @Test
    void markingAnUnknownConnectionIsANoOpRatherThanAFailure() {
        // Health tracking is a side-channel: it must never take down the caller that reported it.
        connectionHealthService.markUnhealthy("no-such-connection", "gone");
        connectionHealthService.markHealthy("no-such-connection");
    }

    @Test
    void reportPublishAuthFailureIsTheEntryPointForThePublishPath() {
        Connection conn = persistedConnection();

        connectionHealthService.reportPublishAuthFailure(conn.getId(),
                "(#200) Requires instagram_content_publish permission");

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("UNHEALTHY");
        assertThat(reloaded.getHealthMessage()).contains("instagram_content_publish");
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    }

    // ---- OAuth refresh drives health ----

    /** A minimal OAuth2 connector so the refresh path is exercised without a real provider. */
    private static final class FakeOAuth2Connector implements OAuth2Connector {
        @Override public String getId() { return "acme"; }
        @Override public List<String> oauthScopes() { return List.of("acme.read"); }
        @Override public String authorizationUrl() { return "https://acme.example.com/oauth/authorize"; }
        @Override public String tokenUrl() { return "https://acme.example.com/oauth/token"; }
        @Override public String clientIdProperty() { return "ACME_OAUTH_CLIENT_ID"; }
        @Override public String clientSecretProperty() { return "ACME_OAUTH_CLIENT_SECRET"; }
        @Override public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("acme", "Acme", ConnectorCategory.ANALYTICS, "Acme", "AC");
        }
        @Override public ConnectorSpec getSpec() { return ConnectorSpec.oauth2(true, List.of()); }
    }

    private record OAuthHarness(OAuthFlowService service, RestTemplate restTemplate) {}

    private OAuthHarness oauthHarness() {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.findOAuth2("acme")).thenReturn(Optional.of(new FakeOAuth2Connector()));
        Environment environment = mock(Environment.class);
        when(environment.getProperty("ACME_OAUTH_CLIENT_ID", "")).thenReturn("acme-client-id");
        when(environment.getProperty("ACME_OAUTH_CLIENT_SECRET", "")).thenReturn("acme-client-secret");
        RestTemplate restTemplate = mock(RestTemplate.class);

        OAuthFlowService service = new OAuthFlowService(
                mock(IntegrationOAuthStateRepository.class),
                mock(ConnectionService.class),
                registry,
                environment,
                new ObjectMapper(),
                connectionHealthService);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        return new OAuthHarness(service, restTemplate);
    }

    private void stubTokenEndpoint(RestTemplate restTemplate, Throwable failure) {
        when(restTemplate.exchange(eq("https://acme.example.com/oauth/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class))).thenThrow(failure);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRefreshFailureWithAnAuthErrorMarksTheConnectionUnhealthyWithThePlatformMessage() {
        Connection conn = persistedConnection();
        OAuthHarness harness = oauthHarness();
        stubTokenEndpoint(harness.restTemplate(), HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                ("{\"error\": \"invalid_grant\", \"error_description\": "
                        + "\"Token has been expired or revoked.\"}").getBytes(), null));

        assertThatThrownBy(() -> harness.service().refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(OAuthReauthRequiredException.class);

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("UNHEALTHY");
        assertThat(reloaded.getHealthMessage()).contains("Token has been expired or revoked.");
        assertThat(reloaded.getHealthCheckedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void aRefreshFailureWithA401AlsoMarksTheConnectionUnhealthy() {
        Connection conn = persistedConnection();
        OAuthHarness harness = oauthHarness();
        stubTokenEndpoint(harness.restTemplate(), HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                "{\"error\": \"invalid_client\"}".getBytes(), null));

        assertThatThrownBy(() -> harness.service().refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(reload(conn.getId()).getHealthStatus()).isEqualTo("UNHEALTHY");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aSubsequentSuccessfulRefreshClearsTheConnectionBackToHealthy() {
        Connection conn = persistedConnection();
        connectionHealthService.markUnhealthy(conn.getId(), "Token has been expired or revoked.");

        OAuthHarness harness = oauthHarness();
        when(harness.restTemplate().exchange(eq("https://acme.example.com/oauth/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(
                        Map.of("access_token", "new-access", "expires_in", 3600)));

        assertThat(harness.service().refreshAccessToken(conn, "refresh-456")).isEqualTo("new-access");

        Connection reloaded = reload(conn.getId());
        assertThat(reloaded.getHealthStatus()).isEqualTo("HEALTHY");
        assertThat(reloaded.getHealthMessage()).isNull();
    }

    @Test
    void aTransientNetworkRefreshFailureDoesNotMarkTheConnectionUnhealthy() {
        Connection conn = persistedConnection();
        OAuthHarness harness = oauthHarness();
        stubTokenEndpoint(harness.restTemplate(),
                new ResourceAccessException("I/O error", new IOException("connection reset")));

        assertThatThrownBy(() -> harness.service().refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(reload(conn.getId()).getHealthStatus()).isNull();
    }

    @Test
    void aRateLimitedRefreshDoesNotMarkTheConnectionUnhealthy() {
        Connection conn = persistedConnection();
        OAuthHarness harness = oauthHarness();
        stubTokenEndpoint(harness.restTemplate(), HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(),
                "{\"error\": \"rate_limit_exceeded\"}".getBytes(), null));

        assertThatThrownBy(() -> harness.service().refreshAccessToken(conn, "refresh-456"))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(reload(conn.getId()).getHealthStatus()).isNull();
    }
}
