package com.conductor.workflow.signal;

import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

    private Signal signal() {
        return Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, PROJECT_ID, null, Instant.now(),
                Map.of("workItemId", "wi-1"), new SignalOrigin("test", null));
    }

    @Test
    void orderIsLifecycle() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher);
        assertThat(sub.order()).isEqualTo(SignalDispatchOrder.LIFECYCLE);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher);
        assertThat(sub.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    /** Narrowed in A6: exact string equality against the one type this subscriber acts on. */
    @Test
    void interestedInConductorStatusChangedOnly() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher);
        assertThat(sub.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)).isTrue();
        assertThat(sub.interestedIn(SignalTypes.GITHUB_PULL_REQUEST)).isFalse();
        assertThat(sub.interestedIn("anything")).isFalse();
    }

    @Test
    void onSignalDelegatesToLifecycleTriggerDispatcher() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher);
        Signal signal = signal();

        sub.onSignal(signal);

        verify(lifecycleTriggerDispatcher).onConductorEvent(signal);
    }

    @Test
    void aFailingCascadeIsSwallowedInsideOnSignal() {
        LifecycleSignalSubscriber sub = new LifecycleSignalSubscriber(lifecycleTriggerDispatcher);
        doThrow(new RuntimeException("boom")).when(lifecycleTriggerDispatcher).onConductorEvent(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(signal()));
    }
}
