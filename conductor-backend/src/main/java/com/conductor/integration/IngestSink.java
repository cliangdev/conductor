package com.conductor.integration;

/**
 * Where a feed's pulls land.
 *
 * <p>{@link #KNOWLEDGE} is the default and what every feed did before: items go to the Knowledge Center
 * (directly, or through the metrics digest pipeline when the spec declares a digest). {@link #POST_METRICS}
 * is the publishing pipeline's own sink: the pull reads performance numbers for the connection's published
 * posts and files them as {@code post_publish_target_metric} rows, producing no items at all.
 */
public enum IngestSink {
    KNOWLEDGE,
    POST_METRICS
}
