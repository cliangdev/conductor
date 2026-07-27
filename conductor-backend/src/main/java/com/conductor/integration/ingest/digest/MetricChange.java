package com.conductor.integration.ingest.digest;

/**
 * One metric's period-over-period change report from {@link MetricsChangeDetector}, material or not —
 * {@code material == false} entries are still included in the (later) narrator payload so it has
 * context ("position was flat") it must not lead with.
 */
public record MetricChange(
        String key,
        double value,
        double previousLast,
        double delta,
        boolean material,
        boolean lowConfidence,
        double ewma,
        double ewmVar) {
}
