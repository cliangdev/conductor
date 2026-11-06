package com.conductor.marketing.growth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What changed on a watched app's listing between two weekly snapshots, as short plain lines
 * ({@code price 0.99 -> 1.99}). A field is compared only when both snapshots have it: a value the public API
 * did not return in one of the weeks is unknown, not a change. Two snapshots of different App Store storefronts are
 * never compared: a price or a rating count read in another country is not the same number.
 */
public final class CompetitorChanges {

    private CompetitorChanges() {
    }

    /** One weekly reading, as the view and the diff see it. */
    public record SnapshotView(LocalDate capturedOn, String country, BigDecimal price, String currency,
                               BigDecimal ratingAvg, Long ratingCount, String version, String title, String subtitle,
                               String descriptionHash) {

        static SnapshotView of(MarketingAppSnapshot s) {
            return new SnapshotView(s.getCapturedOn(), s.getCountry(), s.getPrice(), s.getCurrency(),
                    s.getRatingAvg(), s.getRatingCount(), s.getVersion(), s.getTitle(), s.getSubtitle(),
                    s.getDescriptionHash());
        }
    }

    /**
     * Changes from {@code previous} to {@code latest}; empty when either is missing, they are from different
     * storefronts, or nothing differs.
     */
    public static List<String> diff(SnapshotView previous, SnapshotView latest) {
        List<String> changes = new ArrayList<>();
        if (!comparable(previous, latest)) {
            return changes;
        }
        if (differs(previous.price(), latest.price())) {
            boolean showCurrency = differs(previous.currency(), latest.currency());
            changes.add("price " + price(previous, showCurrency) + " -> " + price(latest, showCurrency));
        }
        if (differs(previous.ratingAvg(), latest.ratingAvg())) {
            changes.add("rating " + plain(previous.ratingAvg()) + " -> " + plain(latest.ratingAvg()));
        }
        if (differs(previous.ratingCount(), latest.ratingCount())) {
            changes.add("ratings " + previous.ratingCount() + " -> " + latest.ratingCount());
        }
        if (differs(previous.version(), latest.version())) {
            changes.add("version " + previous.version() + " -> " + latest.version());
        }
        if (differs(previous.title(), latest.title())) {
            changes.add("title " + previous.title() + " -> " + latest.title());
        }
        if (differs(previous.subtitle(), latest.subtitle())) {
            changes.add("subtitle " + previous.subtitle() + " -> " + latest.subtitle());
        }
        if (differs(previous.descriptionHash(), latest.descriptionHash())) {
            changes.add("description changed");
        }
        return changes;
    }

    /**
     * Weekly ratings growth: the rating count now minus the count in the snapshot before; null unless both exist,
     * both have a count and they are from the same storefront.
     */
    public static Long weeklyGrowth(SnapshotView previous, SnapshotView latest) {
        if (!comparable(previous, latest) || previous.ratingCount() == null || latest.ratingCount() == null) {
            return null;
        }
        return latest.ratingCount() - previous.ratingCount();
    }

    /** Both snapshots exist and, where both say which storefront, it is the same one. */
    private static boolean comparable(SnapshotView previous, SnapshotView latest) {
        return previous != null && latest != null
                && (previous.country() == null || latest.country() == null
                || previous.country().equalsIgnoreCase(latest.country()));
    }

    private static boolean differs(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) != 0;
        }
        return !Objects.equals(a, b);
    }

    /** Two decimals, with the currency code only when the two weeks quote different currencies. */
    private static String price(SnapshotView s, boolean showCurrency) {
        String amount = s.price().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return showCurrency && s.currency() != null ? amount + " " + s.currency() : amount;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
