package com.conductor.integration.connector.posthog.model;

import java.util.List;

/** Response from a HogQL query: row-matrix results with named columns. */
public record PostHogHogQLResponse(List<List<Object>> results, List<String> columns) {}
