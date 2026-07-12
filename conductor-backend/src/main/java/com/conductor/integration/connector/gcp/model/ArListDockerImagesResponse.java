package com.conductor.integration.connector.gcp.model;

import java.util.List;

/** Response from GET /v1/{parent}/dockerImages. */
public record ArListDockerImagesResponse(List<ArDockerImage> dockerImages, String nextPageToken) {}
