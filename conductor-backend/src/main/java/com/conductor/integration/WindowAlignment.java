package com.conductor.integration;

/**
 * How an {@link IngestWindowSpec} slice is aligned to calendar boundaries — e.g. {@code ISO_WEEK} for
 * a weekly digest that should always start on a Monday, regardless of when the pull actually runs.
 */
public enum WindowAlignment { DAY, ISO_WEEK, MONTH }
