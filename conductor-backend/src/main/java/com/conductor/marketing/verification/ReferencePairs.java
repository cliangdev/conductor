package com.conductor.marketing.verification;

import com.conductor.integration.MetricChannel;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The channel metrics a product backend also reports, and how the two are compared. A pair is a channel
 * metric (read from its own connector) and the product feed's {@code ref_} metric for the same thing. Only
 * days both sides have settled are compared, so a day one side is still restating, or has not reached yet,
 * never reads as a gap.
 */
public final class ReferencePairs {

    /** The channel metric key and the stored name of the product feed's reference for it. */
    public record Pair(MetricChannel channel, String metric, String reference) {}

    public static final List<Pair> ALL = List.of(
            new Pair(MetricChannel.ADS, "spend", "ref_ads_spend"),
            new Pair(MetricChannel.ADS, "installs", "ref_ads_tap_installs"),
            new Pair(MetricChannel.STORE, "downloads", "ref_store_downloads"),
            new Pair(MetricChannel.STORE, "downloads_of_record", "ref_sales_first_time_downloads"));

    /** Both sides' sums over the days they have in common, and the first and last of those days. */
    public record Comparison(double ownSum, double referenceSum, LocalDate first, LocalDate last, int days) {}

    private ReferencePairs() {}

    public static List<Pair> forChannel(MetricChannel channel) {
        return ALL.stream().filter(p -> p.channel() == channel).toList();
    }

    public static Optional<Pair> find(MetricChannel channel, String metric) {
        return ALL.stream().filter(p -> p.channel() == channel && p.metric().equals(metric)).findFirst();
    }

    /** The stored names of every reference metric a product feed may send. */
    public static List<String> referenceMetrics() {
        return ALL.stream().map(Pair::reference).toList();
    }

    /**
     * Sums both series over the days in {@code from}..{@code to} that each has a value for. Empty when they
     * share no such day: there is nothing to compare, which is not the same as agreeing.
     */
    public static Optional<Comparison> compare(Map<LocalDate, Double> own, Map<LocalDate, Double> reference,
                                               LocalDate from, LocalDate to) {
        double ownSum = 0;
        double referenceSum = 0;
        int days = 0;
        LocalDate first = null;
        LocalDate last = null;
        for (Map.Entry<LocalDate, Double> e : new TreeMap<>(reference).entrySet()) {
            LocalDate day = e.getKey();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            Double ownValue = own.get(day);
            if (ownValue == null) {
                continue;
            }
            ownSum += ownValue;
            referenceSum += e.getValue();
            days++;
            first = first == null ? day : first;
            last = day;
        }
        return days == 0 ? Optional.empty() : Optional.of(new Comparison(ownSum, referenceSum, first, last, days));
    }
}
