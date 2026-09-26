package com.conductor.integration.connector.marketing;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.repository.PostPublishTargetMetricRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
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

    public static final String ID = "conductor-marketing";

    private final MarketingInsightsSnapshotQuery snapshotQuery;
    private final PostPublishTargetMetricRepository metricRepository;
    private final Clock clock;

    @Autowired
    public MarketingInsightsConnector(MarketingInsightsSnapshotQuery snapshotQuery,
                                      PostPublishTargetMetricRepository metricRepository) {
        this(snapshotQuery, metricRepository, Clock.systemUTC());
    }

    /** Package-visible for tests: a fixed {@link Clock} makes the trailing-7-day window deterministic. */
    MarketingInsightsConnector(MarketingInsightsSnapshotQuery snapshotQuery,
                              PostPublishTargetMetricRepository metricRepository, Clock clock) {
        this.snapshotQuery = snapshotQuery;
        this.metricRepository = metricRepository;
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
        Map<String, Object> payload = snapshotQuery.fetch(ctx.projectId(), clock.instant());
        return ConnectorData.healthy(payload);
    }
}
