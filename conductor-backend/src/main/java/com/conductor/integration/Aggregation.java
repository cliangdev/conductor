package com.conductor.integration;

/** How a {@link MetricSpec} rolls up raw series rows into one value per period. */
public enum Aggregation { SUM, MEAN, WEIGHTED_MEAN, LAST, RATIO }
