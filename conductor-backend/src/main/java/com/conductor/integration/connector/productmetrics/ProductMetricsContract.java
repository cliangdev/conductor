package com.conductor.integration.connector.productmetrics;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.MetricDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads a response of the product metrics contract, v1, v2 or v3 ({@code docs/integrations-product-metrics.md},
 * {@code docs/integrations-product-metrics.schema.json}) into a {@link ChannelMetricsPull}.
 *
 * <p>Strict on purpose: a response that breaks the contract is rejected whole with a plain message, never
 * half-applied, so a mistake in the product backend shows up on the connection instead of as quietly wrong
 * numbers. Unknown extra fields are ignored. Version 2 only adds optional fields and wider limits (cohort
 * basis, area, description, headline, a watch band, {@code keyword:}/{@code ad:} dimensions, restatement
 * up to 60 days); a v1 response reads exactly as before, and v2 fields on it are ignored. Version 3 adds, again
 * all optional, per-metric {@code exclusions}, the {@code POSITION} unit, the {@code offer:}, {@code link:},
 * {@code message:}, {@code intent:}, {@code aso:}, {@code channel:} and {@code paywall:} dimensions and two more
 * reference metrics; a v1 or v2 response ignores the v3 fields it carries, except that a v3-only dimension prefix
 * or unit is refused there like any unknown one. A reference metric Conductor does not read is ignored, not refused.
 */
public final class ProductMetricsContract {

    /** The version the bundled JSON schema file and the local sample response declare (v2, until the schema covers v3). */
    public static final int VERSION = 2;
    /** The newest version {@link #parse} reads. */
    public static final int LATEST_VERSION = 3;
    public static final int MIN_VERSION = 1;
    /** Reference values are stored as PRODUCT metrics with this prefix; the overview never lists them. */
    public static final String REFERENCE_PREFIX = "ref_";
    /** The reference metrics a v1 or v2 response may carry. */
    public static final Set<String> REFERENCE_METRICS = Set.of("store_downloads", "ads_spend");
    /** Contract v3 adds Apple Ads tap installs and Sales Reports first-time downloads. */
    public static final Set<String> REFERENCE_METRICS_V3 = Set.of("store_downloads", "ads_spend", "ads_tap_installs",
            "sales_first_time_downloads");

    private static final Logger LOG = LoggerFactory.getLogger(ProductMetricsContract.class);
    private static final String READS_VERSIONS = "Conductor reads versions 1, 2 and 3.";
    private static final int MAX_IGNORED_REFERENCES_LOGGED = 10;

