package com.conductor.service;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.repository.ActionInvocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Media-heavy invocation accommodations (T5.6): a connector may declare its own invocation deadline
 * (a multi-gigabyte video upload can't finish inside the 10s webhook-shaped default), and a failed
 * attempt may leave a resume checkpoint that the retry under the SAME idempotency key picks up
 * instead of restarting from byte zero.
 *
 * <p>The three-way failure contract on {@link ActionConnector} (thrown = transient/retried,
 * {@link ActionResult#error} = permanent/dead-lettered, timeout = terminal-ambiguous/dead-lettered)
 * is asserted here to hold unchanged for a connector that HAS opted into a long deadline.
 */
@ExtendWith(MockitoExtension.class)
class ActionInvocationCheckpointTest {

    private static final String CONNECTOR_ID = "youtube";
    private static final String ACTION_ID = "upload_video";
    private static final String IDEMPOTENCY_KEY = "publish:target-1";
    private static final String OTHER_KEY = "publish:target-2";

    @Mock private ActionInvocationRepository repository;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionService connectionService;

    private ExecutorService executor;
    private ActionInvocationService service;
    private final Map<String, ActionInvocation> rows = new ConcurrentHashMap<>();

    /**
     * Real (not Mockito-mocked) connector so the {@link ActionConnector#getInvocationTimeout()}
     * default method actually runs — a mock would answer the interface default with an empty value
     * regardless of what the implementation declares.
     */
    private static class FakeConnector implements ActionConnector {
        private final Duration declaredTimeout;
        private final Function<Map<String, Object>, ActionResult> body;

        FakeConnector(Duration declaredTimeout, Function<Map<String, Object>, ActionResult> body) {
            this.declaredTimeout = declaredTimeout;
            this.body = body;
        }

        @Override
        public Optional<Duration> getInvocationTimeout() {
            return declaredTimeout != null ? Optional.of(declaredTimeout) : ActionConnector.super.getInvocationTimeout();
        }

        @Override
        public String getId() { return CONNECTOR_ID; }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(CONNECTOR_ID, "YouTube", ConnectorCategory.MARKETING, "test", "YT");
        }

        @Override
        public ConnectorSpec getSpec() { return ConnectorSpec.oauth2(false, List.of()); }

        @Override
        public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
            return body.apply(input);
        }
    }

    private Connection connection() {
        Connection c = new Connection();
        c.setId("conn-1");
        c.setProjectId("proj-1");
        c.setConnectorId(CONNECTOR_ID);
        c.setStatus("ACTIVE");
        return c;
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        service = new ActionInvocationService(repository, connectorRegistry, connectionService,
                new ObjectMapper(), executor);
        service.self = service;

        lenient().when(repository.save(any())).thenAnswer(call -> {
            ActionInvocation a = call.getArgument(0);
            if (a.getId() == null) a.setId("inv-" + a.getIdempotencyKey());
            if (a.getStatus() == null) a.setStatus(ActionInvocationStatus.PENDING);
            rows.put(a.getId(), a);
            return a;
        });
        lenient().when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        lenient().when(repository.findByIdempotencyKey(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(a -> call.getArgument(0).equals(a.getIdempotencyKey()))
                .findFirst());
        lenient().when(connectionService.toContext(any())).thenReturn(
                new ConnectionContext("proj-1", CONNECTOR_ID, "conn-1", "token", null, null, Map.of(), null));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        rows.clear();
    }

    private void registerConnector(ActionConnector connector) {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
    }

    // ---- [auto] invocation timeout is per-connector configurable, 10s default preserved ----

    @Test
    void connectorDeclaringLongTimeout_isNotCancelledAtTheDefaultDeadline() {
        // Default deadline squeezed to 1s so the test doesn't have to burn the real 10s to prove the
        // connector's own (much longer) declared deadline is what actually governs the call.
        service.invokeTimeoutSeconds = 1;
        registerConnector(new FakeConnector(Duration.ofMinutes(30), input -> {
            sleepQuietly(1500);
            return ActionResult.ok(Map.of("video_id", "v1"));
        }));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("file", "big.mp4"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("video_id", "v1");
        assertThat(rows.values().iterator().next().getStatus()).isEqualTo(ActionInvocationStatus.SUCCEEDED);
    }

    @Test
    void connectorDeclaringNoTimeout_runsUnderTheTenSecondDefault() {
        ActionConnector plain = new FakeConnector(null, input -> ActionResult.ok(Map.of()));

        assertThat(plain.getInvocationTimeout()).isEmpty();
        assertThat(service.invokeTimeoutSeconds).isEqualTo(10L);
        assertThat(service.resolveTimeoutSeconds(plain)).isEqualTo(10L);
    }

    @Test
    void connectorDeclaringTimeout_resolvesToItsOwnDeadline() {
        assertThat(service.resolveTimeoutSeconds(new FakeConnector(Duration.ofHours(2), input -> ActionResult.ok(Map.of()))))
                .isEqualTo(7200L);
    }

    // ---- [auto] a resumable checkpoint persists across retries keyed by idempotency key ----

    @Test
    void checkpointWrittenByFailedAttempt_isReadableByRetryUnderSameIdempotencyKey() {
        registerConnector(new FakeConnector(Duration.ofMinutes(30), input -> {
            throw new RuntimeException("socket closed mid-upload");
        }));
        service.invoke(connection(), ACTION_ID, Map.of("file", "big.mp4"), IDEMPOTENCY_KEY, List.of());

        service.saveCheckpoint(IDEMPOTENCY_KEY, "{\"sessionUri\":\"https://upload/session-1\",\"offset\":1048576}");

        assertThat(service.readCheckpoint(IDEMPOTENCY_KEY))
                .contains("{\"sessionUri\":\"https://upload/session-1\",\"offset\":1048576}");
    }

    @Test
    void checkpointIsNotReadableUnderADifferentIdempotencyKey() {
        registerConnector(new FakeConnector(Duration.ofMinutes(30), input -> ActionResult.error("upload rejected")));
        service.invoke(connection(), ACTION_ID, Map.of("file", "big.mp4"), IDEMPOTENCY_KEY, List.of());
        service.invoke(connection(), ACTION_ID, Map.of("file", "other.mp4"), OTHER_KEY, List.of());

        service.saveCheckpoint(IDEMPOTENCY_KEY, "{\"offset\":1048576}");

        assertThat(service.readCheckpoint(OTHER_KEY)).isEmpty();
        assertThat(service.readCheckpoint("publish:never-existed")).isEmpty();
    }

    // ---- [auto] the transient/permanent/timeout contract is unchanged (long-deadline connector too) ----

    @Test
    void thrownFailure_stillRetriesInline_andPersistsFailed() {
        registerConnector(new FakeConnector(Duration.ofMinutes(30), input -> {
            throw new RuntimeException("connection reset");
        }));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of(), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        ActionInvocation row = rows.values().iterator().next();
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getStatus()).isEqualTo(ActionInvocationStatus.FAILED);
    }

    @Test
    void explicitErrorResult_stillDeadLettersImmediately_withoutRetry() {
        registerConnector(new FakeConnector(Duration.ofMinutes(30), input -> ActionResult.error("video too long")));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of(), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("video too long");
        ActionInvocation row = rows.values().iterator().next();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
    }

    @Test
    void timeoutAgainstTheConnectorsOwnDeadline_stillDeadLettersImmediately_withoutRetry() {
        registerConnector(new FakeConnector(Duration.ofSeconds(1), input -> {
            sleepQuietly(30_000);
            return ActionResult.ok(Map.of());
        }));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of(), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out").contains("not retried");
        ActionInvocation row = rows.values().iterator().next();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
