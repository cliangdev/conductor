package com.conductor.integration;

/**
 * Static descriptor of a connector type for the hub UI. Auth type and config live on
 * {@link ConnectorSpec}; capabilities are derived from the implemented sub-interfaces.
 *
 * @param builtIn true for a connector every project is provisioned into automatically (no
 *                credential, no Integrations "Connect" flow — e.g. {@code conductor-marketing}).
 *                Defaults to {@code false} via the five-arg constructor every pre-existing connector
 *                already calls.
 */
public record ConnectorMetadata(String id, String name, ConnectorCategory category,
                                String description, String iconLabel, boolean builtIn) {

    public ConnectorMetadata(String id, String name, ConnectorCategory category,
                             String description, String iconLabel) {
        this(id, name, category, description, iconLabel, false);
    }
}
