package com.conductor.service.publish;

import com.conductor.entity.PublishLane;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything the publishing pipeline knows about one platform, in one place.
 *
 * <p>Before this record existed the same four platform ids were spelled out in eleven parallel tables —
 * the validators' label maps, the scheduler's action map, the outcome service's asset-type map, the
 * native hand-off's window map, the confirmation poller's read-action map, the target service's manual
 * labels — each keyed by the {@code post_publish_target.platform} vocabulary and each free to drift from
 * the others. Adding a platform meant finding all eleven. Now a platform is one value here, and the
 * services ask {@link PublishPlatformRegistry} for it.
 *
 * <p>The record is deliberately plain data plus a couple of derived predicates. Behaviour that differs by
 * platform in ways richer than a value (how a media set is checked, how a set of options is inspected)
 * still lives in the service that owns it, keyed on {@link #id()} — the point is one vocabulary, not one
 * class that knows everything.
 *
 * @param id             the {@code post_publish_target.platform} value, always lower-case
 * @param label          how a human reads the platform in a message ("Facebook")
 * @param connectorId    the connector whose connections publish here ({@code meta}, {@code youtube}, ...)
 * @param assetType      the Workflow asset type a published destination is recorded under, and the entry a
 *                       Workflow's {@code asset_types} declares to opt into publishing
 * @param manualLabel    how the platform's MANUAL destination names itself in a picker
 * @param automatedLane  the lane an API-connected destination on this platform publishes through; MANUAL is
 *                       offered for every platform and is never declared here
 * @param publish        how a post goes out
 * @param revoke         how a natively scheduled post is taken back, or null off the NATIVE lane
 * @param confirm        how a natively scheduled post is asked whether it went live, or null off that lane
 * @param metrics        how a published post's performance is read back, or null when the connector has no
 *                       read action for it
 * @param optionParams   the target's {@code publish_options} keys (the API's camelCase) mapped to the
 *                       parameter the connector's tool spec declares; a whitelist, so an unknown key is dropped
 * @param minLead        the earliest a fire time may be from "now" for an automated destination here
 * @param maxLead        the latest a fire time may be for a native hand-off, or null for no far-future limit
 * @param gates          the extra approval rules this platform trips, beyond media and schedule
 */
