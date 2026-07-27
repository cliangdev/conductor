package com.conductor.notification.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationSignalSinkTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private NotificationDeliveryService deliveryService;

    private final NotificationSignalMapper mapper = new NotificationSignalMapper();

    private Signal signal() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));
        return mapper.toSignal(event);
    }

    @Test
    void orderIsNotificationFirst() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        assertThat(sink.order()).isEqualTo(SignalDispatchOrder.NOTIFICATION);
    }

    @Test
    void failureModeIsPropagate() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        assertThat(sink.failureMode()).isEqualTo(FailureMode.PROPAGATE);
    }

    /**
     * Interested in every type it can EXPRESS as a {@code NotificationMessage}, and nothing else.
     * Because this sink is {@code PROPAGATE}, claiming a type the mapper can't translate would make
     * {@code toNotificationEvent}'s throw abort the whole fan-out -- so an unmapped connector signal
     * like {@code github.pull_request_merged} would silently stop knowledge ingestion and work-item
     * completion from running at all. Pinned end-to-end by
     * {@code SignalFanOutCharacterizationTest#unclaimedSignalTypesFanOutToNothing}.
     */
    @Test
    void interestedOnlyInTypesThatMapToAnEventType() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);

        assertThat(sink.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)).isTrue();
        assertThat(sink.interestedIn(SignalTypes.GITHUB_PULL_REQUEST)).isTrue();

        assertThat(sink.interestedIn(SignalTypes.GITHUB_PULL_REQUEST_MERGED)).isFalse();
        assertThat(sink.interestedIn("some.connector.defined.type")).isFalse();
    }

    @Test
    void onSignalDelegatesToDeliveryServiceWithTheTranslatedEvent() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        Signal signal = signal();

        sink.onSignal(signal);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(deliveryService).deliver(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.WORK_ITEM_STATUS_CHANGED);
        assertThat(captor.getValue().getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().getMetadata()).isEqualTo(Map.of("workItemId", "wi-1"));
    }

    @Test
    void aFailingDeliverEscapesOnSignalUnguarded() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(deliveryService).deliver(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sink.onSignal(signal()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void nameIsStable() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        assertThat(sink.name()).isEqualTo("notification-delivery");
    }
}
