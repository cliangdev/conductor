package com.conductor.integration;

import java.math.BigDecimal;

/**
 * One metric a feed declares about itself (see {@code docs/integrations-product-metrics.md}). {@code aggregation}
 * is SUM, LEVEL or RATIO; a RATIO names a numerator and a denominator that are other metrics of the same feed.
 */
public record MetricDefinition(String metric, String label, String unit, String aggregation, String numerator,
                               String denominator, String good, Integer funnelOrder, BigDecimal targetValue,
                               String targetDirection, String targetWindow) {}
