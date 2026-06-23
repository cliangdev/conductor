package com.conductor.integration;

/**
 * Static descriptor of a connector type for the hub UI. Auth type and config live on
 * {@link ConnectorSpec}; capabilities are derived from the implemented sub-interfaces.
 */
public record ConnectorMetadata(String id, String name, ConnectorCategory category,
                                String description, String iconLabel) {}
