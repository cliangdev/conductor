package com.conductor.signal;

/**
 * Identifies what produced a {@link Signal} -- e.g. {@code kind="work_item", id="<work item id>"}
 * or {@code kind="github", id="<repo full name>"}. Purely descriptive metadata for logging/debugging
 * and future disposition-policy rules; subscribers should not need to parse it to decide interest,
 * that's what {@link Signal#type()} and {@link SignalGlob} are for.
 */
public record SignalOrigin(String kind, String id) {
}
