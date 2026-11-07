package com.conductor.marketing.switchback;

import com.conductor.marketing.switchback.SwitchbackOutcome.Means;
import com.conductor.marketing.switchback.SwitchbackOutcome.Week;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The arithmetic of settling a switchback test, with no I/O. Per week, organic search first-time downloads are the store's
 * search first-time downloads minus Apple Ads first-time downloads over the same days, floored at 0 for the week (the
 * report's organic derivation, applied per week); total installs add the Apple Ads downloads back. The means over the on
 * weeks and over the off weeks are rounded to two decimals and the rule is applied to those rounded means, so what a
 * reader sees is exactly what decided the verdict.
 *
 * <p>The verdict, once {@code n} (search first-time downloads across all weeks) reaches {@code minN}:
 * <ul>
 *   <li>{@code worked}: total installs rose by at least {@code minLift} a week and organic did not fall by more than
 *       {@code maxCannibalisationPct} percent (paid is incremental);</li>
 *   <li>{@code did_not_work}: organic fell by more than that (paid takes installs organic would have had, so it only
 *       relabels them);</li>
 *   <li>{@code no_effect}: otherwise, the lift was too small and organic held.</li>
 * </ul>
 * Below {@code minN} the verdict is {@code inconclusive} and says the n reached.
 */
final class SwitchbackEvaluator {

    static final String WORKED = "worked";
    static final String DID_NOT_WORK = "did_not_work";
    static final String NO_EFFECT = "no_effect";
    static final String INCONCLUSIVE = "inconclusive";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** One planned week with the settled sums over its seven days. */
    record WeekInput(LocalDate weekStart, boolean on, BigDecimal searchDownloads, BigDecimal adsNewDownloads) {}

    private SwitchbackEvaluator() {}

    static SwitchbackOutcome evaluate(List<WeekInput> inputs, SwitchbackRule rule, int minN, String keywordText) {
        List<Week> weeks = inputs.stream().map(SwitchbackEvaluator::figures).toList();
        Means on = means(weeks, true);
        Means off = means(weeks, false);
        long n = weeks.stream().map(Week::searchDownloads).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP).longValue();

        BigDecimal lift = on.meanTotalInstalls().subtract(off.meanTotalInstalls());
        BigDecimal organicChangePct = off.meanOrganic().signum() > 0
                ? on.meanOrganic().subtract(off.meanOrganic()).multiply(HUNDRED)
                        .divide(off.meanOrganic(), 1, RoundingMode.HALF_UP)
                : null;
        boolean organicFell = organicChangePct != null
                && organicChangePct.negate().compareTo(rule.maxCannibalisationPct()) > 0;
        boolean liftEnough = lift.compareTo(rule.minLift()) >= 0;

        String verdict;
        if (n < minN) {
            verdict = INCONCLUSIVE;
        } else if (liftEnough && !organicFell) {
            verdict = WORKED;
        } else if (organicFell) {
            verdict = DID_NOT_WORK;
        } else {
            verdict = NO_EFFECT;
        }
        String reason = reason(verdict, keywordText, rule, on, off, lift, organicChangePct, n, minN);
        return new SwitchbackOutcome(weeks, on, off, n, minN, lift, organicChangePct, verdict, reason);
    }

    private static Week figures(WeekInput in) {
        BigDecimal organic = in.searchDownloads().subtract(in.adsNewDownloads()).max(BigDecimal.ZERO);
        return new Week(in.weekStart().toString(), in.on(), in.searchDownloads(), in.adsNewDownloads(), organic,
                organic.add(in.adsNewDownloads()));
    }

    private static Means means(List<Week> weeks, boolean on) {
        List<Week> selected = weeks.stream().filter(w -> w.on() == on).toList();
        if (selected.isEmpty()) {
            throw new IllegalStateException("A switchback test needs at least one " + (on ? "on" : "off") + " week");
        }
        BigDecimal count = BigDecimal.valueOf(selected.size());
        BigDecimal organic = selected.stream().map(Week::organic).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = selected.stream().map(Week::totalInstalls).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Means(selected.size(), organic.divide(count, 2, RoundingMode.HALF_UP),
                total.divide(count, 2, RoundingMode.HALF_UP));
    }

    // ---- words -------------------------------------------------------------------------------------------

    private static String reason(String verdict, String keyword, SwitchbackRule rule, Means on, Means off,
                                 BigDecimal lift, BigDecimal organicChangePct, long n, int minN) {
        String totals = fmt(off.meanTotalInstalls()) + " to " + fmt(on.meanTotalInstalls());
        String organicRange = fmt(off.meanOrganic()) + " to " + fmt(on.meanOrganic());
        return switch (verdict) {
            case INCONCLUSIVE -> "Inconclusive for \"" + keyword + "\": n reached " + n + " of min " + minN
                    + " first-time search downloads, so no verdict was drawn.";
            case WORKED -> "Paid is incremental for \"" + keyword + "\": with it on, a week averaged "
                    + fmt(on.meanTotalInstalls()) + " first-time installs against " + fmt(off.meanTotalInstalls())
                    + " with it off (" + signed(lift) + " a week), and organic search first-time downloads "
                    + (organicChangePct == null ? "had none in off weeks, so could not fall"
                            : "moved from " + organicRange + " a week (" + signed(organicChangePct) + "%)")
                    + ".";
            case DID_NOT_WORK -> "Paid only relabels organic for \"" + keyword + "\": organic search first-time "
                    + "downloads fell " + fmt(organicChangePct.negate()) + "% when it was on (" + organicRange
                    + " a week), more than the " + fmt(rule.maxCannibalisationPct()) + "% allowed, and total "
                    + "first-time installs moved " + signed(lift) + " a week (" + totals + ").";
            default -> "Switching \"" + keyword + "\" on made no measurable difference: total first-time installs "
                    + "moved " + signed(lift) + " a week (" + totals + "), short of the " + fmt(rule.minLift())
                    + " needed, and organic search first-time downloads did not fall by more than "
                    + fmt(rule.maxCannibalisationPct()) + "%.";
        };
    }

    /** What the change's baseline field says: the off weeks, the state paid was not in. */
    static String baselineText(SwitchbackOutcome o) {
        return "Off weeks (" + o.offWeeks().weeks() + "): mean organic " + fmt(o.offWeeks().meanOrganic())
                + " and mean total first-time installs " + fmt(o.offWeeks().meanTotalInstalls()) + " a week.";
    }

    /** What the change's outcome field says: every week, then the means, the lift and the n reached. */
    static String outcomeText(SwitchbackOutcome o) {
        String table = o.weeks().stream().map(w -> w.weekStart() + " " + (w.on() ? "ON" : "OFF") + ": search "
                + fmt(w.searchDownloads()) + ", Apple Ads first-time " + fmt(w.adsNewDownloads()) + ", organic "
                + fmt(w.organic()) + ", total installs " + fmt(w.totalInstalls())).collect(Collectors.joining("\n"));
        String organicChange = o.organicChangePct() == null ? "organic change not defined (no organic in off weeks)"
                : "organic change " + signed(o.organicChangePct()) + "%";
        return table + "\n\n"
                + "On weeks (" + o.onWeeks().weeks() + "): mean organic " + fmt(o.onWeeks().meanOrganic())
                + ", mean total installs " + fmt(o.onWeeks().meanTotalInstalls()) + ".\n"
                + "Off weeks (" + o.offWeeks().weeks() + "): mean organic " + fmt(o.offWeeks().meanOrganic())
                + ", mean total installs " + fmt(o.offWeeks().meanTotalInstalls()) + ".\n"
                + "Lift " + signed(o.lift()) + " a week; " + organicChange + ".\n"
                + "n = " + o.n() + " first-time search downloads (min " + o.minN() + ").";
    }

    private static String fmt(BigDecimal value) {
        return SwitchbackRule.plain(value);
    }

    private static String signed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + fmt(value);
    }
}
