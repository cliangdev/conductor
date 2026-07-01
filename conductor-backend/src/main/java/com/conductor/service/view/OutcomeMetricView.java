package com.conductor.service.view;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The append-only outcome-metric series on a Work Item plus the metric's name/unit/direction (from the bound
 * Workflow definition). The nested {@link Observation} is also the JSON persistence shape stored on the item.
 */
public record OutcomeMetricView(
        List<Observation> observations,
        String name,
        String unit,
        String direction) {

    /** A single metric observation. Field names are the persisted JSON shape — do not rename. */
    public record Observation(Double value, OffsetDateTime observedAt, String note) {
    }
}
