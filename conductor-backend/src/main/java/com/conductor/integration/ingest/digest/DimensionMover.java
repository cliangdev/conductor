package com.conductor.integration.ingest.digest;

/**
 * One dimension-row change {@link MetricsChangeDetector} found material — a computed diff, never the
 * full current/baseline row list (see {@code DigestPayloadBuilder}'s "no raw dimension data" contract).
 * {@code previousRank}/{@code currentRank} and {@code previousValue}/{@code currentValue} are null on
 * whichever side doesn't apply ({@code ENTERED} has no previous rank/value, {@code EXITED} has no
 * current rank/value).
 */
public record DimensionMover(
        String id,
        MoverKind kind,
        Integer previousRank,
        Integer currentRank,
        Double previousValue,
        Double currentValue) {
}
