package com.conductor.integration;

import java.time.Instant;

/** A half-open time slice {@code [start, end)} requested from an {@link IngestConnector} pull. */
public record IngestWindow(Instant start, Instant end) {
}
