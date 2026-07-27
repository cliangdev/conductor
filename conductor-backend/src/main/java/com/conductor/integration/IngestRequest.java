package com.conductor.integration;

/**
 * One {@link IngestConnector#pull} invocation: which declared {@link IngestSpec#id()} to pull,
 * the {@link IngestWindow} for {@code WINDOW}-mode feeds ({@code null} for {@code SNAPSHOT}-mode),
 * the connector-owned opaque {@code cursor} from the feed's last pull ({@code null} on first pull),
 * and {@code maxItems} as a soft cap the connector should respect but the platform does not enforce.
 */
public record IngestRequest(String ingestId, IngestWindow window, String cursor, int maxItems) {
}
