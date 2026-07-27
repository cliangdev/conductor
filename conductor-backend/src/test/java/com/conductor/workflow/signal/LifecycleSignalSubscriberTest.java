package com.conductor.workflow.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LifecycleSignalSubscriberTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private LifecycleTriggerDispatcher lifecycleTriggerDispatcher;

    private final NotificationSignalMapper mapper = new NotificationSignalMapper();

    private Signal signal() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));
        return mapper.toSignal(event);
    }

    @Test
    void orderIsLifecycle() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper);
        assertThat(sub.order()).isEqualTo(SignalDispatchOrder.LIFECYCLE);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper);
        assertThat(sub.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    @Test
    void interestedInEveryType() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper);
        assertThat(sub.interestedIn("anything")).isTrue();
    }

    @Test
    void onSignalDelegatesToLifecycleTriggerDispatcher() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper);

        sub.onSignal(signal());

        verify(lifecycleTriggerDispatcher).onConductorEvent(any());
    }

    @Test
    void aFailingCascadeIsSwallowedInsideOnSignal() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper);
        doThrow(new RuntimeException("boom")).when(lifecycleTriggerDispatcher).onConductorEvent(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(signal()));
    }
}
