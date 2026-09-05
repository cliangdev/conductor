package com.conductor.notification.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationMessage;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSignalMapperTest {

    private static final String PROJECT_ID = "proj-1";

    private final NotificationSignalMapper mapper = new NotificationSignalMapper();

    @ParameterizedTest
    @EnumSource(EventType.class)
    void roundTripsEveryEventTypeIdentically(EventType type) {
        NotificationMessage event = NotificationMessage.of(type, PROJECT_ID,
                Map.of("workItemId", "wi-1", "toStatus", "DONE"));

        NotificationMessage roundTripped = mapper.toNotificationEvent(mapper.toSignal(event));

        assertThat(roundTripped.getEventType()).isEqualTo(event.getEventType());
        assertThat(roundTripped.getProjectId()).isEqualTo(event.getProjectId());
        assertThat(roundTripped.getMetadata()).isEqualTo(event.getMetadata());
        assertThat(roundTripped).isEqualTo(event);
        // equals() deliberately ignores timestamp, so assert it explicitly: the reverse mapping must
        // carry Signal.occurredAt back rather than stamping the translation time. Nothing reads
        // getTimestamp() today, so a regression here would be invisible until something did.
        assertThat(roundTripped.getTimestamp()).isEqualTo(event.getTimestamp());
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyEventTypeMapsToADistinctSignalType(EventType type) {
        NotificationMessage event = NotificationMessage.of(type, PROJECT_ID, Map.of());
        Signal signal = mapper.toSignal(event);

        assertThat(signal.type()).isNotBlank();
    }

    @Test
    void toSignalCarriesProjectIdAndOccurredAtFromTheEvent() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));

        Signal signal = mapper.toSignal(event);

        assertThat(signal.projectId()).isEqualTo(PROJECT_ID);
        assertThat(signal.occurredAt()).isEqualTo(event.getTimestamp());
        assertThat(signal.ref()).isNull();
    }

    @Test
    void flatAttributesReproducesTheOriginalMetadataExactly() {
        Map<String, String> metadata = Map.of(
                "workItemId", "wi-1",
                "workItemTitle", "Some title",
                "fromStatus", "OPEN",
                "toStatus", "DONE");
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, metadata);

        Signal signal = mapper.toSignal(event);

        assertThat(signal.flatAttributes()).isEqualTo(metadata);
    }

    @Test
    void toSignalOnEmptyMetadataProducesEmptyFlatAttributes() {
        NotificationMessage event = NotificationMessage.of(EventType.MEMBER_JOINED, PROJECT_ID, Map.of());

        Signal signal = mapper.toSignal(event);

        assertThat(signal.flatAttributes()).isEmpty();
    }

    @Test
    void mappingIsABijectionOverEveryEventTypeAndSignalType() {
        Set<String> mappedSignalTypes = Arrays.stream(EventType.values())
                .map(type -> mapper.toSignal(NotificationMessage.of(type, PROJECT_ID, Map.of())))
                .map(Signal::type)
                .collect(Collectors.toSet());

        // Every EventType maps to a distinct SignalTypes constant -- no two collapse onto the same
        // signal type, and every produced signal type maps straight back to its origin EventType. The
        // size is derived rather than written twice: the bijection is the property under test, and a
        // literal count in two places is a thing to edit on every addition rather than a thing to prove.
        assertThat(mappedSignalTypes).hasSize(EventType.values().length).containsExactlyInAnyOrder(
                SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED,
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED,
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED,
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED,
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_REPLIED,
                SignalTypes.CONDUCTOR_PROJECT_MEMBER_JOINED,
                SignalTypes.CONDUCTOR_PROJECT_MEMBER_ROLE_CHANGED,
                SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED,
                SignalTypes.CONDUCTOR_WORKFLOW_AUTO_PAUSED,
                SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED,
                SignalTypes.CONDUCTOR_WORK_ITEM_AWAITING_MANUAL_PUBLISH,
                SignalTypes.CONDUCTOR_WORK_ITEM_AUTO_TRANSITION_BLOCKED,
                SignalTypes.GITHUB_PULL_REQUEST);
    }

    @Test
    void toNotificationEventRejectsAnUnmappableSignalType() {
        Signal signal = Signal.of("some.unmapped.type", PROJECT_ID, null,
                java.time.Instant.now(), Map.of(), new com.conductor.signal.SignalOrigin("test", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.toNotificationEvent(signal))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
