package com.conductor.integration.ingest;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindowSpec;
import com.conductor.integration.ToolOperation;
import com.conductor.integration.WindowAlignment;
import com.conductor.service.IntegrationFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Presents any {@link com.conductor.integration.FetchConnector} as a {@code SNAPSHOT}-mode
 * {@link IngestSpec} feed with zero connector code changes — the puller ({@link FeedPullService})
 * selects this adapter when {@link ConnectorRegistry#findIngest} comes back empty but
 * {@link ConnectorRegistry#findFetch} doesn't. Deliberately NOT a default method on
 * {@link com.conductor.integration.IngestConnector} — the six existing connectors don't implement
 * that interface, and never need to.
 *
 * <p>This is the ONLY place in the codebase that ever interprets {@code IngestRequest#cursor()} as
 * anything other than an opaque blob — and even here, it only ever compares it against a period key
 * this same adapter minted on a previous pull ({@code "2026-W30"}, {@code "2026-07-26"}, ...). Every
 * other reader of {@code connector_feed.cursor_state} ({@link ConnectorFeed}, the (later) scheduler)
 * must keep treating it as opaque.
 */
@Component
public class SnapshotIngestAdapter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotIngestAdapter.class);

    private final IntegrationFetchService integrationFetchService;
    private final ConnectorRegistry connectorRegistry;
    private final ObjectMapper objectMapper;

    public SnapshotIngestAdapter(IntegrationFetchService integrationFetchService,
                                 ConnectorRegistry connectorRegistry,
                                 ObjectMapper objectMapper) {
        this.integrationFetchService = integrationFetchService;
        this.connectorRegistry = connectorRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Pulls one {@code SNAPSHOT}-mode feed by bridging {@link IntegrationFetchService#fetchData}.
     *
     * <p>Uses {@code forceRefresh = false} deliberately: a weekly feed's cache is always stale by the
     * time it's due, so it refetches anyway, while a 15-minute feed reuses a cache a dashboard load
     * just warmed instead of re-hammering the remote API. {@code true} would make feed cadence
     * silently drive dashboard cache churn.
     */
    public IngestBatch pull(ConnectionContext ctx, IngestSpec spec, IngestRequest request) {
        ConnectorData data = integrationFetchService.fetchData(ctx.connectionId(), false);

        // Never advance the cursor on stale/missing data -- a weekly feed would otherwise digest last
        // week's numbers as this week's.
        if (data.healthStatus() == ConnectorHealth.SETUP_REQUIRED) {
            return IngestBatch.setupRequired(data.errorMessage());
        }
        if (data.healthStatus() == ConnectorHealth.DEGRADED) {
            return IngestBatch.degraded(data.errorMessage());
        }

        String periodKey = periodKeyFor(spec.window(), Instant.now());
        if (periodKey.equals(request.cursor())) {
            return IngestBatch.empty(periodKey);
        }

        Map<String, Object> projected = project(ctx.connectorId(), spec.projectOperation(), data.data());

        String sourceType = resolveTemplate(spec.sourceType(), ctx.connectorId(), spec.id(), periodKey);
        String sourceRef = "connector://" + ctx.connectorId() + "/" + ctx.connectionId() + "/" + spec.id()
                + "@" + periodKey;
        String dedupKey = "feed:" + ctx.connectionId() + ":" + spec.id() + ":" + periodKey;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(projected);
        } catch (Exception e) {
            log.warn("Failed to serialize snapshot for connector={} ingest={}: {}",
                    ctx.connectorId(), spec.id(), e.getMessage());
            return IngestBatch.degraded("Failed to serialize snapshot: " + e.getMessage());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("health", data.healthStatus().name());
        metadata.put("fetchedAt", data.fetchedAt() != null ? data.fetchedAt().toString() : null);
        metadata.put("periodKey", periodKey);
        metadata.put("projectOperation", spec.projectOperation());

        IngestItem item = new IngestItem(
                sourceType,
                sourceRef,
                null,
                "application/json",
                payload,
                data.fetchedAt() != null ? data.fetchedAt().atOffset(ZoneOffset.UTC) : null,
                dedupKey,
                metadata);

        return IngestBatch.of(List.of(item), periodKey, false);
    }

    /**
     * Package-visible for direct unit testing of ISO-week/year-boundary edge cases without exercising
     * the whole fetch pipeline. A {@code null} window falls back to unlagged {@code DAY} alignment —
     * there's no {@link DigestSpec}/window declared for a plain (non-metric) SNAPSHOT feed.
     */
    String periodKeyFor(IngestWindowSpec windowSpec, Instant now) {
        WindowAlignment alignTo = windowSpec != null ? windowSpec.alignTo() : WindowAlignment.DAY;
        int lagDays = windowSpec != null ? windowSpec.lagDays() : 0;
        LocalDate anchor = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(lagDays);
        return switch (alignTo) {
            case DAY -> anchor.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case MONTH -> anchor.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            // Week-based year (not the calendar year) is load-bearing here -- late-December/early-
            // January dates can belong to a week-based year that differs from getYear(), which is
            // exactly what keeps "2029-12-31" from colliding with "2030-01-01" style boundaries.
            case ISO_WEEK -> String.format("%d-W%02d",
                    anchor.get(IsoFields.WEEK_BASED_YEAR),
                    anchor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        };
    }

    /** Same {@code outputKeys} projection lookup as {@code IntegrationStepExecutor} — unknown operation
     *  id logs a WARN and returns the snapshot unfiltered, matching that executor's existing behavior. */
    private Map<String, Object> project(String connectorId, String operationId, Map<String, Object> data) {
        if (operationId == null || operationId.isBlank() || data == null) {
            return data;
        }
        Set<String> filterKeys = connectorRegistry.findFetch(connectorId)
                .map(fetch -> fetch.getToolSpec().operations().stream()
                        .filter(op -> op.id().equals(operationId))
                        .findFirst()
                        .map(op -> op.outputKeys().isEmpty() ? null : new LinkedHashSet<>(op.outputKeys()))
                        .orElse(null))
                .orElse(null);
        if (filterKeys == null) {
            log.warn("Unknown projectOperation '{}' for connector '{}' -- returning unfiltered snapshot",
                    operationId, connectorId);
            return data;
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (filterKeys.contains(k)) projected.put(k, v);
        });
        return projected;
    }

    /** Resolves ONLY the platform placeholders {@code {connector}}/{@code {ingest}}/{@code {period}} —
     *  any other {@code {...}} token in the template is passed through literally. */
    private String resolveTemplate(String template, String connectorId, String ingestId, String periodKey) {
        if (template == null) return null;
        return template
                .replace("{connector}", connectorId)
                .replace("{ingest}", ingestId)
                .replace("{period}", periodKey);
    }
}
