package com.conductor.notification;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum ChannelGroup {

    ISSUES("Issues", List.of(
            EventType.ISSUE_SUBMITTED,
            EventType.ISSUE_APPROVED,
            EventType.ISSUE_COMPLETED,
            EventType.REVIEWER_ASSIGNED,
            EventType.REVIEW_SUBMITTED,
            EventType.COMMENT_ADDED,
            EventType.COMMENT_REPLY
    )),

    MEMBERS("Members", List.of(
            EventType.MEMBER_JOINED,
            EventType.MEMBER_ROLE_CHANGED
    ));

    private final String label;
    private final List<EventType> eventTypes;

    ChannelGroup(String label, List<EventType> eventTypes) {
        this.label = label;
        this.eventTypes = eventTypes;
    }

    public String getLabel() {
        return label;
    }

    public List<EventType> getEventTypes() {
        return eventTypes;
    }

    public static Optional<ChannelGroup> forEventType(EventType eventType) {
        return Arrays.stream(values())
                .filter(g -> g.eventTypes.contains(eventType))
                .findFirst();
    }
}
