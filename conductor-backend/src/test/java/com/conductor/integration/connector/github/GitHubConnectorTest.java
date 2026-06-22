package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookRouting;
import com.conductor.integration.WebhookVerification;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.IssueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubConnectorTest {

    private static final String APP_SECRET = "app-webhook-secret";
    private static final String PROJECT_ID = "proj-1";

    @Mock private IssueService issueService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionService connectionService;

    private GitHubConnector connector;

    @BeforeEach
    void setUp() {
        connector = new GitHubConnector(issueService, connectionRepository, connectionService,
                new ObjectMapper(), APP_SECRET);
    }

    private ConnectionContext ctx() {
        return new ConnectionContext(PROJECT_ID, "github", "conn-1", null, null, null, Map.of(), null);
    }

    private HttpHeaders signedHeaders(byte[] body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Hub-Signature-256", sign(body));
        return headers;
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    // --- verify() : app-level signature against the injected app secret ---

    @Test
    void verify_acceptsCorrectSignature_rejectsWrong() throws Exception {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(connector.verify(body, signedHeaders(body), ctx()).valid()).isTrue();

        HttpHeaders bad = new HttpHeaders();
        bad.add("X-Hub-Signature-256", "sha256=deadbeef");
        assertThat(connector.verify(body, bad, ctx()).valid()).isFalse();

        WebhookVerification missing = connector.verify(body, new HttpHeaders(), ctx());
        assertThat(missing.valid()).isFalse();
    }

    @Test
    void verify_failsWhenAppSecretNotConfigured() throws Exception {
        GitHubConnector noSecret = new GitHubConnector(issueService, connectionRepository, connectionService,
                new ObjectMapper(), "");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(noSecret.verify(body, signedHeaders(body), ctx()).valid()).isFalse();
    }

    // --- route() : resolve target connection(s) by installation.id ---

    @Test
    void route_returnsInstallationIdSelector() {
        byte[] body = "{\"installation\":{\"id\":42}}".getBytes(StandardCharsets.UTF_8);
        WebhookRouting routing = connector.route(body, new HttpHeaders());
        assertThat(routing).isNotNull();
        assertThat(routing.configKey()).isEqualTo("installationId");
        assertThat(routing.configValue()).isEqualTo("42");
    }

    @Test
    void route_returnsNullWhenNoInstallationId() {
        // ping / payload without installation → no fan-out, receiver accepts-and-ignores.
        assertThat(connector.route("{\"zen\":\"hi\"}".getBytes(StandardCharsets.UTF_8), new HttpHeaders())).isNull();
    }

    @Test
    void route_returnsNullForUnparseableBody() {
        assertThat(connector.route("not json".getBytes(StandardCharsets.UTF_8), new HttpHeaders())).isNull();
    }

    // --- handleLifecycle() : installation deleted / installation_repositories / other ---

    @Test
    void handleLifecycle_installationDeleted_deletesMatchingConnections() {
        byte[] body = "{\"action\":\"deleted\",\"installation\":{\"id\":42}}".getBytes(StandardCharsets.UTF_8);
        Connection conn = new Connection();
        conn.setId("conn-1");
        when(connectionRepository.findByConnectorIdAndConfigValue("github", "installationId", "42"))
                .thenReturn(List.of(conn));

        boolean consumed = connector.handleLifecycle(body, new HttpHeaders(), "installation");

        assertThat(consumed).isTrue();
        verify(connectionService).delete("conn-1");
    }

    @Test
    void handleLifecycle_installationNonDeleted_isConsumedButDeletesNothing() {
        byte[] body = "{\"action\":\"created\",\"installation\":{\"id\":42}}".getBytes(StandardCharsets.UTF_8);
        boolean consumed = connector.handleLifecycle(body, new HttpHeaders(), "installation");
        assertThat(consumed).isTrue();
        verify(connectionService, never()).delete(anyString());
    }

    @Test
    void handleLifecycle_installationRepositories_isConsumedNoOp() {
        boolean consumed = connector.handleLifecycle("{}".getBytes(StandardCharsets.UTF_8), new HttpHeaders(),
                "installation_repositories");
        assertThat(consumed).isTrue();
        verify(connectionService, never()).delete(anyString());
    }

    @Test
    void handleLifecycle_otherEvent_isNotConsumed() {
        boolean consumed = connector.handleLifecycle("{}".getBytes(StandardCharsets.UTF_8), new HttpHeaders(),
                "pull_request");
        assertThat(consumed).isFalse();
    }

    // --- handleEvent() : delegates issue mutation to IssueService.completeFromPullRequest ---

    @Test
    void handleEvent_mergedPr_delegatesToIssueService() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,"
                + "\"body\":\"closes conductor/PROJ-1\",\"html_url\":\"https://github.com/x/y/pull/3\"}}";
        InboundEvent event = new InboundEvent("delivery-1", "pull_request", payload, Map.of());

        connector.handleEvent(event, ctx());

        verify(issueService).completeFromPullRequest(
                PROJECT_ID, "PROJ", 1, "https://github.com/x/y/pull/3");
    }

    @Test
    void handleEvent_crossProjectIssue_isSkippedQuietly() {
        // IssueService throws EntityNotFoundException for an issue in another project → swallowed, no rethrow.
        doThrow(new EntityNotFoundException("PROJ-1 does not belong to project proj-1"))
                .when(issueService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,"
                + "\"body\":\"closes conductor/PROJ-1\",\"html_url\":\"https://github.com/x/y/pull/3\"}}";
        InboundEvent event = new InboundEvent("delivery-x", "pull_request", payload, Map.of());

        // Must not throw — the dispatcher would otherwise FAIL/retry a permanently-unroutable delivery.
        connector.handleEvent(event, ctx());
        verify(issueService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_nonPullRequestEvent_isIgnored() {
        InboundEvent event = new InboundEvent("delivery-2", "push", "{}", Map.of());
        connector.handleEvent(event, ctx());
        verify(issueService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_notMerged_isIgnored() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":false,"
                + "\"body\":\"closes conductor/PROJ-1\"}}";
        connector.handleEvent(new InboundEvent("d3", "pull_request", payload, Map.of()), ctx());
        verify(issueService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_noClosesDirective_isIgnored() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,\"body\":\"just a PR\"}}";
        connector.handleEvent(new InboundEvent("d4", "pull_request", payload, Map.of()), ctx());
        verify(issueService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }
}
