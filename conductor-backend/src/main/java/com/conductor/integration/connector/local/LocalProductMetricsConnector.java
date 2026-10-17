package com.conductor.integration.connector.local;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSource;
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
import com.conductor.integration.connector.productmetrics.ProductMetricsContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Local-profile stand-in for the product metrics connector: serves a product-neutral in-app funnel (first
 * open to paid), paid spend, a cost-per-trial ratio, a source breakdown, a target and reference values, so
 * the in-app channel of the overview renders without a real backend. It builds the same JSON a real backend
 * would and reads it back through {@link ProductMetricsContract}, so it exercises the real parsing path and
 * its output is checked against {@code docs/integrations-product-metrics.schema.json}.
 *
 * <p>The reference ad spend equals the local Apple Search Ads series, so the overview reports no mismatch.
 */
@Component
@Profile("local")
@Primary
public class LocalProductMetricsConnector implements FetchConnector, ChannelMetricsSource {

    public static final String ID = "product-metrics";

    private final ObjectMapper mapper;

    public LocalProductMetricsConnector(ObjectMapper mapper) {
        this.mapper = mapper;
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
                        "The endpoint that serves your product metrics (https only)", FieldType.STRING, true),
                ConnectorConfigField.userInput("apiKey", "API key",
                        "Bearer key for the endpoint. Use a read-only key.", FieldType.SECRET, true)));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        return ConnectorData.healthy(Map.of("metricCount", 9));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }

    // -- ChannelMetricsSource ---------------------------------------------------------------------

    @Override
    public MetricChannel channel() { return MetricChannel.PRODUCT; }

    @Override
    public int backfillDays() { return 90; }

    @Override
    public int maxDaysPerPull() { return 14; }

    @Override
    public int trailingDays() { return 3; }

    @Override
    public int trailingDays(Integer previousPullHint) {
        return previousPullHint == null ? 3 : Math.max(1, previousPullHint);
    }

    @Override
    public ChannelMetricsPull pull(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return ProductMetricsContract.parse(mapper, sampleJson(from, to), from, to).pull();
    }

    @Override
    public List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        return pull(ctx, from, to).points();
    }

    // -- the sample response ----------------------------------------------------------------------

    /** What a product backend would answer for {@code from..to}; public so a test can validate it against the schema. */
    public String sampleJson(LocalDate from, LocalDate to) {
        LocalDate complete = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        ObjectNode root = mapper.createObjectNode();
        root.put("contractVersion", ProductMetricsContract.VERSION);
        root.put("timezone", "America/Los_Angeles");
        root.put("dataCompleteThrough", complete.toString());
        root.put("restatementDays", 3);

        ArrayNode metrics = root.putArray("metrics");
        metric(metrics, "first_open", "First opens", "COUNT", "SUM", "UP", 1, null, null);
        metric(metrics, "onboarding_started", "Onboarding started", "COUNT", "SUM", "UP", 2, null, null);
        metric(metrics, "paywall_shown", "Paywall shown", "COUNT", "SUM", "UP", 3, null, null);
        ObjectNode trial = metric(metrics, "trial_started", "Trials started", "COUNT", "SUM", "UP", 4, null, null);
        trial.putObject("target").put("value", 200).put("direction", "UP").put("window", "28d");
        metric(metrics, "paid", "Paid", "COUNT", "SUM", "UP", 5, null, null);
        metric(metrics, "paid_spend", "Paid acquisition spend", "CURRENCY", "SUM", "DOWN", null, null, null);
        metric(metrics, "paid_trials", "Trials from paid acquisition", "COUNT", "SUM", "UP", null, null, null);
        metric(metrics, "cost_per_trial", "Cost per trial", "CURRENCY", "RATIO", "DOWN", null, "paid_spend",
                "paid_trials");

        ArrayNode points = root.putArray("points");
        ArrayNode reference = root.putArray("reference");
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long open = Math.round(LocalChannelSeries.value("po-open", day, 120));
            long onboarding = Math.round(open * 0.62);
            long paywall = Math.round(onboarding * 0.55);
            long trials = Math.round(paywall * 0.35);
            long paid = Math.round(trials * 0.4);
            long paidTrials = Math.round(trials * 0.4);
            double spend = adsSpend(day);

            total(points, "first_open", day, open, open);
            total(points, "onboarding_started", day, onboarding, open);
            total(points, "paywall_shown", day, paywall, open);
            total(points, "trial_started", day, trials, open);
            total(points, "paid", day, paid, open);
            total(points, "paid_spend", day, spend, null);
            total(points, "paid_trials", day, paidTrials, open);

            // split by acquisition source: paid ads take 40% of each stage, the rest is organic
            for (Map.Entry<String, Long> stage : Map.of("first_open", open, "onboarding_started", onboarding,
                    "paywall_shown", paywall, "trial_started", trials, "paid", paid).entrySet()) {
                long ads = Math.round(stage.getValue() * 0.4);
                dimension(points, stage.getKey(), day, ads, "source:apple_search_ads", open);
                dimension(points, stage.getKey(), day, stage.getValue() - ads, "source:organic", open);
            }
            dimension(points, "paid_spend", day, spend, "source:apple_search_ads", null);
            dimension(points, "paid_trials", day, paidTrials, "source:apple_search_ads", open);

            reference.addObject().put("metric", "ads_spend").put("day", day.toString()).put("value", spend)
                    .putNull("dimension");
            reference.addObject().put("metric", "store_downloads").put("day", day.toString())
                    .put("value", Math.round(LocalChannelSeries.value("po-store", day, 110)))
                    .putNull("dimension");
        }
        return root.toString();
    }

    /** Same arithmetic as {@link LocalAppleSearchAdsConnector}, so the reference matches that connector's spend. */
    private static double adsSpend(LocalDate day) {
        double impressions = LocalChannelSeries.value("asa-impr", day, 2500);
        double taps = Math.round(impressions * 0.07);
        return Math.round(taps * 0.9 * 100) / 100.0;
    }

    private static ObjectNode metric(ArrayNode into, String key, String label, String unit, String aggregation,
                                     String good, Integer funnelOrder, String numerator, String denominator) {
        ObjectNode m = into.addObject();
        m.put("key", key).put("label", label).put("unit", unit).put("aggregation", aggregation).put("good", good);
        if (funnelOrder != null) m.put("funnelOrder", funnelOrder);
        if (numerator != null) m.put("numerator", numerator).put("denominator", denominator);
        return m;
    }

    private static void total(ArrayNode points, String metric, LocalDate day, double value, Long sample) {
        ObjectNode p = points.addObject();
        p.put("metric", metric).put("day", day.toString()).put("value", value).putNull("dimension");
        if (sample != null) p.put("sampleSize", sample);
    }

    private static void dimension(ArrayNode points, String metric, LocalDate day, double value, String dimension,
                                  Long sample) {
        ObjectNode p = points.addObject();
        p.put("metric", metric).put("day", day.toString()).put("value", value).put("dimension", dimension);
        if (sample != null) p.put("sampleSize", sample);
    }
}