public record PublishPlatform(String id,
                              String label,
                              String connectorId,
                              String assetType,
                              String manualLabel,
                              PublishLane automatedLane,
                              PublishAction publish,
                              RevokeAction revoke,
                              ConfirmAction confirm,
                              MetricsAction metrics,
                              Map<String, String> optionParams,
                              Duration minLead,
                              Duration maxLead,
                              Set<Gate> gates,
                              Set<PostFormat> formats,
                              Map<PostFormat, Duration> maxLeadByFormat,
                              Map<PostFormat, PublishLane> laneByFormat) {

    /**
     * Facebook's native scheduling minimum, the floor every platform shared before leads went per platform.
     * Still the fallback for a target whose platform is not in the registry.
     */
    public static final Duration DEFAULT_MIN_LEAD = Duration.ofMinutes(10);

    /** Which of the Post's two pieces of copy an alias parameter carries. */
    public enum CopySource { CAPTION, TITLE }

    /** The approval-gate rules a platform trips beyond the media and schedule checks every platform gets. */
    public enum Gate {
        /** A privacy level must be chosen from the connected creator's allowed set. */
        PRIVACY_LEVEL,
        /** The creator's recorded consent must stand for the current bundle. */
        CREATOR_CONSENT,
        /** Video length is capped per connected creator, read from the connection's cached config. */
        CREATOR_DURATION_CAP
    }

    /**
     * How a post goes out on this platform.
     *
     * @param actionId        the connector's publish action
     * @param captionParam    the parameter the post's body text travels in
     * @param copyAliases     extra parameters that carry the caption or the title under another name — TikTok
     *                        names a photo post's text {@code description}/{@code headline}
     * @param extras          parameters every publish on this platform sends regardless of the post
     * @param postIdOutputKey the output key the platform reports its post id under
     * @param scheduleParam   the parameter a NATIVE hand-off sends the fire time in, or null off that lane
     */
    public record PublishAction(String actionId,
                                String captionParam,
                                Map<String, CopySource> copyAliases,
                                Map<String, Object> extras,
                                String postIdOutputKey,
                                String scheduleParam) {
        public PublishAction {
            copyAliases = ordered(copyAliases);
            extras = ordered(extras);
        }
    }

    /**
     * How a natively scheduled post is taken back.
     *
     * @param actionId        the connector's revoke action
     * @param idParam         the parameter the stored platform post id travels in
     * @param extras          parameters sent with every revoke
     * @param clearedOnRevoke parameters sent as explicit nulls, so a platform default cannot creep back in
     */
    public record RevokeAction(String actionId,
                               String idParam,
                               Map<String, Object> extras,
                               List<String> clearedOnRevoke) {
        public RevokeAction {
            extras = ordered(extras);
            clearedOnRevoke = clearedOnRevoke == null ? List.of() : List.copyOf(clearedOnRevoke);
        }
    }

    /**
     * How a natively scheduled post is asked whether it went live.
     *
     * @param actionId        the connector's read action
     * @param postIdParam     the parameter the stored platform post id travels in
     * @param postIdOutputKey the key the platform reports that id back under
     * @param liveness        reads the action's output as "live yet?" — {@code null} meaning "cannot tell",
     *                        which is deliberately not "published"
     */
    public record ConfirmAction(String actionId,
                                String postIdParam,
                                String postIdOutputKey,
                                Function<Map<String, Object>, Boolean> liveness) {}

    /**
     * How a published post's numbers are read back.
     *
     * @param actionId the connector's read action; takes {@code post_ids} (a list) and answers with
     *                 {@code metrics}, one entry per id
     * @param maxBatch how many ids one call may carry — the platform's own batch limit
     */
    public record MetricsAction(String actionId, int maxBatch) {}

    /**
     * How far from "now" a platform will accept a scheduled post. A {@code null} {@link #maxLead} means the
     * platform declares no far-future limit (YouTube), not "zero".
     */
    public record HandoffWindow(Duration minLead, Duration maxLead) {

        public boolean accepts(OffsetDateTime now, OffsetDateTime fireTime) {
            return fireTime != null && !tooSoon(now, fireTime) && !tooFarOut(now, fireTime);
        }

        public boolean tooSoon(OffsetDateTime now, OffsetDateTime fireTime) {
            return fireTime != null && fireTime.isBefore(now.plus(minLead));
        }

        public boolean tooFarOut(OffsetDateTime now, OffsetDateTime fireTime) {
            return maxLead != null && fireTime != null && fireTime.isAfter(now.plus(maxLead));
        }
    }

    /** A platform that publishes feed posts only, on one lane, with one hand-off window: the shape before formats. */
    public PublishPlatform(String id, String label, String connectorId, String assetType, String manualLabel,
                           PublishLane automatedLane, PublishAction publish, RevokeAction revoke,
                           ConfirmAction confirm, MetricsAction metrics, Map<String, String> optionParams,
                           Duration minLead, Duration maxLead, Set<Gate> gates) {
        this(id, label, connectorId, assetType, manualLabel, automatedLane, publish, revoke, confirm, metrics,
                optionParams, minLead, maxLead, gates, EnumSet.of(PostFormat.FEED), Map.of(), Map.of());
    }

    public PublishPlatform {
        formats = formats == null || formats.isEmpty() ? EnumSet.of(PostFormat.FEED) : EnumSet.copyOf(formats);
        maxLeadByFormat = maxLeadByFormat == null ? Map.of() : Map.copyOf(maxLeadByFormat);
        laneByFormat = laneByFormat == null ? Map.of() : Map.copyOf(laneByFormat);
        optionParams = ordered(optionParams);
        gates = gates == null ? Set.of() : Set.copyOf(gates);
    }

    /**
     * An unmodifiable copy that keeps the declared order. {@code Map.copyOf} would not, and order is part of
     * the contract: {@link #optionParams()} is iterated when a target's options become action input, so the
     * parameters should read in the order the connector's spec declares them.
     */
    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** Whether API-connected destinations here are scheduled by the platform itself. */
    public boolean isNative() {
        return automatedLane == PublishLane.NATIVE;
    }

    /**
     * The earliest a fire time may be for a destination on the given lane. A MANUAL destination has no
     * platform API to satisfy, so nothing but "in the future" applies to it.
     */
    public Duration minLead(PublishLane lane) {
        return lane == PublishLane.MANUAL ? Duration.ZERO : minLead;
    }

    /** The native hand-off window, built from the same leads the approval gate reads. */
    public HandoffWindow window() {
        return new HandoffWindow(minLead, maxLead);
    }

    /** Whether this platform publishes in {@code format} at all. Every platform offers {@link PostFormat#FEED}. */
    public boolean supports(PostFormat format) {
        return format != null && formats.contains(format);
    }

    /**
     * The lane a connected account publishes {@code format} on. Most formats share the platform's
     * {@link #automatedLane()}; a format the platform cannot schedule itself (a Facebook story) is held by
     * Conductor and fired at its time instead, i.e. APP_MANAGED.
     */
    public PublishLane laneFor(PostFormat format) {
        return laneByFormat.getOrDefault(format == null ? PostFormat.FEED : format, automatedLane);
    }

    /** The furthest ahead the platform's own scheduler accepts {@code format}; null when it has no ceiling. */
    public Duration maxLeadFor(PostFormat format) {
        return maxLeadByFormat.getOrDefault(format == null ? PostFormat.FEED : format, maxLead);
    }

    /** The native hand-off window for {@code format}: {@link #window()} with that format's ceiling. */
    public HandoffWindow windowFor(PostFormat format) {
        return new HandoffWindow(minLead, maxLeadFor(format));
    }

    /** Whether this platform trips the given approval rule. */
    public boolean has(Gate gate) {
        return gates.contains(gate);
    }
}
