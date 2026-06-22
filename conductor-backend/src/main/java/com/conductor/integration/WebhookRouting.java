package com.conductor.integration;

/**
 * How the generic webhook receiver resolves the target connection(s) for an inbound delivery.
 *
 * <p>A connector returns a {@link #configSelector(String, String)} when the connection is identified by a
 * value inside the payload rather than by the URL — e.g. an app-level GitHub App delivers ALL events for ALL
 * installations to one endpoint, so the connector points the receiver at
 * {@code config_json->>'installationId' = <id>}. The receiver then resolves the connection(s) via
 * {@code ConnectionRepository.findByConnectorIdAndConfigValue(connectorId, configKey, configValue)}, which may
 * return N connections across projects (multi-project fan-out).
 *
 * <p>A {@code null} routing (the SPI default) means "use the {@code connectionId} from the URL" — the
 * per-connection model where the URL itself selects the instance.
 *
 * <p>The connector NEVER touches persistence here: it only describes WHICH config key/value to match. The
 * receiver owns the lookup.
 */
public record WebhookRouting(String configKey, String configValue) {

    /** Route to every connection whose {@code config_json->>configKey == configValue}. */
    public static WebhookRouting configSelector(String configKey, String configValue) {
        return new WebhookRouting(configKey, configValue);
    }
}
