package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.integration.ConnectionContext;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubAppWebhookControllerTest {

    private static final String SECRET = "app-webhook-secret";

    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionService connectionService;
    @Mock private WebhookEventRepository eventRepository;
    @Mock private WebhookDispatchService dispatchService;

    private GitHubConnector connector;
    private GitHubAppWebhookController controller;

    @BeforeEach
    void setUp() {
        // Real connector so HMAC verification runs; issue repo unused for these tests.
        connector = new GitHubConnector(null, new ObjectMapper(), SECRET);
        controller = new GitHubAppWebhookController(
                connector, connectionRepository, connectionService, eventRepository, dispatchService, new ObjectMapper());
    }

    private MockHttpServletRequest request(String event, String delivery, String body, boolean validSig) throws Exception {
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

    @Test
    void validPullRequest_routesToDispatch() throws Exception {
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":42},"
                + "\"pull_request\":{\"merged\":true,\"body\":\"closes conductor/PROJ-1\"}}";
        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setConnectorId("github");
        when(eventRepository.findByDeliveryId("d1")).thenReturn(Optional.empty());
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn));

        ResponseEntity<Void> resp = controller.receive(request("pull_request", "d1", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(eventRepository).save(any(WebhookEvent.class));
        verify(dispatchService).dispatch(any(WebhookEvent.class));
    }

    @Test
    void badSignature_returns401_andDoesNotDispatch() throws Exception {
        String body = "{\"installation\":{\"id\":42}}";
        ResponseEntity<Void> resp = controller.receive(request("pull_request", "d2", body, false));
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void ping_returns200_withoutRouting() throws Exception {
        ResponseEntity<Void> resp = controller.receive(request("ping", null, "{\"zen\":\"hi\"}", true));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void installationDeleted_deletesMatchingConnections() throws Exception {
        String body = "{\"action\":\"deleted\",\"installation\":{\"id\":42}}";
        Connection conn = new Connection();
        conn.setId("conn-1");
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn));

        ResponseEntity<Void> resp = controller.receive(request("installation", "d3", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(connectionService).delete("conn-1");
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void unconnectedInstallation_isIgnored() throws Exception {
        String body = "{\"action\":\"closed\",\"installation\":{\"id\":99}}";
        when(eventRepository.findByDeliveryId("d4")).thenReturn(Optional.empty());
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "99"))
                .thenReturn(List.of());

        ResponseEntity<Void> resp = controller.receive(request("pull_request", "d4", body, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(dispatchService, never()).dispatch(any());
    }
}
