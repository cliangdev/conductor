package com.conductor.marketing.switchback;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a switchback test measured and decided, stored as JSON when the test settles and read back as it was: a later
 * restatement of the daily rows never rewrites a verdict that was already recorded as a change.
 *
 * <p>{@code organicChangePct} is the change of the on-week organic mean against the off-week mean (negative = fell), null
 * when the off-week mean is 0 because nothing could fall. {@code verdict} is one of the MARKETING_CHANGES options:
 * worked, did_not_work, no_effect or inconclusive. {@code reason} is one sentence, used as the change's lesson.
 */
public record SwitchbackOutcome(List<Week> weeks, Means onWeeks, Means offWeeks, long n, int minN, BigDecimal lift,
                                BigDecimal organicChangePct, String verdict, String reason) {

    /** {@code weekStart} is yyyy-MM-dd. */
    public record Week(String weekStart, boolean on, BigDecimal searchDownloads, BigDecimal adsNewDownloads,
                       BigDecimal organic, BigDecimal totalInstalls) {}

    public record Means(int weeks, BigDecimal meanOrganic, BigDecimal meanTotalInstalls) {}
}
