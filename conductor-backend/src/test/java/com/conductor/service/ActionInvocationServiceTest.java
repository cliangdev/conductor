package com.conductor.service;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.repository.ActionInvocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionInvocationServiceTest {

    private static final String CONNECTOR_ID = "discord";
    private static final String ACTION_ID = "post_message";
    private static final String IDEMPOTENCY_KEY = "wfstep:run-1:post";

    @Mock private ActionInvocationRepository repository;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private ConnectionService connectionService;
    @Mock private ActionConnector connector;

    private ExecutorService executor;
    private ActionInvocationService service;
    private AtomicReference<ActionInvocation> stored;

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
        // No real Spring proxy in a unit test — point the self-reference at the instance itself so
        // the @Transactional(REQUIRES_NEW) helper calls just run as plain method calls.
        service.self = service;

        stored = new AtomicReference<>();
        lenient().when(repository.save(any())).thenAnswer(invocationOnMock -> {
            ActionInvocation a = invocationOnMock.getArgument(0);
            if (a.getId() == null) a.setId("inv-1");
            if (a.getStatus() == null) a.setStatus(ActionInvocationStatus.PENDING);
            stored.set(a);
            return a;
        });
        lenient().when(repository.findById(anyString())).thenAnswer(invocationOnMock -> Optional.ofNullable(stored.get()));
        lenient().when(connectionService.toContext(any())).thenReturn(
                new ConnectionContext("proj-1", CONNECTOR_ID, "conn-1", "https://discord/webhook", null, null, Map.of(), null));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void newIdempotencyKey_invokesConnectorOnce_andPersistsSucceeded() {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenReturn(ActionResult.ok(Map.of("message_id", "m1")));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("message_id", "m1");
        verify(connector, times(1)).invoke(eq(ACTION_ID), any(), any());
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.SUCCEEDED);
        assertThat(stored.get().getAttempts()).isEqualTo(1);
    }

    @Test
    void existingSucceededRowUnderSameKey_returnsStoredResult_withoutInvokingConnector() {
        // Simulates a second call under the same idempotency key: the insert loses the unique-index
        // race (or is a genuine re-run) and the original, already-terminal row is returned instead.
        // doThrow(...).when(...), not when(...).thenThrow(...) — the latter would invoke repository.save
        // with the any() matcher's placeholder null argument as part of registering the stub itself,
        // which re-triggers the setUp() answer (dereferencing that null) as a side effect.
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate key")).when(repository).save(any());
        ActionInvocation existing = new ActionInvocation();
        existing.setId("inv-existing");
        existing.setStatus(ActionInvocationStatus.SUCCEEDED);
        existing.setOutputJson("{\"message_id\":\"m0\"}");
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("message_id", "m0");
        verify(connectorRegistry, never()).findAction(anyString());
    }

    @Test
    void existingFailedRowUnderSameKey_returnsStoredFailure_withoutReinvoking() {
        // doThrow(...).when(...), not when(...).thenThrow(...) — the latter would invoke repository.save
        // with the any() matcher's placeholder null argument as part of registering the stub itself,
        // which re-triggers the setUp() answer (dereferencing that null) as a side effect.
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate key")).when(repository).save(any());
        ActionInvocation existing = new ActionInvocation();
        existing.setId("inv-existing");
        existing.setStatus(ActionInvocationStatus.DEAD);
        existing.setErrorMessage("Discord webhook rejected request: 400");
        when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Discord webhook rejected request: 400");
        verify(connector, never()).invoke(any(), any(), any());
    }

    @Test
    void connectorThrows_isClassifiedTransient_retriedInline_thenPersistsFailedAfterExhaustingAttempts() {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenThrow(new RuntimeException("connection reset"));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("connection reset");
        // 3 inline attempts, each recording an attempt bump before invoking.
        verify(connector, times(3)).invoke(eq(ACTION_ID), any(), any());
        assertThat(stored.get().getAttempts()).isEqualTo(3);
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.FAILED);
    }

    @Test
    void connectorThrows_withConnectionCredentialInMessage_redactedInPersistedErrorMessage() {
        // A connector's thrown-exception message can itself embed the connection's own credential (this
        // test's connection() resolves to accessToken "https://discord/webhook" via the toContext stub
        // in setUp()) — e.g. RestTemplate's ResourceAccessException embeds the full request URL, and
        // for a webhook connector the URL IS the credential.
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenThrow(
                new RuntimeException("I/O error on POST request for \"https://discord/webhook?wait=true\": Connection refused"));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.FAILED);
        assertThat(stored.get().getErrorMessage()).doesNotContain("https://discord/webhook");
        assertThat(stored.get().getErrorMessage()).contains("***");
    }

    @Test
    void connectorReturnsExplicitError_isClassifiedPermanent_noRetry_persistsDead() {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenReturn(ActionResult.error("bad webhook url"));

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("bad webhook url");
        // Only ONE attempt — a permanent (explicit error()) result is never retried.
        verify(connector, times(1)).invoke(eq(ACTION_ID), any(), any());
        assertThat(stored.get().getAttempts()).isEqualTo(1);
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
    }

    @Test
    void unknownActionConnector_persistsDead_withoutInvoking() {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.empty());

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        assertThat(result.success()).isFalse();
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
    }

    // ---- retry sweep ----

    @Test
    void sweep_marksDeadAfterMaxAttempts_withoutInvoking() {
        ActionInvocation exhausted = new ActionInvocation();
        exhausted.setId("inv-exhausted");
        exhausted.setConnectionId("conn-1");
        exhausted.setStatus(ActionInvocationStatus.FAILED);
        exhausted.setAttempts(5);
        exhausted.setLastAttemptedAt(OffsetDateTime.now().minusHours(1));
        when(repository.findRetryable(any())).thenReturn(java.util.List.of(exhausted));
        when(repository.findById("inv-exhausted")).thenReturn(Optional.of(exhausted));

        service.retryFailedInvocations();

        assertThat(exhausted.getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
        verify(connectionService, never()).getById(anyString());
    }

    @Test
    void sweep_retriesReadyInvocation_andRecordsAttempt() {
        ActionInvocation retryable = new ActionInvocation();
        retryable.setId("inv-retry");
        retryable.setConnectionId("conn-1");
        retryable.setConnectorId(CONNECTOR_ID);
        retryable.setActionId(ACTION_ID);
        retryable.setInputJson("{\"content\":\"hi\"}");
        retryable.setStatus(ActionInvocationStatus.FAILED);
        retryable.setAttempts(1);
        retryable.setLastAttemptedAt(OffsetDateTime.now().minusMinutes(10));
        when(repository.findRetryable(any())).thenReturn(java.util.List.of(retryable));
        when(repository.findById("inv-retry")).thenReturn(Optional.of(retryable));
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(connection()));
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenReturn(ActionResult.ok(Map.of("message_id", "m2")));

        service.retryFailedInvocations();

        assertThat(retryable.getAttempts()).isEqualTo(2);
        assertThat(retryable.getStatus()).isEqualTo(ActionInvocationStatus.SUCCEEDED);
    }

    // ---- secret redaction at rest ----

    @Test
    void sensitiveValueInInput_redactedInPersistedInputJson_butConnectorReceivesRealValue() {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenReturn(ActionResult.ok(Map.of("message_id", "m1")));

        Map<String, Object> input = Map.of("content", "token=sekret-value-123");

        ActionResult result = service.invoke(connection(), ACTION_ID, input, IDEMPOTENCY_KEY,
                List.of("sekret-value-123"));

        assertThat(result.success()).isTrue();
        // The connector call itself still gets the real, unredacted input.
        verify(connector).invoke(eq(ACTION_ID), eq(input), any());
        // But what's persisted at rest has the secret value stripped out.
        assertThat(stored.get().getInputJson()).doesNotContain("sekret-value-123");
        assertThat(stored.get().getInputJson()).contains("***");
    }

    // ---- timeout = terminal-ambiguous, never retried ----

    @Test
    void timeout_cancelsTheAbandonedCall_deadLettersImmediately_doesNotRetry() throws InterruptedException {
        when(connectorRegistry.findAction(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        CountDownLatch connectorEntered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        when(connector.invoke(eq(ACTION_ID), any(), any())).thenAnswer(invocationOnMock -> {
            connectorEntered.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return ActionResult.ok(Map.of());
        });
        // NOT a 0-second timeout: future.get(0, ...) can time out and cancel the task before the
        // pool thread ever STARTS it (a cancelled-before-running FutureTask never runs), which made
        // this test flake on loaded CI runners with "zero interactions" on the connector mock. A
        // 2-second window is far beyond any realistic task-start latency on the dedicated executor,
        // while the blocked stub above guarantees the get() still times out.
        service.invokeTimeoutSeconds = 2;

        ActionResult result = service.invoke(connection(), ACTION_ID, Map.of("content", "hi"), IDEMPOTENCY_KEY, List.of());

        // If this ever fires, the task genuinely never started — a clearer failure than a
        // "zero interactions" Mockito verify below.
        assertThat(connectorEntered.await(5, TimeUnit.SECONDS))
                .as("connector call should have started before the timeout window elapsed")
                .isTrue();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("timed out").contains("not retried");
        assertThat(stored.get().getStatus()).isEqualTo(ActionInvocationStatus.DEAD);
        // Only ONE attempt — a timeout is treated as terminal-ambiguous and never retried inline.
        verify(connector, times(1)).invoke(eq(ACTION_ID), any(), any());
        assertThat(stored.get().getAttempts()).isEqualTo(1);

        // The abandoned call was actually cancelled (interrupted), not left running forever.
        await().atMost(2, TimeUnit.SECONDS).untilTrue(interrupted);
    }
}
