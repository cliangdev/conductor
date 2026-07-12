package com.conductor.workflow.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Null-value-tolerant, order-preserving defensive copies for the records in this package.
 *
 * <p>{@code Map.copyOf}/{@code List.copyOf} throw {@code NullPointerException} on a null value
 * (not just a null key/element) — but SnakeYAML legitimately produces a null value for any
 * empty-valued key (e.g. a step's {@code body:} with nothing after the colon), which the old
 * Map-walking code always passed through untouched. These helpers preserve that tolerance.
 */
final class Copies {

    private Copies() {
    }

    static <K, V> Map<K, V> map(Map<K, V> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <T> List<T> list(List<T> source) {
        return source == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
