package com.conductor.service;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.repository.ActionInvocationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbound-action execution engine. Owns idempotency (one durable {@link ActionInvocation} row per
 * caller-supplied key), bounded-timeout connector invocation, inline retry on transient failure, and
 * a background sweep that re-drives ad-hoc (non-workflow-step) invocations left in FAILED, aging them
 * to DEAD after {@link #MAX_ATTEMPTS} total tries. Mirrors {@link WebhookDispatchService} /
 * {@link WebhookRetryScheduler}'s inbound retry shape for the outbound direction.
 *
 * <p>Transaction discipline matches {@link IntegrationFetchService}: connector I/O
 * ({@code connector.invoke}) never runs inside an open transaction — each DB write (claim, attempt
 * bump, terminal persist) is its own short {@code REQUIRES_NEW} transaction, so a slow/hanging
 * connector call never holds a connection-pool transaction open.
 */
@Service
public class ActionInvocationService {

    private static final Logger log = LoggerFactory.getLogger(ActionInvocationService.class);

    /** Total attempts (inline + background sweep combined) before an invocation is dead-lettered. */
    private static final int MAX_ATTEMPTS = 5;
    /** Attempts made synchronously inside the initial {@link #invoke} call, before handing off to the sweep. */
    private static final int MAX_INLINE_ATTEMPTS = 3;
    private static final int INVOKE_TIMEOUT_SECONDS = 10;
    private static final long INLINE_BACKOFF_MILLIS = 500L;

    private final ActionInvocationRepository repository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final ExecutorService actionExecutor;

    /**
     * Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy —
     * see {@link ConnectionService}. Package-private (not {@code private}) so unit tests can set it to
     * the instance under test itself, since a plain Mockito-constructed instance has no real proxy.
     */
    @Autowired
    @Lazy
    ActionInvocationService self;

    public ActionInvocationService(ActionInvocationRepository repository,
                                   ConnectorRegistry connectorRegistry,
                                   ConnectionService connectionService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("actionInvocationExecutor") ExecutorService actionExecutor) {
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
        this.actionExecutor = actionExecutor;
    }

    /**
     * Invokes {@code actionId} on {@code conn}'s connector, keyed by {@code idempotencyKey}. Never
     * throws — always returns an {@link ActionResult}.
     *
     * <p>Claim-or-return: the first caller for a given key claims the row and actually invokes the
     * connector (with up to {@link #MAX_INLINE_ATTEMPTS} inline retries on transient failure). Any
     * later caller with the SAME key never re-invokes — it just returns the stored result, whatever
     * state that row is in (including FAILED/DEAD from a prior attempt). Only the background sweep
     * re-drives a FAILED row after the fact.
     */
    public ActionResult invoke(Connection conn, String actionId, Map<String, Object> input, String idempotencyKey) {
        Claim claim = claimOrLoad(conn, actionId, input, idempotencyKey);
        if (!claim.owned()) {
            return resultFromStored(claim.invocation());
        }
        return runInline(claim.invocation(), conn, actionId, input);
    }

    private record Claim(ActionInvocation invocation, boolean owned) {}

    private Claim claimOrLoad(Connection conn, String actionId, Map<String, Object> input, String idempotencyKey) {
        try {
            return new Claim(self.insertPendingInNewTx(conn, actionId, input, idempotencyKey), true);
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race (or a genuine re-run under the same key) — the winning/original row exists.
            ActionInvocation existing = repository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return new Claim(existing, false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActionInvocation insertPendingInNewTx(Connection conn, String actionId,
                                                 Map<String, Object> input, String idempotencyKey) {
        ActionInvocation invocation = new ActionInvocation();
        invocation.setProjectId(conn.getProjectId());
        invocation.setConnectionId(conn.getId());
        invocation.setConnectorId(conn.getConnectorId());
        invocation.setActionId(actionId);
        invocation.setIdempotencyKey(idempotencyKey);
        invocation.setInputJson(toJson(input));
        invocation.setStatus(ActionInvocationStatus.PENDING);
        return repository.save(invocation);
    }

    private ActionResult resultFromStored(ActionInvocation invocation) {
        return switch (invocation.getStatus()) {
            case SUCCEEDED -> ActionResult.ok(parseOutput(invocation.getOutputJson()));
            case FAILED, DEAD -> ActionResult.error(invocation.getErrorMessage());
            case PENDING -> ActionResult.error("Action invocation already in progress: " + invocation.getIdempotencyKey());
        };
    }

    /** Runs the connector inline, up to {@link #MAX_INLINE_ATTEMPTS} times on transient failure. */
    private ActionResult runInline(ActionInvocation invocation, Connection conn, String actionId, Map<String, Object> input) {
        Optional<ActionConnector> connectorOpt = connectorRegistry.findAction(conn.getConnectorId());
        if (connectorOpt.isEmpty()) {
            String msg = "Connector does not support actions: " + conn.getConnectorId();
            self.persistDeadInNewTx(invocation.getId(), msg);
            return ActionResult.error(msg);
        }
        ActionConnector connector = connectorOpt.get();
        ConnectionContext ctx = connectionService.toContext(conn);

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_INLINE_ATTEMPTS; attempt++) {
            self.recordAttemptInNewTx(invocation.getId());

            ActionResult result;
            try {
                Future<ActionResult> future = actionExecutor.submit(() -> connector.invoke(actionId, input, ctx));
                result = future.get(INVOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                lastError = "Action timed out after " + INVOKE_TIMEOUT_SECONDS + "s";
                log.warn("Action invocation {} timed out (attempt {})", invocation.getId(), attempt);
                result = null;
            } catch (Exception e) {
                // A thrown exception (incl. ExecutionException wrapping the connector's own throw) is
                // TRANSIENT per the ActionConnector contract — retry.
                lastError = rootMessage(e);
                log.warn("Action invocation {} threw on attempt {}: {}", invocation.getId(), attempt, lastError);
                result = null;
            }

            if (result != null && result.success()) {
                return self.persistSuccessInNewTx(invocation.getId(), result);
            }
            if (result != null) {
                // Connector returned ActionResult.error(...) normally = PERMANENT — don't retry.
                self.persistDeadInNewTx(invocation.getId(), result.message());
                return result;
            }
            if (attempt < MAX_INLINE_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }

        String finalError = lastError != null ? lastError : "Action failed after " + MAX_INLINE_ATTEMPTS + " attempts";
        self.persistFailureInNewTx(invocation.getId(), finalError);
        return ActionResult.error(finalError);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptInNewTx(String invocationId) {
        repository.findById(invocationId).ifPresent(invocation -> {
            invocation.setAttempts(invocation.getAttempts() + 1);
            invocation.setLastAttemptedAt(OffsetDateTime.now());
            repository.save(invocation);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActionResult persistSuccessInNewTx(String invocationId, ActionResult result) {
        repository.findById(invocationId).ifPresent(invocation -> {
            invocation.setStatus(ActionInvocationStatus.SUCCEEDED);
            invocation.setOutputJson(toJson(result.output()));
            invocation.setErrorMessage(null);
            repository.save(invocation);
        });
        return result;
    }

    /** Retryable failure (transient) — eligible for the background sweep until it hits {@link #MAX_ATTEMPTS}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistFailureInNewTx(String invocationId, String errorMessage) {
        repository.findById(invocationId).ifPresent(invocation -> {
            invocation.setStatus(ActionInvocationStatus.FAILED);
            invocation.setErrorMessage(errorMessage);
            repository.save(invocation);
        });
    }

    /** Terminal failure (permanent, or the connector/action is unusable) — never retried. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistDeadInNewTx(String invocationId, String errorMessage) {
        repository.findById(invocationId).ifPresent(invocation -> {
            invocation.setStatus(ActionInvocationStatus.DEAD);
            invocation.setErrorMessage(errorMessage);
            repository.save(invocation);
        });
    }

    // ---- background retry sweep (ad-hoc invocations only — see ActionInvocationRepository#findRetryable) ----

    @Scheduled(fixedDelay = 60000)
    public void retryFailedInvocations() {
        OffsetDateTime minimumCutoff = OffsetDateTime.now().minusMinutes(2);
        for (ActionInvocation invocation : repository.findRetryable(minimumCutoff)) {
            if (invocation.getAttempts() >= MAX_ATTEMPTS) {
                log.warn("Marking action invocation {} as DEAD after {} attempts", invocation.getId(), invocation.getAttempts());
                self.persistDeadInNewTx(invocation.getId(), invocation.getErrorMessage());
                continue;
            }
            if (!isReadyForRetry(invocation)) {
                continue;
            }
            retryOnce(invocation);
        }
    }

    private void retryOnce(ActionInvocation invocation) {
        Optional<Connection> conn = connectionService.getById(invocation.getConnectionId());
        Optional<ActionConnector> connector = conn.flatMap(c -> connectorRegistry.findAction(c.getConnectorId()));
        if (conn.isEmpty() || connector.isEmpty()) {
            self.recordAttemptInNewTx(invocation.getId());
            self.persistFailureInNewTx(invocation.getId(), "Connector or connection no longer available");
            return;
        }

        self.recordAttemptInNewTx(invocation.getId());
        ConnectionContext ctx = connectionService.toContext(conn.get());
        Map<String, Object> input = parseInput(invocation.getInputJson());
        try {
            Future<ActionResult> future = actionExecutor.submit(
                    () -> connector.get().invoke(invocation.getActionId(), input, ctx));
            ActionResult result = future.get(INVOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.success()) {
                self.persistSuccessInNewTx(invocation.getId(), result);
            } else {
                self.persistDeadInNewTx(invocation.getId(), result.message());
            }
        } catch (TimeoutException e) {
            self.persistFailureInNewTx(invocation.getId(), "Action timed out after " + INVOKE_TIMEOUT_SECONDS + "s");
        } catch (Exception e) {
            self.persistFailureInNewTx(invocation.getId(), rootMessage(e));
        }
    }

    private boolean isReadyForRetry(ActionInvocation invocation) {
        if (invocation.getLastAttemptedAt() == null) {
            return true;
        }
        long backoffMinutes = (long) Math.pow(2, invocation.getAttempts());
        OffsetDateTime requiredCutoff = OffsetDateTime.now().minusMinutes(backoffMinutes);
        return !invocation.getLastAttemptedAt().isAfter(requiredCutoff);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(INLINE_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String rootMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOutput(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
