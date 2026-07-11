package com.conductor.integration.connector.gcp.model;

import java.util.List;

/** Response from GET /v1/{parent}/repositories. */
public record ArListRepositoriesResponse(List<ArRepository> repositories, String nextPageToken) {}
