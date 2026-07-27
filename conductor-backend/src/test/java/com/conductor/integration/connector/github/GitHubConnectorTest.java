package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.integration.AuthType;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.CredentialRequest;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.RuntimeCredential;
import com.conductor.integration.WebhookRouting;
import com.conductor.integration.WebhookVerification;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemService;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubConnectorTest {

    private static final String APP_SECRET = "app-webhook-secret";
    private static final String PROJECT_ID = "proj-1";

    @Mock private WorkItemService workItemService;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectionService connectionService;
    @Mock private GitHubAppService gitHubAppService;
    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private ProjectSettingsService projectSettingsService;
    @Mock private SignalBus signalBus;

    private GitHubConnector connector;

    @BeforeEach
    void setUp() {
        // Most tests don't care about the knowledge-submission side effect — default to disabled so
        // they don't need to stub it; the dedicated knowledge tests below override it.
        lenient().when(projectSettingsService.isKnowledgeEnabled(anyString())).thenReturn(false);
        connector = new GitHubConnector(workItemService, connectionRepository, connectionService,
                gitHubAppService, knowledgeIngestionService, projectSettingsService, signalBus,
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
        GitHubConnector noSecret = new GitHubConnector(workItemService, connectionRepository, connectionService,
                gitHubAppService, knowledgeIngestionService, projectSettingsService, signalBus,
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
        // Uninstall must also evict the installation's cached access token (#15).
        verify(gitHubAppService).evictInstallationToken("42");
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

    // --- handleEvent() : delegates issue mutation to WorkItemService.completeFromPullRequest ---

    @Test
    void handleEvent_mergedPr_delegatesToWorkItemService() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,"
                + "\"body\":\"closes conductor/PROJ-1\",\"html_url\":\"https://github.com/x/y/pull/3\"}}";
        InboundEvent event = new InboundEvent("delivery-1", "pull_request", payload, Map.of());

        connector.handleEvent(event, ctx());

        verify(workItemService).completeFromPullRequest(
                PROJECT_ID, "PROJ", 1, "https://github.com/x/y/pull/3");
    }

    @Test
    void handleEvent_crossProjectIssue_isSkippedQuietly() {
        // WorkItemService throws EntityNotFoundException for an issue in another project → swallowed, no rethrow.
        doThrow(new EntityNotFoundException("PROJ-1 does not belong to project proj-1"))
                .when(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,"
                + "\"body\":\"closes conductor/PROJ-1\",\"html_url\":\"https://github.com/x/y/pull/3\"}}";
        InboundEvent event = new InboundEvent("delivery-x", "pull_request", payload, Map.of());

        // Must not throw — the dispatcher would otherwise FAIL/retry a permanently-unroutable delivery.
        connector.handleEvent(event, ctx());
        verify(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_nonPullRequestEvent_isIgnored() {
        InboundEvent event = new InboundEvent("delivery-2", "push", "{}", Map.of());
        connector.handleEvent(event, ctx());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_notMerged_isIgnored() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":false,"
                + "\"body\":\"closes conductor/PROJ-1\"}}";
        connector.handleEvent(new InboundEvent("d3", "pull_request", payload, Map.of()), ctx());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_noClosesDirective_isIgnored() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,\"body\":\"just a PR\"}}";
        connector.handleEvent(new InboundEvent("d4", "pull_request", payload, Map.of()), ctx());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    // --- handleEvent() : Knowledge Center adapter — submits a merged PR as a github.pr_merged source ---

    private static final String MERGED_PR_PAYLOAD =
            "{\"action\":\"closed\",\"repository\":{\"full_name\":\"x/y\"},"
            + "\"pull_request\":{\"number\":3,\"merged\":true,\"title\":\"Add feature\","
            + "\"body\":\"just a PR, no conductor link\","
            + "\"labels\":[{\"name\":\"enhancement\"}],"
            + "\"merged_by\":{\"login\":\"alice\"},"
            + "\"base\":{\"sha\":\"aaa\"},\"head\":{\"sha\":\"bbb\"}}}";

    @Test
    void handleEvent_mergedPr_knowledgeEnabled_submitsSource() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        connector.handleEvent(new InboundEvent("d5", "pull_request", MERGED_PR_PAYLOAD, Map.of()), ctx());

        org.mockito.ArgumentCaptor<KnowledgeSubmission> captor = org.mockito.ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(knowledgeIngestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(PROJECT_ID);
        assertThat(submission.sourceType()).isEqualTo("github.pr_merged");
        assertThat(submission.sourceRef()).isEqualTo("github:x/y#3");
        assertThat(submission.title()).isEqualTo("Add feature");
        assertThat(submission.payload()).contains("\"enhancement\"", "\"alice\"", "\"aaa\"", "\"bbb\"");
        assertThat(submission.dedupKey()).isEqualTo("github-pr-merged:github:x/y#3");
    }

    @Test
    void handleEvent_mergedPr_knowledgeDisabled_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        connector.handleEvent(new InboundEvent("d6", "pull_request", MERGED_PR_PAYLOAD, Map.of()), ctx());

        verify(knowledgeIngestionService, never()).submit(any());
    }

    @Test
    void handleEvent_nonMergedClose_knowledgeEnabled_doesNotSubmit() {
        // isMergeEvent is false here, so submitMergedPrKnowledge (and its isKnowledgeEnabled check)
        // is never reached — nothing to stub; this only asserts the ingestion service is left alone.
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":false}}";

        connector.handleEvent(new InboundEvent("d7", "pull_request", payload, Map.of()), ctx());

        verify(knowledgeIngestionService, never()).submit(any());
    }

    // --- handleEvent() : firable PR actions dispatch GITHUB_PULL_REQUEST for workflow triggers ---

    private static final String PR_ACTION_PAYLOAD_TEMPLATE =
            "{\"action\":\"%s\",\"installation\":{\"id\":42},"
            + "\"repository\":{\"name\":\"nexus-backend\",\"full_name\":\"Rexworks-LLC/nexus-backend\"},"
            + "\"pull_request\":{\"number\":7,\"title\":\"Add feature\",\"merged\":false,"
            + "\"user\":{\"login\":\"alice\"},\"head\":{\"ref\":\"feature-branch\"},\"base\":{\"ref\":\"main\"},"
            + "\"html_url\":\"https://github.com/Rexworks-LLC/nexus-backend/pull/7\"}}";

    @Test
    void handleEvent_prOpened_dispatchesGitHubPullRequestEventWithMetadata() {
        String payload = String.format(PR_ACTION_PAYLOAD_TEMPLATE, "opened");

        connector.handleEvent(new InboundEvent("d8", "pull_request", payload, Map.of()), ctx());

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        Signal signal = captor.getValue();
        assertThat(signal.type()).isEqualTo(SignalTypes.GITHUB_PULL_REQUEST);
        assertThat(signal.projectId()).isEqualTo(PROJECT_ID);
        assertThat(signal.payload())
                .containsEntry("repoName", "nexus-backend")
                .containsEntry("repoFullName", "Rexworks-LLC/nexus-backend")
                .containsEntry("prNumber", "7")
                .containsEntry("prTitle", "Add feature")
                .containsEntry("author", "alice")
                .containsEntry("headRef", "feature-branch")
                .containsEntry("baseRef", "main")
                .containsEntry("htmlUrl", "https://github.com/Rexworks-LLC/nexus-backend/pull/7")
                .containsEntry("installationId", "42")
                .containsEntry("action", "opened")
                .doesNotContainKey("label");
    }

    @Test
    void handleEvent_prSynchronize_dispatchesGitHubPullRequestEvent() {
        String payload = String.format(PR_ACTION_PAYLOAD_TEMPLATE, "synchronize");
        connector.handleEvent(new InboundEvent("d9", "pull_request", payload, Map.of()), ctx());
        verify(signalBus).publish(any());
    }

    @Test
    void handleEvent_prReopened_dispatchesGitHubPullRequestEvent() {
        String payload = String.format(PR_ACTION_PAYLOAD_TEMPLATE, "reopened");
        connector.handleEvent(new InboundEvent("d10", "pull_request", payload, Map.of()), ctx());
        verify(signalBus).publish(any());
    }

    @Test
    void handleEvent_prLabeled_includesLabelMetadata() {
        String payload = "{\"action\":\"labeled\",\"pull_request\":{\"number\":7,\"merged\":false},"
                + "\"label\":{\"name\":\"code_review_ready\"}}";

        connector.handleEvent(new InboundEvent("d11", "pull_request", payload, Map.of()), ctx());

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().payload())
                .containsEntry("action", "labeled")
                .containsEntry("label", "code_review_ready");
    }

    @Test
    void handleEvent_closedWithoutMerge_doesNotDispatchReviewEventOrMergeCompletion() {
        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":false,"
                + "\"body\":\"closes conductor/PROJ-1\"}}";

        connector.handleEvent(new InboundEvent("d12", "pull_request", payload, Map.of()), ctx());

        verify(signalBus, never()).publish(any());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void handleEvent_unrelatedAction_doesNotDispatch() {
        String payload = "{\"action\":\"assigned\",\"pull_request\":{\"number\":7,\"merged\":false}}";

        connector.handleEvent(new InboundEvent("d13", "pull_request", payload, Map.of()), ctx());

        verify(signalBus, never()).publish(any());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    // --- issueRuntimeCredential() : CREDENTIAL capability (Phase B connector-issued runtime credentials) ---

    private Connection connectionWithConfig(String configJson) {
        Connection c = new Connection();
        c.setId("conn-gh");
        c.setConfigJson(configJson);
        return c;
    }

    @Test
    void issueRuntimeCredential_unscoped_returnsGhTokenFromInstallationToken() {
        Connection conn = connectionWithConfig("{\"installationId\":\"42\"}");
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        when(gitHubAppService.installationToken("42", List.of()))
                .thenReturn(new GitHubAppService.InstallationTokenResult("ghs_abc", expiry));

        RuntimeCredential credential = connector.issueRuntimeCredential(conn, new CredentialRequest(null));

        assertThat(credential.envHint()).isEqualTo("GH_TOKEN");
        assertThat(credential.value()).isEqualTo("ghs_abc");
        assertThat(credential.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void issueRuntimeCredential_withRepoFullName_scopesToBareRepoName() {
        // GitHub's installation-token "repositories" field expects bare repo names, not owner/repo —
        // passing the full path 422s with "repository does not exist or is not accessible".
        Connection conn = connectionWithConfig("{\"installationId\":\"42\"}");
        java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
        when(gitHubAppService.installationToken("42", List.of("nexus-backend")))
                .thenReturn(new GitHubAppService.InstallationTokenResult("ghs_scoped", expiry));

        RuntimeCredential credential = connector.issueRuntimeCredential(
                conn, new CredentialRequest("Rexworks-LLC/nexus-backend"));

        assertThat(credential.value()).isEqualTo("ghs_scoped");
        verify(gitHubAppService).installationToken("42", List.of("nexus-backend"));
    }

    @Test
    void issueRuntimeCredential_nullRequest_isTreatedAsUnscoped() {
        Connection conn = connectionWithConfig("{\"installationId\":\"42\"}");
        when(gitHubAppService.installationToken("42", List.of()))
                .thenReturn(new GitHubAppService.InstallationTokenResult("ghs_abc", java.time.Instant.now()));

        connector.issueRuntimeCredential(conn, null);

        verify(gitHubAppService).installationToken("42", List.of());
    }

    @Test
    void issueRuntimeCredential_missingInstallationId_throwsClearly() {
        Connection conn = connectionWithConfig("{}");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> connector.issueRuntimeCredential(conn, new CredentialRequest(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installationId");
        verify(gitHubAppService, never()).installationToken(anyString(), any());
    }

    // --- issueRuntimeCredential() : PAT connections return the stored token, no GitHubAppService call ---

    @Test
    void issueRuntimeCredential_patConnection_returnsStoredTokenWithoutCallingGitHubAppService() {
        Connection conn = new Connection();
        conn.setId("conn-pat");
        conn.setAuthType(AuthType.PAT.name());
        java.time.OffsetDateTime expiry = java.time.OffsetDateTime.now().plusDays(30);
        conn.setTokenExpiresAt(expiry);
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("ghp_stored", null, null, Map.of()));

        RuntimeCredential credential = connector.issueRuntimeCredential(conn, new CredentialRequest("owner/repo"));

        assertThat(credential.envHint()).isEqualTo("GH_TOKEN");
        assertThat(credential.value()).isEqualTo("ghp_stored");
        assertThat(credential.expiresAt()).isEqualTo(expiry.toInstant());
        verify(gitHubAppService, never()).installationToken(anyString(), any());
        verify(gitHubAppService, never()).installationToken(anyString());
    }

    @Test
    void issueRuntimeCredential_patConnection_blankStoredToken_throwsClearly() {
        Connection conn = new Connection();
        conn.setId("conn-pat");
        conn.setAuthType(AuthType.PAT.name());
        when(connectionService.decrypt(conn))
                .thenReturn(new DecryptedCredentials("", null, null, Map.of()));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> connector.issueRuntimeCredential(conn, new CredentialRequest(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conn-pat");
    }

    // --- handleEvent() : characterization of the merge-path ordering, error isolation, and outer
    //     throw-to-retry contract (see the SignalBus/KnowledgeSignalSink subscriber tests for the
    //     sibling event-bus behaviour) ---

    private static final String MERGED_PR_WITH_ISSUE_PAYLOAD =
            "{\"action\":\"closed\",\"repository\":{\"full_name\":\"x/y\"},"
            + "\"pull_request\":{\"number\":3,\"merged\":true,\"title\":\"Add feature\","
            + "\"body\":\"closes conductor/PROJ-1\","
            + "\"html_url\":\"https://github.com/x/y/pull/3\"}}";

    @Test
    void mergedWithIssueRef_submitsKnowledgeBeforeCompletingWorkItem() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        connector.handleEvent(new InboundEvent("d14", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx());

        InOrder inOrder = inOrder(knowledgeIngestionService, workItemService);
        inOrder.verify(knowledgeIngestionService).submit(any());
        inOrder.verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 1,
                "https://github.com/x/y/pull/3");
    }

    @Test
    void mergedWithoutIssueRef_stillSubmitsKnowledge_andNeverCallsWorkItemService() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        connector.handleEvent(new InboundEvent("d15", "pull_request", MERGED_PR_PAYLOAD, Map.of()), ctx());

        verify(knowledgeIngestionService).submit(any());
        verify(workItemService, never()).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void mergedWithKnowledgeDisabled_stillCompletesWorkItem() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        connector.handleEvent(new InboundEvent("d16", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx());

        verify(knowledgeIngestionService, never()).submit(any());
        verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 1,
                "https://github.com/x/y/pull/3");
    }

    @Test
    void knowledgeSubmitThrows_doesNotBlockWorkItemCompletion_andNothingEscapes() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        doThrow(new RuntimeException("ingestion boom")).when(knowledgeIngestionService).submit(any());

        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
                connector.handleEvent(new InboundEvent("d17", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx()));

        verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 1,
                "https://github.com/x/y/pull/3");
    }

    @Test
    void workItemServiceThrowsRuntimeException_escapesAsRuntimeException() {
        doThrow(new RuntimeException("db exploded"))
                .when(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        connector.handleEvent(new InboundEvent("d18", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process GitHub event");
    }

    @Test
    void workItemServiceThrowsEntityNotFound_isSwallowed() {
        doThrow(new EntityNotFoundException("no such issue"))
                .when(workItemService).completeFromPullRequest(anyString(), anyString(), anyInt(), anyString());

        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
                connector.handleEvent(new InboundEvent("d19", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx()));
    }

    @Test
    void malformedJsonPayload_escapesAsRuntimeException() {
        InboundEvent event = new InboundEvent("d20", "pull_request", "not json at all", Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.handleEvent(event, ctx()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process GitHub event");
    }

    @Test
    void reviewActionDispatchThrows_escapesAsRuntimeException() {
        doThrow(new RuntimeException("dispatch boom")).when(signalBus).publish(any());
        String payload = String.format(PR_ACTION_PAYLOAD_TEMPLATE, "synchronize");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        connector.handleEvent(new InboundEvent("d21", "pull_request", payload, Map.of()), ctx()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process GitHub event");
    }

    @Test
    void mergedPr_neverDispatchesGitHubPullRequestEvent() {
        connector.handleEvent(new InboundEvent("d22", "pull_request", MERGED_PR_WITH_ISSUE_PAYLOAD, Map.of()), ctx());

        verifyNoInteractions(signalBus);
    }

    @Test
    void labeledWithoutLabelName_omitsTheLabelKey() {
        String payload = "{\"action\":\"labeled\",\"pull_request\":{\"number\":7,\"merged\":false},"
                + "\"label\":{}}";

        connector.handleEvent(new InboundEvent("d23", "pull_request", payload, Map.of()), ctx());

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().payload()).doesNotContainKey("label");
    }
}
