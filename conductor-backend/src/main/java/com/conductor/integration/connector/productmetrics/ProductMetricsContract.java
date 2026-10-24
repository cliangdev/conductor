package com.conductor.integration.connector.productmetrics;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.MetricDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.regex.Pattern;

/**
 * Reads a response of the product metrics contract, v1 or v2 ({@code docs/integrations-product-metrics.md},
 * {@code docs/integrations-product-metrics.schema.json}) into a {@link ChannelMetricsPull}.
 *
 * <p>Strict on purpose: a response that breaks the contract is rejected whole with a plain message, never
 * half-applied, so a mistake in the product backend shows up on the connection instead of as quietly wrong
 * numbers. Unknown extra fields are ignored. Version 2 only adds optional fields and wider limits (cohort
 * basis, area, description, headline, a watch band, {@code keyword:}/{@code ad:} dimensions, restatement
 * up to 60 days); a v1 response reads exactly as before, and v2 fields on it are ignored.
 */
public final class ProductMetricsContract {

    public static final int VERSION = 2;
    public static final int MIN_VERSION = 1;
    /** Reference values are stored as PRODUCT metrics with this prefix; the overview never lists them. */
    public static final String REFERENCE_PREFIX = "ref_";
    public static final Set<String> REFERENCE_METRICS = Set.of("store_downloads", "ads_spend");

    static final int MAX_METRICS = 50;
    static final int MAX_TRAILING_DAYS = 90;
    static final int DEFAULT_TRAILING_DAYS = 3;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern DIMENSION = Pattern.compile("^(source|campaign):.{1,190}$");
    /** v2 adds {@code keyword:{id}:{text}} and {@code ad:{id}:{name}}. */
    private static final Pattern DIMENSION_V2 = Pattern.compile("^((source|campaign):.{1,190}|(keyword|ad):[^:]{1,100}:.{1,190})$");
    private static final Pattern WINDOW = Pattern.compile("^[0-9]{1,3}d$");
    private static final Set<String> UNITS = Set.of("COUNT", "CURRENCY", "PERCENT");
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "LEVEL", "RATIO");
    private static final Set<String> DIRECTIONS = Set.of("UP", "DOWN");
    private static final Set<String> BASES = Set.of("EVENT", "COHORT");
    private static final Set<String> AREAS = Set.of("ACQUISITION", "ACTIVATION", "RETENTION", "REVENUE");
    static final int MAX_DESCRIPTION = 240;
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
            throw bad("The response has no contractVersion. Conductor reads versions " + MIN_VERSION + " and "
                    + VERSION + ".");
        }
        if (version.asInt() < MIN_VERSION || version.asInt() > VERSION) {
            throw bad("The server speaks product metrics contract version " + version.asInt()
                    + "; Conductor reads versions " + MIN_VERSION + " and " + VERSION + ".");
        }
        boolean v2 = version.asInt() >= 2;
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

        List<MetricDefinition> definitions = definitions(root.get("metrics"), v2);
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
            String dimension = dimension(p.get("dimension"), v2);
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
            for (JsonNode r : referenceNodes) {
                String metric = text(r, "metric");
                if (metric == null || !REFERENCE_METRICS.contains(metric)) {
                    throw bad("A reference value names '" + metric + "'; expected one of "
                            + REFERENCE_METRICS.stream().sorted().toList() + ".");
                }
                LocalDate day = date(r.get("day"), "a reference value's day");
                BigDecimal value = number(r.get("value"), "The reference value of " + metric + " on " + day);
                if (dimension(r.get("dimension"), v2).length() > 0) {
                    throw bad("Reference values are totals; leave dimension empty.");
                }
                if (day.isBefore(from) || day.isAfter(to)) {
                    continue;
                }
                points.add(new ChannelMetricPoint(REFERENCE_PREFIX + metric, "", day, value, null));
            }
        }

        Set<String> snapshots = new HashSet<>();
        definitions.stream().filter(d -> d.aggregation().equals("LEVEL")).forEach(d -> snapshots.add(d.metric()));

        // Re-read far enough back to finalise every day that is still provisional: the backend's own restatement
        // window, or the lag behind dataCompleteThrough when that is longer.
        long lag = Math.max(0, ChronoUnit.DAYS.between(completeThrough, to));
        int trailing = (int) Math.min(MAX_TRAILING_DAYS, Math.max(Math.max(1, restatement.asInt()), lag));

        ChannelMetricsPull pull = new ChannelMetricsPull(points, completeThrough.plusDays(1), definitions, trailing,
                snapshots, List.of(), version.asInt());
        return new Parsed(pull, timezone, completeThrough);
    }

    private static List<MetricDefinition> definitions(JsonNode nodes, boolean v2) {
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
            if (unit == null || !UNITS.contains(unit)) {
                throw bad("The metric '" + key + "' has an unknown unit '" + unit + "'; expected COUNT, CURRENCY or PERCENT.");
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
                    targetValue, targetDirection, targetWindow, basis, area, description, headline, watchBandPct));
        }
        return out;
    }

    /**
     * A dimension is absent (a total) or {@code source:<name>} / {@code campaign:<id>:<name>}; v2 adds
     * {@code keyword:<id>:<text>} and {@code ad:<id>:<name>}.
     */
    private static String dimension(JsonNode node, boolean v2) {
        if (node == null || node.isNull()) {
            return "";
        }
        Pattern allowed = v2 ? DIMENSION_V2 : DIMENSION;
        if (!node.isTextual() || !allowed.matcher(node.asText()).matches()) {
            throw bad("A dimension must be empty or start with "
                    + (v2 ? "source:, campaign:, keyword: or ad:" : "source: or campaign:")
                    + "; got '" + node.asText() + "'.");
        }
        return node.asText();
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
