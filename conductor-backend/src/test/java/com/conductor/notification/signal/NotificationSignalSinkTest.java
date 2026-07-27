package com.conductor.notification.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationEvent;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
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

    @Test
    void interestedInEveryType() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        assertThat(sink.interestedIn("anything")).isTrue();
        assertThat(sink.interestedIn("conductor.work_item.status_changed")).isTrue();
    }

    @Test
    void onSignalDelegatesToDeliveryServiceWithTheTranslatedEvent() {
        NotificationSignalSink sink = new NotificationSignalSink(deliveryService, mapper);
        Signal signal = signal();

        sink.onSignal(signal);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
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
