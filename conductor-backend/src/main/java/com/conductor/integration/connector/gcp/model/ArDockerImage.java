package com.conductor.integration.connector.gcp.model;

import java.util.List;

/**
 * One entry from Artifact Registry {@code dockerImages.list}.
 * {@code name} is {@code projects/*&#47;locations/*&#47;repositories/*&#47;dockerImages/IMAGE_PATH@sha256:DIGEST}.
 */
public record ArDockerImage(String name, String uri, List<String> tags) {}
