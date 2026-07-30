package com.conductor.notification.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationMessage;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates between {@link NotificationMessage} (the old, notification-shaped envelope) and
 * {@link Signal} (the generalized bus envelope) in both directions. This is the anti-corruption layer
 * that lets {@code NotificationDispatcher} publish onto {@code SignalBus}, and lets each new {@code
 * SignalSubscriber} hand the existing, UNMODIFIED consumer methods (on {@code WorkflowTriggerService},
 * {@code LifecycleTriggerDispatcher}, {@code KnowledgeEventTap}) the exact same shape of event they
 * receive today.
 *
 * <h2>Payload fidelity</h2>
 * {@link #toSignal} copies {@code event.getMetadata()} verbatim into {@link Signal#payload()}, and
 * {@link #toNotificationEvent} reads it back out via {@link Signal#flatAttributes()}. This round-trips
 * exactly because every value going in is already a {@code String} -- {@code flatAttributes()}'s
 * {@code String.valueOf} stringification is a no-op on an already-{@code String} value. This matters
 * beyond this translator: {@code WorkflowTriggerService.buildEventPayload} does
 * {@code new HashMap<>(metadata)} and persists it verbatim to {@code workflow_runs.event_payload},
 * which customer workflow YAML reads via expressions like {@code ${{ event.workItemId }}} -- so the
 * persisted shape must not change underneath this refactor.
 */
@Component
public class NotificationSignalMapper {

    /** Purely descriptive; see {@link SignalOrigin}'s javadoc -- subscribers don't parse this. */
    private static final SignalOrigin ORIGIN = new SignalOrigin("notification_dispatcher", null);

    public Signal toSignal(NotificationMessage event) {
        Map<String, Object> payload = new HashMap<>(event.getMetadata());
        return Signal.of(toSignalType(event.getEventType()), event.getProjectId(), null,
                event.getTimestamp(), payload, ORIGIN);
    }

    /**
     * Rebuilds the consumer-facing envelope, preserving {@link Signal#occurredAt()} as the event's
     * timestamp rather than stamping the translation time. Nothing reads {@code getTimestamp()} today,
     * so a fresh stamp would be invisible now and wrong later.
     */
    public NotificationMessage toNotificationEvent(Signal signal) {
        return NotificationMessage.of(toEventType(signal.type()), signal.projectId(), signal.flatAttributes(),
                signal.occurredAt());
    }

    /**
     * Total, exhaustive bijection over every {@link EventType} constant. Deliberately a {@code switch}
     * expression with NO {@code default} branch: adding a new {@code EventType} without adding its
     * mapping here is a COMPILE error, not a silent {@code null} landing in {@link Signal#type()}.
     */
    private static String toSignalType(EventType eventType) {
        return switch (eventType) {
            case WORK_ITEM_STATUS_CHANGED -> SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED;
            case REVIEWER_ASSIGNED -> SignalTypes.CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED;
            case REVIEW_SUBMITTED -> SignalTypes.CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED;
            case COMMENT_ADDED -> SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED;
            case COMMENT_REPLY -> SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_REPLIED;
            case MEMBER_JOINED -> SignalTypes.CONDUCTOR_PROJECT_MEMBER_JOINED;
            case MEMBER_ROLE_CHANGED -> SignalTypes.CONDUCTOR_PROJECT_MEMBER_ROLE_CHANGED;
            case ASSET_ADDED -> SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED;
            case WORKFLOW_AUTO_PAUSED -> SignalTypes.CONDUCTOR_WORKFLOW_AUTO_PAUSED;
            case WORKFLOW_RUN_FAILED -> SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED;
            case GITHUB_PULL_REQUEST -> SignalTypes.GITHUB_PULL_REQUEST;
        };
    }

    /**
     * Whether this signal type has an {@link EventType} counterpart, i.e. whether it is expressible in
     * the notification vocabulary at all.
     *
     * <p>{@link SignalTypes} is deliberately open-ended -- connector-defined types like
     * {@code github.pull_request_merged} exist on the bus with no notification equivalent, because not
     * every fact worth fanning out is a chat message. Callers must gate on this before {@link
     * #toNotificationEvent}, which throws for an unmapped type.
     */
    public boolean isDeliverable(String signalType) {
        for (EventType eventType : EventType.values()) {
            if (toSignalType(eventType).equals(signalType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inverse of {@link #toSignalType}, derived from it rather than hand-duplicated as a second case
     * table -- a second table could silently drift out of sync with the first (e.g. after someone edits
     * one switch and forgets the other), which would break the round-trip identity this mapper exists to
     * guarantee. Eleven constants is cheap enough to scan linearly per call.
     */
    private static EventType toEventType(String signalType) {
        for (EventType eventType : EventType.values()) {
            if (toSignalType(eventType).equals(signalType)) {
                return eventType;
            }
        }
        throw new IllegalArgumentException("No EventType maps to signal type: " + signalType);
    }
}
