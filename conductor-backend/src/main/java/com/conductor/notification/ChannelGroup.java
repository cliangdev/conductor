package com.conductor.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Optional;

public enum ChannelGroup {

    ISSUES("Issues", List.of(
            EventType.WORK_ITEM_STATUS_CHANGED,
            EventType.REVIEWER_ASSIGNED,
            EventType.REVIEW_SUBMITTED,
            EventType.COMMENT_ADDED,
            EventType.COMMENT_REPLY
    )),

    /**
     * Publishing activity, so a marketing channel is not the engineering one. Deliberately carries
     * {@link EventType#WORK_ITEM_STATUS_CHANGED} as well as {@link #ISSUES} does: a Post moving through
     * review is the same event an Issue fires, and the point of this group is only to send it somewhere
     * else. {@link #forEventType} would resolve that shared event to whichever group comes first, so
     * routing between the two is decided by {@link #forEvent} from the event's own metadata rather than by
     * declaration order here.
     */
    PUBLISHING("Publishing", true, List.of(
            EventType.WORK_ITEM_STATUS_CHANGED,
            EventType.POST_AWAITING_MANUAL
    )),

    MEMBERS("Members", List.of(
            EventType.MEMBER_JOINED,
            EventType.MEMBER_ROLE_CHANGED
    )),

    WORKFLOWS("Workflows", List.of(
            EventType.WORKFLOW_RUN_FAILED,
            EventType.WORKFLOW_AUTO_PAUSED
    ));

    private final String label;
    private final boolean specialised;
    private final List<EventType> eventTypes;

    ChannelGroup(String label, List<EventType> eventTypes) {
        this(label, false, eventTypes);
    }

    ChannelGroup(String label, boolean specialised, List<EventType> eventTypes) {
        this.label = label;
        this.specialised = specialised;
        this.eventTypes = eventTypes;
    }

    /**
     * A specialised group is never resolved from an event type alone — only {@link #forEvent} can choose
     * it, from the event's own metadata. That is what lets one event type live in two groups without
     * {@link #forEventType} becoming a coin toss decided by declaration order: the general groups still
     * partition the event types between them, and a specialised group sits on top as an opt-in override.
     */
    public boolean isSpecialised() {
        return specialised;
    }

    public String getLabel() {
        return label;
    }

    public List<EventType> getEventTypes() {
        return eventTypes;
    }

    /**
     * The general group declaring this event type. Specialised groups are excluded, so this stays
     * single-valued and order-independent however many of them carry the same event; {@link #forEvent} is
     * what delivery uses, and is the only thing that can select a specialised group.
     */
    public static Optional<ChannelGroup> forEventType(EventType eventType) {
        return Arrays.stream(values())
                .filter(g -> !g.specialised)
                .filter(g -> g.eventTypes.contains(eventType))
                .findFirst();
    }

    /**
     * Where an event should be delivered, most specific first.
     *
     * <p>An event from a publishing Workflow prefers {@link #PUBLISHING}; everything else resolves exactly
     * as it always did. The list is ordered rather than singular so delivery can fall through: a project
     * that has never configured a Publishing channel keeps getting its Post activity in the Issues channel
     * rather than silently losing it, which makes this additive for every project that already exists.
     *
     * <p>The decision reads {@link #META_PUBLISHES} off the event rather than matching an area name, so no
     * Workflow's vocabulary is hardcoded here — the emitter, which holds the statechart, decides whether
     * the item publishes, by the same {@code asset_types} rule the publishing validators use.
     */
    public static List<ChannelGroup> forEvent(EventType eventType, Map<String, String> metadata) {
        List<ChannelGroup> candidates = new ArrayList<>();
        if (publishes(metadata) && PUBLISHING.eventTypes.contains(eventType)) {
            candidates.add(PUBLISHING);
        }
        forEventType(eventType).filter(g -> !candidates.contains(g)).ifPresent(candidates::add);
        return List.copyOf(candidates);
    }

    /** Metadata flag an emitter sets when the Work Item belongs to a publishing Workflow. */
    public static final String META_PUBLISHES = "publishes";

    private static boolean publishes(Map<String, String> metadata) {
        return metadata != null && Boolean.parseBoolean(metadata.get(META_PUBLISHES));
    }
}
