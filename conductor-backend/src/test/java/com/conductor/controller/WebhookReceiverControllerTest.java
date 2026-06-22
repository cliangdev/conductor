package com.conductor.controller;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.connector.github.GitHubConnector;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IssueService;
import com.conductor.service.WebhookDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generic receiver tests exercised through the GitHub App (app-level, payload-routed) path: app-level
 * signature verify accept/reject, installation.id routing + multi-connection fan-out, per-connection dedup,
 * installation-deleted lifecycle cleanup, and ping/unparseable accept-and-ignore. A REAL {@link GitHubConnector}
 * is used so HMAC verification + routing + lifecycle actually run; persistence + dispatch are mocked.
 */
@ExtendWith(MockitoExtension.class)
class WebhookReceiverControllerTest {

    private static final String SECRET = "app-webhook-secret";

    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionService connectionService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private WebhookEventRepository eventRepository;
    @Mock private WebhookDispatchService dispatchService;
    @Mock private IssueService issueService;

    private WebhookReceiverController controller;

    @BeforeEach
    void setUp() {
        controller = new WebhookReceiverController(
                connectorRegistry, connectionService, connectionRepository, eventRepository, dispatchService);
    }

    /** Register a REAL GitHub connector so verify/route/handleLifecycle run for "github". */
    private void registerGitHub() {
        GitHubConnector connector = new GitHubConnector(
                issueService, connectionRepository, connectionService, new ObjectMapper(), SECRET);
        when(connectorRegistry.findWebhook("github")).thenReturn(Optional.of(connector));
    }

    private MockHttpServletRequest appRequest(String event, String delivery, String body, boolean validSig)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/webhooks/github");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-GitHub-Event", event);
        if (delivery != null) req.addHeader("X-GitHub-Delivery", delivery);
        req.addHeader("X-Hub-Signature-256", validSig ? sign(body) : "sha256=deadbeef");
        return req;
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private Connection conn(String id) {
        Connection c = new Connection();
        c.setId(id);
        c.setConnectorId("github");
        return c;
    }

    @Test
    void appLevel_validPullRequest_routesAndDispatches() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":42},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn("conn-1")));
        when(eventRepository.findByConnectionIdAndDeliveryId("conn-1", "d1")).thenReturn(Optional.empty());
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d1", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(eventRepository).save(any(WebhookEvent.class));
        verify(dispatchService).dispatch(any(WebhookEvent.class));
    }

    @Test
    void appLevel_badSignature_returns401_andDoesNotDispatch() throws Exception {
        registerGitHub();
        String body = "{\"installation\":{\"id\":42}}";

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d2", body, false));

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verify(dispatchService, never()).dispatch(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void appLevel_ping_returns200_withoutRouting() throws Exception {
        registerGitHub();
        // No installation id → route() returns null and lifecycle does not consume → accept-and-ignore.
        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("ping", null, "{\"zen\":\"hi\"}", true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(dispatchService, never()).dispatch(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void appLevel_unparseableBody_returns200_withoutRouting() throws Exception {
        registerGitHub();
        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d0", "not json", true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void appLevel_fanOut_oneRowAndDispatchPerTargetConnection() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":42},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn("conn-1"), conn("conn-2"), conn("conn-3")));
        when(eventRepository.findByConnectionIdAndDeliveryId(any(), eq("d5"))).thenReturn(Optional.empty());
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d5", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(eventRepository, times(3)).save(any(WebhookEvent.class));
        verify(dispatchService, times(3)).dispatch(any(WebhookEvent.class));
        // Each target connection got exactly one durable row.
        verify(eventRepository).findByConnectionIdAndDeliveryId("conn-1", "d5");
        verify(eventRepository).findByConnectionIdAndDeliveryId("conn-2", "d5");
        verify(eventRepository).findByConnectionIdAndDeliveryId("conn-3", "d5");
    }

    @Test
    void appLevel_dedup_replayForSameConnectionIsSkipped() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":42},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn("conn-1")));
        // A row already exists for (conn-1, d6) → idempotent replay, skip.
        when(eventRepository.findByConnectionIdAndDeliveryId("conn-1", "d6"))
                .thenReturn(Optional.of(new WebhookEvent()));

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d6", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(eventRepository, never()).save(any());
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void appLevel_dedup_sameDeliveryDifferentConnectionIsNotSkipped() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":42},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn("conn-1"), conn("conn-2")));
        // conn-1 already processed this delivery; conn-2 has not — only conn-2 should be dispatched.
        when(eventRepository.findByConnectionIdAndDeliveryId("conn-1", "d7"))
                .thenReturn(Optional.of(new WebhookEvent()));
        when(eventRepository.findByConnectionIdAndDeliveryId("conn-2", "d7")).thenReturn(Optional.empty());
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d7", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(eventRepository, times(1)).save(any(WebhookEvent.class));
        verify(dispatchService, times(1)).dispatch(any(WebhookEvent.class));
    }

    @Test
    void appLevel_installationDeleted_deletesMatchingConnections_noDispatch() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"deleted\",\"installation\":{\"id\":42}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn("conn-1")));

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("installation", "d3", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(connectionService).delete("conn-1");
        verify(dispatchService, never()).dispatch(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void appLevel_unconnectedInstallation_isIgnored() throws Exception {
        registerGitHub();
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":99},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "99"))
                .thenReturn(List.of());

        ResponseEntity<Void> resp = controller.receiveAppLevel("github", appRequest("pull_request", "d4", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(dispatchService, never()).dispatch(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void unknownConnector_returns404() throws Exception {
        when(connectorRegistry.findWebhook("nope")).thenReturn(Optional.empty());
        ResponseEntity<Void> resp = controller.receiveAppLevel("nope", appRequest("ping", null, "{}", true));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void perConnection_missingConnection_returns404() throws Exception {
        registerGitHub();
        when(connectionService.getById("conn-x", "github")).thenReturn(Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/webhooks/github/conn-x");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-GitHub-Event", "pull_request");

        ResponseEntity<Void> resp = controller.receiveWebhook("github", "conn-x", req);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(dispatchService, never()).dispatch(any());
    }
}
