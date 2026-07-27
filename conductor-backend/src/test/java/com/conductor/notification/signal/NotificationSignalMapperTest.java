package com.conductor.notification.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
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
        NotificationEvent event = NotificationEvent.of(type, PROJECT_ID,
                Map.of("workItemId", "wi-1", "toStatus", "DONE"));

        NotificationEvent roundTripped = mapper.toNotificationEvent(mapper.toSignal(event));

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
        NotificationEvent event = NotificationEvent.of(type, PROJECT_ID, Map.of());
        Signal signal = mapper.toSignal(event);

        assertThat(signal.type()).isNotBlank();
    }

    @Test
    void toSignalCarriesProjectIdAndOccurredAtFromTheEvent() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, metadata);

        Signal signal = mapper.toSignal(event);

        assertThat(signal.flatAttributes()).isEqualTo(metadata);
    }

    @Test
    void toSignalOnEmptyMetadataProducesEmptyFlatAttributes() {
        NotificationEvent event = NotificationEvent.of(EventType.MEMBER_JOINED, PROJECT_ID, Map.of());

        Signal signal = mapper.toSignal(event);

        assertThat(signal.flatAttributes()).isEmpty();
    }

    @Test
    void mappingIsABijectionOverAllTenEventTypesAndSignalTypes() {
        Set<String> mappedSignalTypes = Arrays.stream(EventType.values())
                .map(type -> mapper.toSignal(NotificationEvent.of(type, PROJECT_ID, Map.of())))
                .map(Signal::type)
                .collect(Collectors.toSet());

        assertThat(EventType.values()).hasSize(10);
        // Every EventType maps to a distinct SignalTypes constant -- no two collapse onto the same
        // signal type, and every produced signal type maps straight back to its origin EventType.
        assertThat(mappedSignalTypes).hasSize(10).containsExactlyInAnyOrder(
                SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED,
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED,
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED,
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED,
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_REPLIED,
                SignalTypes.CONDUCTOR_PROJECT_MEMBER_JOINED,
                SignalTypes.CONDUCTOR_PROJECT_MEMBER_ROLE_CHANGED,
                SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED,
                SignalTypes.CONDUCTOR_WORKFLOW_AUTO_PAUSED,
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
