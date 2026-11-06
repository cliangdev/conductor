package com.conductor.marketing.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decides whether a stored benchmark may be judged against a report metric. Two definitions are comparable only
 * when numerator, denominator and traffic source all match; a null source type means {@code ALL}. A benchmark
 * with no numerator has no definition and is never judged. Pure: no I/O, no clock.
 */
public final class BenchmarkComparability {

    /** The closed list of measure names a numerator or denominator may use (contract C2). */
    public static final List<String> MEASURES = List.of(
            "impressions", "unique_impressions", "page_views", "unique_page_views", "first_time_downloads",
            "redownloads", "total_downloads", "downloads_of_record", "tap_installs", "tap_new_downloads",
            "view_through_installs", "taps", "spend", "visitors", "sessions", "pageviews", "conversion_sessions",
            "conversion_events", "clicks", "followers", "posts", "likes", "channel_views", "website_clicks",
            "rating_count", "rating_avg", "position");

    /** The traffic sources a definition may be limited to; {@code ALL} is the default. */
    public static final List<String> SOURCE_TYPES = List.of(
            "ALL", "APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER", "WEB_REFERRER", "APP_CLIP",
            "INSTITUTIONAL_PURCHASE", "OTHER");

    /** The prefix that points a numerator or denominator at a PRODUCT feed metric. */
    public static final String PRODUCT_PREFIX = "product:";

    /** The key after {@link #PRODUCT_PREFIX}. */
    public static final Pattern PRODUCT_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final String ALL = "ALL";
    private static final String DEFINITION_MISSING = "definition missing";
    private static final String METRIC_DEFINITION_MISSING = "metric definition missing";
    private static final String NO_DEFINITION = "no definition";

    private static final Map<String, String> MEASURE_WORDS = Map.of("first_time_downloads", "first-time downloads");
    private static final Map<String, String> SOURCE_WORDS = Map.of(
            "ALL", "all sources",
            "APP_STORE_SEARCH", "App Store search",
            "APP_STORE_BROWSE", "App Store browse",
            "APP_REFERRER", "app referrers",
            "WEB_REFERRER", "web referrers");

    private BenchmarkComparability() {}

    /** {@code reason} is null when comparable, otherwise a plain sentence a person can act on. */
    public record Result(boolean comparable, String reason) {}

    public static Result compare(String bNum, String bDen, String bSource, String mNum, String mDen, String mSource) {
        String benchmarkNumerator = measure(bNum);
        if (benchmarkNumerator == null) {
            return new Result(false, DEFINITION_MISSING);
        }
        String metricNumerator = measure(mNum);
        if (metricNumerator == null) {
            return new Result(false, METRIC_DEFINITION_MISSING);
        }
        String benchmarkDenominator = measure(bDen);
        String metricDenominator = measure(mDen);
        String benchmarkSourceType = sourceType(bSource);
        String metricSourceType = sourceType(mSource);
        boolean same = benchmarkNumerator.equals(metricNumerator)
                && Objects.equals(benchmarkDenominator, metricDenominator)
                && benchmarkSourceType.equals(metricSourceType);
        if (same) {
            return new Result(true, null);
        }
        return new Result(false, "benchmark: " + describe(benchmarkNumerator, benchmarkDenominator, benchmarkSourceType)
                + "; metric: " + describe(metricNumerator, metricDenominator, metricSourceType));
    }

    /**
     * The definition in words, e.g. {@code total downloads / unique impressions (App Store search)}. A PRODUCT feed
     * measure keeps its key verbatim: {@code trial_starts (product feed)}. No numerator reads {@code no definition}.
     */
    public static String describe(String numerator, String denominator, String sourceType) {
        String num = measure(numerator);
        if (num == null) {
            return NO_DEFINITION;
        }
        String den = measure(denominator);
        String ratio = den == null ? measureWords(num) : measureWords(num) + " / " + measureWords(den);
        return ratio + " (" + sourceWords(sourceType(sourceType)) + ")";
    }

    /** True when {@code value} (already trimmed and lower-cased) is a vocabulary measure or a valid product key. */
    static boolean isMeasure(String value) {
        if (value == null) {
            return false;
        }
        if (value.startsWith(PRODUCT_PREFIX)) {
            return PRODUCT_KEY.matcher(value.substring(PRODUCT_PREFIX.length())).matches();
        }
        return MEASURES.contains(value);
    }

    /** Trimmed and lower-cased; blank is null. */
    static String measure(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Trimmed and upper-cased; blank is null (not yet defaulted to ALL). */
    static String sourceTypeOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String sourceType(String raw) {
        String normalised = sourceTypeOrNull(raw);
        return normalised == null ? ALL : normalised;
    }

    private static String measureWords(String measure) {
        if (measure.startsWith(PRODUCT_PREFIX)) {
            return measure.substring(PRODUCT_PREFIX.length()) + " (product feed)";
        }
        return MEASURE_WORDS.getOrDefault(measure, measure.replace('_', ' '));
    }

    private static String sourceWords(String sourceType) {
        return SOURCE_WORDS.getOrDefault(sourceType, sourceType.toLowerCase(Locale.ROOT).replace('_', ' '));
    }
}
