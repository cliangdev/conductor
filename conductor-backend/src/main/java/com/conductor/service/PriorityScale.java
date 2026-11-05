package com.conductor.service;

import com.conductor.exception.BusinessException;

/**
 * The P0..P3 priority scale and its storage form. A Work Item stores 0..3 ({@code work_items.priority},
 * null = none); the API speaks the labels. P0 is the most urgent, so it also sorts first.
 */
public final class PriorityScale {

    public static final int MIN = 0;
    public static final int MAX = 3;

    private PriorityScale() {
    }

    /** {@code "P1"} -> 1. A blank label is "no priority" and parses to null; anything else must be P0..P3. */
    public static Integer parse(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.length() == 2 && (trimmed.charAt(0) == 'P' || trimmed.charAt(0) == 'p')) {
            int value = trimmed.charAt(1) - '0';
            if (value >= MIN && value <= MAX) {
                return value;
            }
        }
        throw new BusinessException("priority must be one of P0, P1, P2, P3 (or empty to clear it), got '"
                + trimmed + "'");
    }

    /** 1 -> {@code "P1"}; null stays null. */
    public static String label(Integer priority) {
        return priority == null ? null : "P" + priority;
    }
}
