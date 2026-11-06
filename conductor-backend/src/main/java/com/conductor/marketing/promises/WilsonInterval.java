package com.conductor.marketing.promises;

/** The 95% Wilson score interval for a proportion: honest at small samples, where a plain +/- band is not. */
public final class WilsonInterval {

    static final double Z_95 = 1.96;

    /** Both ends are fractions within 0 to 1. */
    public record Range(double low, double high) {}

    private WilsonInterval() {}

    /** The interval for {@code successes} out of {@code n}; {@code n} must be above 0 and at least {@code successes}. */
    public static Range of(double successes, double n) {
        if (n <= 0 || successes < 0 || successes > n) {
            throw new IllegalArgumentException("Need 0 <= successes <= n and n > 0, got " + successes + " of " + n);
        }
        double p = successes / n;
        double z2 = Z_95 * Z_95;
        double denominator = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denominator;
        double half = Z_95 * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator;
        return new Range(Math.max(0.0, center - half), Math.min(1.0, center + half));
    }
}
