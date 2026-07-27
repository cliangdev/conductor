package com.conductor.integration.ingest.digest;

/**
 * How one dimension row changed vs. the baseline, per {@link MetricsChangeDetector}. {@code ENTERED}/
 * {@code EXITED} are baseline-membership changes (not in the wider persisted baseline at all / dropped
 * out of what was previously the top-N); {@code RANK_MOVED} is a same-membership rank shift of at
 * least {@code minRankMove}; {@code ROSE}/{@code FELL} are same-rank-ish value moves clearing
 * {@code minAbsolute}/{@code minRelative}.
 */
public enum MoverKind { ENTERED, EXITED, ROSE, FELL, RANK_MOVED }
