package com.conductor.integration.connector.marketing;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeExperiment;
import com.conductor.creative.CreativeExperimentService;
import com.conductor.creative.CreativeRepository;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in, credential-free connector that turns {@code post_publish_target}/
 * {@code post_publish_target_metric} into the weekly "what's working" Knowledge Center digest
 * (see {@code marketing/what-works.md}, driven by {@code conductor-marketing.json}'s
 * {@code what_works_weekly} ingest). No network call, no account to connect — every project is
 * provisioned into this connector automatically by {@link MarketingInsightsFeedProvisioner} once it
 * has published at least one Post, which is also this connector's own health bar.
 *
 * <p>Deliberately has no {@code @Profile} restriction, unlike every credentialed connector's
 * {@code !local}/{@code local} stub pair — there is nothing here a local developer machine can't do
 * for real, so the same class is the "local stub" too.
 */
@Component
public class MarketingInsightsConnector implements FetchConnector {

    private static final Logger log = LoggerFactory.getLogger(MarketingInsightsConnector.class);

    public static final String ID = "conductor-marketing";

    private final MarketingInsightsSnapshotQuery snapshotQuery;
    private final PostPublishTargetMetricRepository metricRepository;
    private final CreativeExperimentService experimentService;
    private final CreativeRepository creativeRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MarketingInsightsConnector(MarketingInsightsSnapshotQuery snapshotQuery,
                                      PostPublishTargetMetricRepository metricRepository,
                                      CreativeExperimentService experimentService,
                                      CreativeRepository creativeRepository,
                                      ObjectMapper objectMapper) {
        this(snapshotQuery, metricRepository, experimentService, creativeRepository, objectMapper, Clock.systemUTC());
    }

    /** Package-visible for tests: a fixed {@link Clock} makes the trailing-7-day window deterministic. */
    MarketingInsightsConnector(MarketingInsightsSnapshotQuery snapshotQuery,
                              PostPublishTargetMetricRepository metricRepository,
                              CreativeExperimentService experimentService,
                              CreativeRepository creativeRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.snapshotQuery = snapshotQuery;
        this.metricRepository = metricRepository;
        this.experimentService = experimentService;
        this.creativeRepository = creativeRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String getId() { return ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(ID, "Marketing insights", ConnectorCategory.MARKETING,
                "Reads what happened to your published Posts and narrates what is working into the "
                        + "Knowledge Center every week.",
                "MI", true);
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.none(true);
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return metricRepository.existsPublishedWithMetricForProject(ctx.projectId())
                ? ConnectorHealth.HEALTHY
                : ConnectorHealth.SETUP_REQUIRED;
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        if (checkHealth(ctx) != ConnectorHealth.HEALTHY) {
            return ConnectorData.setupRequired("No published Posts with metrics yet.");
        }
        Instant now = clock.instant();
        Map<String, Object> payload = snapshotQuery.fetch(ctx.projectId(), now);
        List<Map<String, Object>> hookWinners = hookWinners(ctx.projectId(), now);
        if (!hookWinners.isEmpty()) {
            payload.put("hookWinners", hookWinners);
        }
        return ConnectorData.healthy(payload);
    }

    /**
     * Attempts to settle every RUNNING experiment in this project (COND-24 T5's "decided ... by the
     * weekly insights job" path), then returns every experiment DECIDED since the start of this pull's
     * trailing window — the same window {@code trend}/{@code byPlatform} etc. cover, so "this week's" hook
     * winners line up with "this week's" numbers. Best-effort: a failure here must never fail the whole
     * feed pull, since the numeric digest is the connector's primary job.
     */
    private List<Map<String, Object>> hookWinners(String projectId, Instant now) {
        try {
            experimentService.decideAllRunning(projectId);
        } catch (RuntimeException e) {
            log.warn("Failed to decide RUNNING experiments for project {}: {}", projectId, e.toString());
        }
        OffsetDateTime since = MarketingInsightsSnapshotQuery.trailingWindow(now)[0].atOffset(ZoneOffset.UTC);
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (CreativeExperiment experiment : experimentService.decidedSince(projectId, since)) {
                Creative winner = experiment.getWinnerCreativeId() != null
                        ? creativeRepository.findByIdAndProjectId(experiment.getWinnerCreativeId(), projectId).orElse(null)
                        : null;
                if (winner == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("creative", winner.displayId());
                row.put("headline", winner.getHeadline());
                row.put("metric", experiment.getMetric());
                row.put("windowHours", experiment.getWindowHours());
                if (experiment.getSummary() != null) {
                    row.put("variants", objectMapper.convertValue(experiment.getSummary().get("variants"),
                            new TypeReference<List<Map<String, Object>>>() { }));
                }
                rows.add(row);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to read decided experiments for project {}: {}", projectId, e.toString());
            return List.of();
        }
        return rows;
    }
}
