package com.conductor.integration.ingest;

/**
 * Lifecycle of a {@link ConnectorFeed} pull binding. {@code SETUP_REQUIRED} mirrors
 * {@code ConnectorHealth#SETUP_REQUIRED} -- the underlying connection needs re-auth before pulls can
 * resume. {@code DEAD} is a feed the scheduler has given up on (see {@code consecutiveFailures}); it
 * stops being claimed by {@link ConnectorFeedRepository#claimDue} until re-enabled.
 */
public enum ConnectorFeedStatus { ACTIVE, PAUSED, SETUP_REQUIRED, DEAD }
