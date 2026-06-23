package com.conductor.integration;

/**
 * What a connector can do. Derived from which capability sub-interface it implements
 * ({@link FetchConnector}, {@link WebhookConnector}, {@link ActionConnector}) — surfaced to the
 * hub UI so it knows whether to render an OAuth redirect, an API-key modal, or a webhook setup panel.
 */
public enum Capability { FETCH, WEBHOOK, ACTION }