    static final int MAX_METRICS = 50;
    static final int MAX_TRAILING_DAYS = 90;
    static final int DEFAULT_TRAILING_DAYS = 3;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern DIMENSION = Pattern.compile("^(source|campaign):.{1,190}$");
    /** v2 adds {@code keyword:{id}:{text}} and {@code ad:{id}:{name}}. */
    private static final Pattern DIMENSION_V2 = Pattern.compile("^((source|campaign):.{1,190}|(keyword|ad):[^:]{1,100}:.{1,190})$");
    /** v3 adds one-part dimensions: offer, link, message, intent, aso, channel and paywall. */
    private static final Pattern DIMENSION_V3 = Pattern.compile(
            "^((source|campaign):.{1,190}|(keyword|ad):[^:]{1,100}:.{1,190}"
                    + "|(offer|link|message|intent|aso|channel|paywall):.{1,190})$");
    private static final Pattern WINDOW = Pattern.compile("^[0-9]{1,3}d$");
    private static final Set<String> UNITS = Set.of("COUNT", "CURRENCY", "PERCENT");
    private static final Set<String> UNITS_V3 = Set.of("COUNT", "CURRENCY", "PERCENT", "POSITION");
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "LEVEL", "RATIO");
    private static final Set<String> DIRECTIONS = Set.of("UP", "DOWN");
    private static final Set<String> BASES = Set.of("EVENT", "COHORT");
    private static final Set<String> AREAS = Set.of("ACQUISITION", "ACTIVATION", "RETENTION", "REVENUE");
    static final int MAX_DESCRIPTION = 240;
    static final int MAX_EXCLUSIONS = 240;
    static final int MAX_RESTATEMENT_DAYS_V1 = 30;
    static final int MAX_RESTATEMENT_DAYS_V2 = 60;

    private ProductMetricsContract() {
    }

    /** What {@link #parse} read besides the pull itself; the health check shows it. */
    public record Parsed(ChannelMetricsPull pull, String timezone, LocalDate dataCompleteThrough) {}

    public static Parsed parse(ObjectMapper mapper, String body, LocalDate from, LocalDate to) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw bad("The server did not return valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw bad("The server did not return a JSON object.");
        }
        JsonNode version = root.get("contractVersion");
        if (version == null || !version.isInt()) {
            throw bad("The response has no contractVersion. " + READS_VERSIONS);
        }
        int contractVersion = version.asInt();
        if (contractVersion < MIN_VERSION || contractVersion > LATEST_VERSION) {
            throw bad("The server speaks product metrics contract version " + contractVersion + "; "
                    + READS_VERSIONS);
        }
        boolean v2 = contractVersion >= 2;
        String timezone = text(root, "timezone");
        if (timezone == null) {
            throw bad("The response has no timezone.");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw bad("The timezone '" + timezone + "' is not a valid time zone name.");
        }
        LocalDate completeThrough = date(root.get("dataCompleteThrough"), "dataCompleteThrough");
        JsonNode restatement = root.get("restatementDays");
        int maxRestatement = v2 ? MAX_RESTATEMENT_DAYS_V2 : MAX_RESTATEMENT_DAYS_V1;
        if (restatement == null || !restatement.isInt() || restatement.asInt() < 0
                || restatement.asInt() > maxRestatement) {
            throw bad("restatementDays must be a whole number from 0 to " + maxRestatement + ".");
        }

        List<MetricDefinition> definitions = definitions(root.get("metrics"), contractVersion);
        Map<String, MetricDefinition> byKey = new HashMap<>();
        definitions.forEach(d -> byKey.put(d.metric(), d));
        for (MetricDefinition d : definitions) {
            if (d.aggregation().equals("RATIO")) {
                for (String component : new String[]{d.numerator(), d.denominator()}) {
                    MetricDefinition c = byKey.get(component);
                    if (c == null) {
                        throw bad("Metric '" + d.metric() + "' is a RATIO of '" + component
                                + "', which the response does not define.");
                    }
                    if (c.aggregation().equals("RATIO")) {
                        throw bad("Metric '" + d.metric() + "' divides '" + component
                                + "', which is itself a RATIO; use SUM or LEVEL metrics.");
                    }
                }
            }
        }

        List<ChannelMetricPoint> points = new ArrayList<>();
        JsonNode pointNodes = root.get("points");
        if (pointNodes == null || !pointNodes.isArray()) {
            throw bad("The response has no points list.");
        }
        for (JsonNode p : pointNodes) {
            String metric = text(p, "metric");
            MetricDefinition def = metric == null ? null : byKey.get(metric);
            if (def == null) {
                throw bad("A point names the metric '" + metric + "', which the response does not define.");
            }
            if (def.aggregation().equals("RATIO")) {
                throw bad("Metric '" + metric + "' is a RATIO and is computed by Conductor; send its numerator and"
                        + " denominator instead of points for it.");
            }
            LocalDate day = date(p.get("day"), "a point's day");
            BigDecimal value = number(p.get("value"), "The value of " + metric + " on " + day);
            String dimension = dimension(p.get("dimension"), contractVersion);
            Long sampleSize = sampleSize(p.get("sampleSize"));
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            points.add(new ChannelMetricPoint(metric, dimension, day, value, sampleSize));
        }

        JsonNode referenceNodes = root.get("reference");
        if (referenceNodes != null && !referenceNodes.isNull()) {
            if (!referenceNodes.isArray()) {
                throw bad("reference must be a list.");
            }
            Set<String> understood = contractVersion >= 3 ? REFERENCE_METRICS_V3 : REFERENCE_METRICS;
            Set<String> ignored = new TreeSet<>();
            for (JsonNode r : referenceNodes) {
                String metric = text(r, "metric");
                if (metric == null) {
                    throw bad("A reference value names '" + metric + "'; expected one of "
                            + understood.stream().sorted().toList() + ".");
                }
                if (!understood.contains(metric)) {
                    ignored.add(metric);
                    continue;
                }
                LocalDate day = date(r.get("day"), "a reference value's day");
                BigDecimal value = number(r.get("value"), "The reference value of " + metric + " on " + day);
                if (dimension(r.get("dimension"), contractVersion).length() > 0) {
                    throw bad("Reference values are totals; leave dimension empty.");
                }
                if (day.isBefore(from) || day.isAfter(to)) {
                    continue;
                }
                points.add(new ChannelMetricPoint(REFERENCE_PREFIX + metric, "", day, value, null));
            }
            logIgnoredReferences(contractVersion, ignored);
        }

        Set<String> snapshots = new HashSet<>();
        definitions.stream().filter(d -> d.aggregation().equals("LEVEL")).forEach(d -> snapshots.add(d.metric()));

        // Re-read far enough back to finalise every day that is still provisional: the backend's own restatement
        // window, or the lag behind dataCompleteThrough when that is longer.
        long lag = Math.max(0, ChronoUnit.DAYS.between(completeThrough, to));
        int trailing = (int) Math.min(MAX_TRAILING_DAYS, Math.max(Math.max(1, restatement.asInt()), lag));

        ChannelMetricsPull pull = new ChannelMetricsPull(points, completeThrough.plusDays(1), definitions, trailing,
                snapshots, List.of(), contractVersion);
        return new Parsed(pull, timezone, completeThrough);
    }

    private static void logIgnoredReferences(int contractVersion, Set<String> ignored) {
        if (ignored.isEmpty()) {
            return;
        }
        String names = ignored.stream().limit(MAX_IGNORED_REFERENCES_LOGGED)
                .map(n -> (n.length() > 64 ? n.substring(0, 64) : n).replaceAll("\\p{Cntrl}", "?"))
                .toList().toString();
        LOG.info("Product metrics response (contract v{}) has reference metrics Conductor does not read; ignoring {}",
                contractVersion, names);
    }

    private static List<MetricDefinition> definitions(JsonNode nodes, int contractVersion) {
        boolean v2 = contractVersion >= 2;
        boolean v3 = contractVersion >= 3;
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            throw bad("The response defines no metrics.");
        }
        if (nodes.size() > MAX_METRICS) {
            throw bad("The response defines " + nodes.size() + " metrics; the limit is " + MAX_METRICS + ".");
        }
        List<MetricDefinition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode m : nodes) {
            String key = text(m, "key");
            if (key == null || !KEY.matcher(key).matches() || key.startsWith(REFERENCE_PREFIX)) {
                throw bad("A metric key must be lowercase letters, digits and underscores, starting with a letter"
                        + " (and not starting with ref_); got '" + key + "'.");
            }
            if (!seen.add(key)) {
                throw bad("The metric '" + key + "' is defined twice.");
            }
            String label = text(m, "label");
            if (label == null) {
                throw bad("The metric '" + key + "' has no label.");
            }
            String unit = text(m, "unit");
            if (unit == null || !(v3 ? UNITS_V3 : UNITS).contains(unit)) {
                throw bad("The metric '" + key + "' has an unknown unit '" + unit + "'; expected "
                        + (v3 ? "COUNT, CURRENCY, PERCENT or POSITION." : "COUNT, CURRENCY or PERCENT."));
            }
            String aggregation = text(m, "aggregation");
            if (aggregation == null || !AGGREGATIONS.contains(aggregation)) {
                throw bad("The metric '" + key + "' has an unknown aggregation '" + aggregation
                        + "'; expected SUM, LEVEL or RATIO.");
            }
            String numerator = text(m, "numerator");
            String denominator = text(m, "denominator");
            if (aggregation.equals("RATIO") && (numerator == null || denominator == null)) {
                throw bad("The RATIO metric '" + key + "' must name both a numerator and a denominator.");
            }
            if (!aggregation.equals("RATIO")) {
                numerator = null;
                denominator = null;
            }
            String good = text(m, "good");
            if (good == null) {
                good = "UP";
            }
            if (!DIRECTIONS.contains(good)) {
                throw bad("The metric '" + key + "' has good '" + good + "'; expected UP or DOWN.");
            }
            Integer funnelOrder = null;
            JsonNode order = m.get("funnelOrder");
            if (order != null && !order.isNull()) {
                if (!order.isInt() || order.asInt() < 1) {
                    throw bad("The metric '" + key + "' has a funnelOrder that is not a whole number of 1 or more.");
                }
                funnelOrder = order.asInt();
            }
            String basis = "EVENT";
            String area = null;
            String description = null;
            Integer headline = null;
            BigDecimal watchBandPct = null;
            String exclusions = null;
            if (v2) {
                String rawBasis = text(m, "basis");
                if (rawBasis != null) {
                    if (!BASES.contains(rawBasis)) {
                        throw bad("The metric '" + key + "' has basis '" + rawBasis + "'; expected EVENT or COHORT.");
                    }
                    basis = rawBasis;
                }
                area = text(m, "area");
                if (area != null && !AREAS.contains(area)) {
                    throw bad("The metric '" + key + "' has area '" + area
                            + "'; expected ACQUISITION, ACTIVATION, RETENTION or REVENUE.");
                }
                description = text(m, "description");
                if (description != null && description.length() > MAX_DESCRIPTION) {
                    throw bad("The description of '" + key + "' is longer than " + MAX_DESCRIPTION + " characters.");
                }
                JsonNode head = m.get("headline");
                if (head != null && !head.isNull()) {
                    if (!head.isInt() || head.asInt() < 1) {
                        throw bad("The metric '" + key + "' has a headline that is not a whole number of 1 or more.");
                    }
                    headline = head.asInt();
                }
            }
            if (v3) {
                exclusions = text(m, "exclusions");
                if (exclusions != null && exclusions.length() > MAX_EXCLUSIONS) {
                    throw bad("The exclusions of '" + key + "' are longer than " + MAX_EXCLUSIONS + " characters.");
                }
            }
            BigDecimal targetValue = null;
            String targetDirection = null;
            String targetWindow = null;
            JsonNode target = m.get("target");
            if (target != null && !target.isNull()) {
                targetValue = number(target.get("value"), "The target of " + key);
                if (targetValue.signum() < 0) {
                    throw bad("The target of '" + key + "' must be zero or more.");
                }
                targetDirection = text(target, "direction");
                if (targetDirection == null) {
                    targetDirection = good;
                }
                if (!DIRECTIONS.contains(targetDirection)) {
                    throw bad("The target of '" + key + "' has direction '" + targetDirection + "'; expected UP or DOWN.");
                }
                targetWindow = text(target, "window");
                if (targetWindow != null && !WINDOW.matcher(targetWindow).matches()) {
                    throw bad("The target window of '" + key + "' must look like 28d.");
                }
                JsonNode band = target.get("watchBandPct");
                if (v2 && band != null && !band.isNull()) {
                    watchBandPct = number(band, "The watchBandPct of the target of " + key);
                    if (watchBandPct.signum() < 0 || watchBandPct.compareTo(BigDecimal.valueOf(100)) > 0) {
                        throw bad("The watchBandPct of the target of '" + key + "' must be from 0 to 100.");
                    }
                }
            }
            out.add(new MetricDefinition(key, label, unit, aggregation, numerator, denominator, good, funnelOrder,
                    targetValue, targetDirection, targetWindow, basis, area, description, headline, watchBandPct,
                    exclusions));
        }
        return out;
    }

    /**
     * A dimension is absent (a total) or {@code source:<name>} / {@code campaign:<id>:<name>}; v2 adds
     * {@code keyword:<id>:<text>} and {@code ad:<id>:<name>}; v3 adds {@code offer:}, {@code link:},
     * {@code message:}, {@code intent:}, {@code aso:}, {@code channel:} and {@code paywall:} followed by one value.
     */
    private static String dimension(JsonNode node, int contractVersion) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual() || !allowedDimension(contractVersion).matcher(node.asText()).matches()) {
            throw bad("A dimension must be empty or start with " + dimensionPrefixes(contractVersion) + "; got '"
                    + node.asText() + "'.");
        }
        return node.asText();
    }

    private static Pattern allowedDimension(int contractVersion) {
        if (contractVersion >= 3) {
            return DIMENSION_V3;
        }
        return contractVersion >= 2 ? DIMENSION_V2 : DIMENSION;
    }

    private static String dimensionPrefixes(int contractVersion) {
        if (contractVersion >= 3) {
            return "source:, campaign:, keyword:, ad:, offer:, link:, message:, intent:, aso:, channel: or paywall:";
        }
        return contractVersion >= 2 ? "source:, campaign:, keyword: or ad:" : "source: or campaign:";
    }

    private static Long sampleSize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < 0) {
            throw bad("sampleSize must be a whole number of zero or more.");
        }
        return node.asLong();
    }

    private static BigDecimal number(JsonNode node, String what) {
        if (node == null || !node.isNumber()) {
            throw bad(what + " must be a number.");
        }
        return node.decimalValue();
    }

    private static LocalDate date(JsonNode node, String what) {
        if (node == null || !node.isTextual()) {
            throw bad("The response has no " + what + ".");
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeException e) {
            throw bad(what + " '" + node.asText() + "' is not a date (expected YYYY-MM-DD).");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }

    private static ChannelMetricsSourceException bad(String message) {
        return ChannelMetricsSourceException.setupRequired(message);
    }
}
