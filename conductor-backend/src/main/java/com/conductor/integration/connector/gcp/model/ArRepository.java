package com.conductor.integration.connector.gcp.model;

/** One entry from Artifact Registry {@code repositories.list}. {@code name} is {@code projects/*&#47;locations/*&#47;repositories/ID}. */
public record ArRepository(String name, String format) {}
