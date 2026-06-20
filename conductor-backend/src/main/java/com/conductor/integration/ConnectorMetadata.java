package com.conductor.integration;

public record ConnectorMetadata(String id, String name, ConnectorCategory category, AuthType authType, String description, String iconLabel) {}
