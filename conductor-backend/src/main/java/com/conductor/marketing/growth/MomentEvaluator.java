package com.conductor.marketing.growth;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Works out, on read, what a moment needs. Nothing here is stored.
 *
 * <p>A surface is <b>overdue</b> once UTC today is after its due date (due today is not yet overdue) and no change
 * Work Item is linked; that is the one warning. A moment is {@code warning} while any surface is overdue,
 * {@code upcoming} while today is within {@code leadDays} before its date (the date included) and any surface is
 * not yet overdue, {@code past} once its date has gone by, and {@code scheduled} otherwise.
 */
public final class MomentEvaluator {

    public static final String WARNING = "warning";
    public static final String UPCOMING = "upcoming";
    public static final String SCHEDULED = "scheduled";
    public static final String PAST = "past";

    private MomentEvaluator() {
    }

    public record SurfaceView(String id, String surface, LocalDate dueOn, String changeWorkItemId,
                              String changeDisplayId, String warning) {}

    public record MomentView(String key, String name, LocalDate date, int leadDays, String status,
                             List<SurfaceView> surfaces) {}

    /**
     * @param displayIds display id by Work Item id, for the changes that are linked; a linked change missing here
     *                   still clears its warning, it just shows no display id
     */
    public static MomentView evaluate(MarketingMoment moment, List<MarketingMomentSurface> surfaces,
                                      Map<String, String> displayIds, LocalDate today) {
        List<SurfaceView> views = surfaces.stream().map(s -> surfaceView(moment, s, displayIds, today)).toList();
        return new MomentView(moment.getMomentKey(), moment.getName(), moment.getMomentDate(), moment.getLeadDays(),
                status(moment, surfaces, views, today), views);
    }

    private static SurfaceView surfaceView(MarketingMoment moment, MarketingMomentSurface surface,
                                           Map<String, String> displayIds, LocalDate today) {
        String changeId = surface.getChangeWorkItemId();
        String warning = changeId == null && today.isAfter(surface.getDueOn())
                ? surface.getSurface() + " for " + moment.getName() + " was due " + surface.getDueOn()
                        + " and no change is recorded"
                : null;
        return new SurfaceView(surface.getId(), surface.getSurface(), surface.getDueOn(), changeId,
                changeId == null ? null : displayIds.get(changeId), warning);
    }

    private static String status(MarketingMoment moment, List<MarketingMomentSurface> surfaces,
                                 List<SurfaceView> views, LocalDate today) {
        if (views.stream().anyMatch(v -> v.warning() != null)) {
            return WARNING;
        }
        LocalDate date = moment.getMomentDate();
        boolean inLeadWindow = !today.isBefore(date.minusDays(moment.getLeadDays())) && !today.isAfter(date);
        if (inLeadWindow && surfaces.stream().anyMatch(s -> !today.isAfter(s.getDueOn()))) {
            return UPCOMING;
        }
        return today.isAfter(date) ? PAST : SCHEDULED;
    }
}
