package com.conductor.marketing.insights;

import java.util.Set;

/**
 * The server, not the model, decides whether a tag or a playbook lesson "works": a model asked to judge noisy
 * numbers finds a story in them. Every threshold lives here. A verdict is about one lift metric over a set of
 * settled posts that have that metric's baseline ({@link PostBaselineService}):
 *
 * <ul>
 *   <li>{@link #NOT_ENOUGH_DATA}: fewer than {@link #MIN_POSTS} posts have the baseline.</li>
 *   <li>{@link #WORKS}: median lift at least {@link #WORKS_MEDIAN_LIFT} and at least {@link #WORKS_BEAT_SHARE}
 *       of the posts beat the baseline (lift above 1).</li>
 *   <li>{@link #DOESNT_WORK}: median lift at most {@link #DOESNT_MEDIAN_LIFT} and at most
 *       {@link #DOESNT_BEAT_SHARE} of the posts beat it.</li>
 *   <li>{@link #NO_DIFFERENCE}: anything between.</li>
 * </ul>
 */
public final class LessonVerdictRules {

    public static final int MIN_POSTS = 3;
    public static final double WORKS_MEDIAN_LIFT = 1.2;
    public static final double WORKS_BEAT_SHARE = 2.0 / 3.0;
    public static final double DOESNT_MEDIAN_LIFT = 0.8;
    public static final double DOESNT_BEAT_SHARE = 1.0 / 3.0;

    public static final String WORKS = "WORKS";
    public static final String DOESNT_WORK = "DOESNT_WORK";
    public static final String NO_DIFFERENCE = "NO_DIFFERENCE";
    public static final String NOT_ENOUGH_DATA = "NOT_ENOUGH_DATA";

    public static final String VIEW_LIFT = "VIEW_LIFT";
    public static final String RETENTION_LIFT = "RETENTION_LIFT";

    /** Tag keys whose default verdict reads retention (when enough posts have it), because the hook is what holds a viewer. */
    private static final Set<String> RETENTION_KEYS = Set.of("hookType", "archetype");

    private LessonVerdictRules() {}

    /** The verdict for {@code withBaseline} posts of which {@code beat} beat it, with the given median lift. */
    public static String verdict(int withBaseline, int beat, Double medianLift) {
        if (withBaseline < MIN_POSTS || medianLift == null) {
            return NOT_ENOUGH_DATA;
        }
        // Integer arithmetic for the shares, so 2 of 3 is exactly 2/3 and 1 of 3 exactly 1/3.
        if (medianLift >= WORKS_MEDIAN_LIFT && beat * 3 >= withBaseline * 2) {
            return WORKS;
        }
        if (medianLift <= DOESNT_MEDIAN_LIFT && beat * 3 <= withBaseline) {
            return DOESNT_WORK;
        }
        return NO_DIFFERENCE;
    }

    /** A tag row's default verdict and the metric it was read from. */
    public record Default(String verdict, String metric) {}

    /** hookType and archetype read retention when at least {@link #MIN_POSTS} posts have its baseline; everything else reads views. */
    public static Default defaultFor(String tagKey, PostBaselineService.LiftSummary lift) {
        if (RETENTION_KEYS.contains(tagKey) && lift.withRetentionBaselinePosts() >= MIN_POSTS) {
            return new Default(lift.retentionVerdict(), RETENTION_LIFT);
        }
        return new Default(lift.viewVerdict(), VIEW_LIFT);
    }
}
