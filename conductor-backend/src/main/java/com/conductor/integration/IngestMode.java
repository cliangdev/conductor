package com.conductor.integration;

/**
 * How an {@link IngestSpec} feed pulls data. {@code SNAPSHOT} takes whatever the connector's latest
 * fetch/pull returns as-is (a {@link FetchConnector}'s single {@code ConnectorData} snapshot, or one
 * {@link IngestConnector} pull with no window semantics). {@code WINDOW} asks an {@link IngestConnector}
 * for a specific {@link IngestWindow} slice — structurally impossible for a connector that only
 * implements {@link FetchConnector}, since {@code fetchData} takes no window/cursor/params at all.
 */
public enum IngestMode { SNAPSHOT, WINDOW }
