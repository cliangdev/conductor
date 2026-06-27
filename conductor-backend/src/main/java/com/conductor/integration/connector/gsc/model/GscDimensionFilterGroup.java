package com.conductor.integration.connector.gsc.model;

import java.util.List;

/** A filter group in a searchAnalytics/query request body. */
public record GscDimensionFilterGroup(String groupType, List<GscDimensionFilter> filters) {}
