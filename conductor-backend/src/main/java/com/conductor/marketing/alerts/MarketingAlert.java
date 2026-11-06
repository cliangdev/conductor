package com.conductor.marketing.alerts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * One condition that holds right now and is worth telling a person about.
 *
 * <p>{@code key} names the condition across evaluations ({@code mismatch:ADS:installs:BACKEND},
 * {@code stale:STORE}, {@code stop_rule:<ruleId>}, {@code off_track:ADS:installs}); the same key is the same
 * condition. {@code figures} are the numbers that say how bad it is (a mismatch's gap in percentage points, a stop
 * rule's observed value); they are what {@link #fingerprint} stores and {@link #sameCondition} compares, so a
 * condition that merely drifts a little as the window rolls forward is still the same condition. A condition with
 * no figures (a stale channel) is the same for as long as it holds.
 *
 * @param channel the {@code MetricChannel} name the condition is about, or null
 * @param tolerance the least a figure must move, in its own units, to count as a different condition; a figure must
 *                  also move by more than {@link #RELATIVE_TOLERANCE} of the figure last posted
 */
record MarketingAlert(String key, Kind kind, String channel, String title, String text, List<Double> figures,
                      double tolerance) {

    /** A figure that moves by no more than this fraction of the last posted one has not moved meaningfully. */
    static final double RELATIVE_TOLERANCE = 0.10;
    private static final String NO_FIGURES = "-";
    private static final String FIGURE_SEPARATOR = "|";

    enum Kind {
        MISMATCH("mismatch:"), STALE("stale:"), STOP_RULE("stop_rule:"), OFF_TRACK("off_track:");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        String keyOf(String... parts) {
            return prefix + String.join(":", parts);
        }

        /** The kind a stored key belongs to, or null for a key this code does not know. */
        static Kind ofKey(String key) {
            for (Kind kind : values()) {
                if (key != null && key.startsWith(kind.prefix)) {
                    return kind;
                }
            }
            return null;
        }
    }

    MarketingAlert {
        figures = figures == null ? List.of() : List.copyOf(figures);
    }

    /** The rounded figures, joined; what is stored and shown as "what this condition looked like when posted". */
    String fingerprint() {
        if (figures.isEmpty()) {
            return NO_FIGURES;
        }
        List<String> parts = new ArrayList<>();
        for (double figure : figures) {
            parts.add(BigDecimal.valueOf(figure).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
                    .toPlainString());
        }
        return String.join(FIGURE_SEPARATOR, parts);
    }

    /**
     * Whether this is still the condition that was posted with {@code posted} as its fingerprint: every figure is
     * within the tolerance of the posted one. Compared with the figures last <em>posted</em>, not the last seen,
     * so a slow drift cannot creep past the tolerance one small step at a time without ever being said.
     */
    boolean sameCondition(String posted) {
        if (posted == null) {
            return false;
        }
        if (posted.equals(fingerprint())) {
            return true;
        }
        List<Double> before = parse(posted);
        if (before == null || before.size() != figures.size() || figures.isEmpty()) {
            return false;
        }
        for (int i = 0; i < figures.size(); i++) {
            double allowed = Math.max(tolerance, RELATIVE_TOLERANCE * Math.abs(before.get(i)));
            if (Math.abs(figures.get(i) - before.get(i)) > allowed) {
                return false;
            }
        }
        return true;
    }

    private static List<Double> parse(String fingerprint) {
        if (NO_FIGURES.equals(fingerprint)) {
            return List.of();
        }
        List<Double> parsed = new ArrayList<>();
        for (String part : fingerprint.split("\\" + FIGURE_SEPARATOR, -1)) {
            try {
                parsed.add(Double.parseDouble(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parsed;
    }
}
