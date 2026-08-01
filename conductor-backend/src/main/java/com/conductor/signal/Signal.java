package com.conductor.signal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A single fan-out event on the in-process {@link SignalBus}. This is the replacement unit for
 * {@code com.conductor.notification.NotificationMessage}, generalized beyond notification-shaped
 * payloads so the same envelope can carry connector events (e.g. a GitHub PR merge) as well as
 * Conductor-internal ones (e.g. a Work Item status change).
 *
 * <p>{@code type} is one of the {@link SignalTypes} constants (or a future connector-defined
 * string of the same dotted-segment shape) and is matched against subscriber interest via
 * {@link SignalGlob} -- see {@link SignalTypes} for why {@code github.pull_request} and
 * {@code github.pull_request_merged} are deliberately flat, non-nesting names.
 *
 * <p>{@code payload} carries typed, structured data. Unlike {@code NotificationMessage#metadata}
 * (a flat {@code Map<String,String>}), {@code payload} values may be any type -- richer consumers
 * should read it directly rather than going through {@link #flatAttributes()}.
 */
public record Signal(
        String type,
        String projectId,
        String ref,
        Instant occurredAt,
        Map<String, Object> payload,
        SignalOrigin origin,
        String traceId) {

    /**
     * Rejects null keys and null values at the TOP level of {@code payload} only -- mirroring
     * {@code Map.copyOf}'s behavior, which is what {@code NotificationMessage} and today's
     * {@code GitHubConnector}/{@code WorkItemService} callers are already built around (both
     * exist precisely because a null value there throws instead of silently landing in the map).
     * Permitting a top-level null would let e.g. {@code "label": null} reach the persisted
     * {@code workflow_runs.event_payload}, where a customer YAML expression like
     * {@code ${{ event.label }}} would render the literal string {@code "null"} instead of being
     * treated as absent. Nested structures are NOT walked -- a null two levels deep is allowed,
     * matching today's blast radius exactly (no wider, no narrower).
     */
    public Signal {
        payload = Map.copyOf(payload);
    }

    /**
     * Convenience for callers with no trace id on hand -- {@link InProcessSignalBus#publish}
     * assigns a fresh one before dispatch when this is {@code null}, so every signal a subscriber
     * ever sees carries one, without forcing every publish call site to invent its own.
     */
    public static Signal of(String type, String projectId, String ref, Instant occurredAt,
                             Map<String, Object> payload, SignalOrigin origin) {
        return new Signal(type, projectId, ref, occurredAt, payload, origin, null);
    }

    /** Same as {@link #of}, but for callers that already have a trace id to carry through (e.g. a webhook's). */
    public static Signal of(String type, String projectId, String ref, Instant occurredAt,
                             Map<String, Object> payload, SignalOrigin origin, String traceId) {
        return new Signal(type, projectId, ref, occurredAt, payload, origin, traceId);
    }

    /** Copy of this signal with {@code traceId} replaced -- used by {@link InProcessSignalBus} to assign one. */
    public Signal withTraceId(String traceId) {
        return new Signal(type, projectId, ref, occurredAt, payload, origin, traceId);
    }

    /**
     * Stringifies top-level scalar values of {@code payload} into a flat {@code Map<String,String>},
     * for consumers migrating off {@code NotificationMessage#getMetadata()}-shaped access such as
     * {@code metadata.get("toStatus")}, {@code metadata.get("action")}, or {@code metadata.get("label")}.
     *
     * @deprecated exists only as a bridge for consumers still doing stringly-typed lookups against
     * the payload. It preserves the stringly-typed ceiling of {@code NotificationMessage#metadata}
     * rather than lifting it -- new code should read {@link #payload()} directly (or a future
     * typed-per-signal-type payload accessor) instead of calling this. Without this annotation the
     * bridge tends to calcify into a permanent API; it is not.
     */
    @Deprecated
    public Map<String, String> flatAttributes() {
        Map<String, String> flat = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof Map || value instanceof Iterable) {
                continue;
            }
            flat.put(entry.getKey(), String.valueOf(value));
        }
        return flat;
    }
}
