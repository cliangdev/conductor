package com.conductor.integration.connector.productmetrics;

import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.FieldType;
import com.conductor.integration.MetricChannel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic "Product metrics" connector: any product backend that serves the product metrics contract
 * ({@code docs/integrations-product-metrics.md}) behind a URL and a bearer key. It feeds the in-app (PRODUCT)
 * channel of the marketing overview through the {@code channel_metrics} feed, and one workspace may connect
 * several backends.
 */
@Component
@Profile("!local")
public class ProductMetricsConnector implements FetchConnector, ChannelMetricsSource {

    public static final String ID = "product-metrics";

    /** Default restatement window until a response says otherwise. */
    static final int DEFAULT_TRAILING_DAYS = ProductMetricsContract.DEFAULT_TRAILING_DAYS;

    private final ProductMetricsClient client;

    public ProductMetricsConnector(ProductMetricsClient client) {
        this.client = client;
    }

    @Override
    public String getId() { return ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(ID, "Product metrics", ConnectorCategory.ANALYTICS,
                "In-app funnel and acquisition numbers from your own product backend", "PM");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
                ConnectorConfigField.userInput("baseUrl", "Metrics URL",
                        "The endpoint that serves your product metrics, e.g. https://api.example.com/v1/marketing-metrics"
                                + " (https only)",
                        FieldType.STRING, true),
                ConnectorConfigField.userInput("apiKey", "API key",
                        "Bearer key for the endpoint. Use a read-only key.",
                        FieldType.SECRET, true)));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    // ---- health ------------------------------------------------------------------------------------

    /** Result of a connection probe: plain-language message and the health it implies. */
    public record Probe(ConnectorHealth health, String message, int metricCount) {
        public boolean ok() { return health == ConnectorHealth.HEALTHY; }
    }

    /** A one-day pull that validates the contract version and shape. */
    public Probe probe(ConnectionContext ctx) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            ProductMetricsContract.Parsed parsed = client.fetch(ctx, day, day);
            int count = parsed.pull().definitions().size();
            return new Probe(ConnectorHealth.HEALTHY,
                    "Connected. The server reports " + count + (count == 1 ? " metric" : " metrics")
                            + ", complete through " + parsed.dataCompleteThrough() + ".", count);
        } catch (ChannelMetricsSourceException e) {
            return new Probe(e.health(), e.getMessage(), 0);
        }
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return probe(ctx).health();
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        Probe p = probe(ctx);
        if (p.health() == ConnectorHealth.SETUP_REQUIRED) return ConnectorData.setupRequired(p.message());
        if (!p.ok()) return ConnectorData.degraded(p.message(), Map.of());
        return ConnectorData.healthy(Map.of("metricCount", p.metricCount()));
    }

    /** Refuse a connection that cannot work before it is saved, with a plain reason (bad URL, rejected key, wrong contract version). */
    @Override
    public Optional<String> verifyCredentials(ConnectionContext ctx) {
        Probe p = probe(ctx);
        return p.ok() ? Optional.empty() : Optional.of(p.message());
    }

    // ---- ChannelMetricsSource ----------------------------------------------------------------------

    @Override
    public MetricChannel channel() { return MetricChannel.PRODUCT; }

    @Override
    public int backfillDays() { return 90; }

    @Override
    public int maxDaysPerPull() { return 14; }

    @Override
    public int trailingDays() { return DEFAULT_TRAILING_DAYS; }

    /** The window the previous response asked to be re-read (its restatementDays or its lag), else 3 days. */
    @Override
    public int trailingDays(Integer previousPullHint) {
        return previousPullHint == null ? DEFAULT_TRAILING_DAYS : Math.max(1, previousPullHint);
    }

    @Override
    public ChannelMetricsPull pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return client.fetch(ctx, from, to).pull();
    }

    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return pull(ctx, from, to).points();
    }
}
