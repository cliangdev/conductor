package com.conductor.integration;

/**
 * What a connector can do. Derived from which capability sub-interface it implements
 * ({@link FetchConnector}, {@link WebhookConnector}, {@link ActionConnector}, {@link IngestConnector}) —
 * surfaced to the hub UI so it knows whether to render an OAuth redirect, an API-key modal, or a webhook
 * setup panel.
 *
 * <p><b>{@code INGEST} is NOT what gates Knowledge Center feed availability</b> — that's
 * {@code getToolSpec().ingest()} (see {@link IngestSpec}), because a {@code SNAPSHOT}-mode feed can be
 * served by bridging a plain {@link FetchConnector} without implementing {@link IngestConnector} at
 * all. This capability only reflects whether a connector implements the interface, which a
 * {@code WINDOW}-mode feed does require.
 */
public enum Capability { FETCH, WEBHOOK, ACTION, CREDENTIAL, INGEST }
