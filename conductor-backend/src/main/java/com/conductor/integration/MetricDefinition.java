package com.conductor.integration;

import java.math.BigDecimal;

/**
 * One metric a feed declares about itself (see {@code docs/integrations-product-metrics.md}). {@code aggregation}
 * is SUM, LEVEL or RATIO; a RATIO names a numerator and a denominator that are other metrics of the same feed.
 * The last five fields are contract v2: {@code basis} (EVENT or COHORT), {@code area}, {@code description},
 * {@code headline} and the target's {@code watchBandPct}; all but basis may be null.
 */
public record MetricDefinition(String metric, String label, String unit, String aggregation, String numerator,
                               String denominator, String good, Integer funnelOrder, BigDecimal targetValue,
                               String targetDirection, String targetWindow, String basis, String area,
                               String description, Integer headline, BigDecimal watchBandPct) {

    /** A v1 definition: event basis and none of the v2 fields. */
    public MetricDefinition(String metric, String label, String unit, String aggregation, String numerator,
                            String denominator, String good, Integer funnelOrder, BigDecimal targetValue,
                            String targetDirection, String targetWindow) {
        this(metric, label, unit, aggregation, numerator, denominator, good, funnelOrder, targetValue,
                targetDirection, targetWindow, "EVENT", null, null, null, null);
    }
}
