package com.conductor.integration.connector.gsc.model;

/** One filter entry within a GscDimensionFilterGroup. */
public record GscDimensionFilter(String dimension, String operator, String expression) {}
