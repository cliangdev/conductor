package com.conductor.signal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InProcessSignalBusTest {

    @Mock
    private ObjectProvider<List<SignalSubscriber>> subscribersProvider;

    private static final String PROJECT_ID = "project-1";

    private Signal signal(String type) {
        return Signal.of(type, PROJECT_ID, "ref-1", Instant.parse("2026-07-26T00:00:00Z"),
                Map.of("key", "value"), new SignalOrigin("work_item", "wi-1"));
    }

    private InProcessSignalBus busWithSubscribers(SignalSubscriber... subscribers) {
        List<SignalSubscriber> list = List.of(subscribers);
        when(subscribersProvider.getIfAvailable(any()))
                .thenReturn(list);
        return new InProcessSignalBus(subscribersProvider);
    }

    private RecordingSubscriber recordingSubscriber(String name, int order, String... interestedTypes) {
        return new RecordingSubscriber(name, order, FailureMode.SWALLOW, interestedTypes);
    }

    @Test
    void subscribersRunInOrder() {
        List<String> invoked = new ArrayList<>();
        SignalSubscriber second = trackingSubscriber("second", 20, invoked);
        SignalSubscriber first = trackingSubscriber("first", 10, invoked);
        SignalSubscriber third = trackingSubscriber("third", 30, invoked);

        InProcessSignalBus bus = busWithSubscribers(second, first, third);

        bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED));

        assertThat(invoked).containsExactly("first", "second", "third");
    }

    @Test
    void uninterestedSubscriberNeverInvoked() {
        RecordingSubscriber interested = recordingSubscriber("interested", 10,
                SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED);
        RecordingSubscriber uninterested = recordingSubscriber("uninterested", 20,
                SignalTypes.GITHUB_PULL_REQUEST);

        InProcessSignalBus bus = busWithSubscribers(interested, uninterested);

        bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED));

        assertThat(interested.received).hasSize(1);
        assertThat(uninterested.received).isEmpty();
    }

    @Test
    void swallowFailureContinuesToLaterSubscribers() {
        FailingSubscriber failing = new FailingSubscriber("failing", 10, FailureMode.SWALLOW);
        RecordingSubscriber later = recordingSubscriber("later", 20, SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED);

        InProcessSignalBus bus = busWithSubscribers(failing, later);

        assertThatNoException().isThrownBy(() ->
                bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)));

        assertThat(later.received).hasSize(1);
    }

    @Test
    void propagateFailureRethrowsAndStopsLaterSubscribers() {
        FailingSubscriber failing = new FailingSubscriber("failing", 10, FailureMode.PROPAGATE);
        RecordingSubscriber later = recordingSubscriber("later", 20, SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED);

        InProcessSignalBus bus = busWithSubscribers(failing, later);

        assertThatThrownBy(() -> bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(later.received).isEmpty();
    }

    @Test
    void depthGuardDropsAtCapWithoutThrowing() {
        // A subscriber that re-enters publish() on the same thread every time it's invoked,
        // simulating the latent WorkflowTriggerService -> circuit breaker -> publish cycle.
        InProcessSignalBus[] busHolder = new InProcessSignalBus[1];
        List<Integer> callDepths = new ArrayList<>();
        SignalSubscriber reentrant = new SignalSubscriber() {
            @Override
            public String name() {
                return "reentrant";
            }

            @Override
            public boolean interestedIn(String signalType) {
                return true;
            }

            @Override
            public void onSignal(Signal signal) {
                callDepths.add(callDepths.size());
                busHolder[0].publish(signal);
            }

            @Override
            public int order() {
                return 10;
            }
        };
        InProcessSignalBus bus = busWithSubscribers(reentrant);
        busHolder[0] = bus;

        assertThatNoException().isThrownBy(() ->
                bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)));

        // depth is capped at 8: the guard trips on the 9th nested publish, so onSignal runs 8 times.
        assertThat(callDepths).hasSize(8);
    }

    @Test
    void subscriberListResolvedFromProviderExactlyOnceAcrossMultiplePublishes() {
        RecordingSubscriber subscriber = recordingSubscriber("only", 10,
                SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED);
        InProcessSignalBus bus = busWithSubscribers(subscriber);

        bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED));
        bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED));
        bus.publish(signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED));

        verify(subscribersProvider, times(1)).getIfAvailable(any());
        assertThat(subscriber.received).hasSize(3);
    }

    private SignalSubscriber trackingSubscriber(String name, int order, List<String> invoked) {
        return new SignalSubscriber() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean interestedIn(String signalType) {
                return true;
            }

            @Override
            public void onSignal(Signal signal) {
                invoked.add(name);
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    /** A subscriber that records every signal it was actually invoked with. */
    private static final class RecordingSubscriber implements SignalSubscriber {
        private final String name;
        private final int order;
        private final FailureMode failureMode;
        private final List<String> interestedTypes;
        private final List<Signal> received = new ArrayList<>();

        RecordingSubscriber(String name, int order, FailureMode failureMode, String... interestedTypes) {
            this.name = name;
            this.order = order;
            this.failureMode = failureMode;
            this.interestedTypes = List.of(interestedTypes);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean interestedIn(String signalType) {
            return interestedTypes.contains(signalType);
        }

        @Override
        public void onSignal(Signal signal) {
            received.add(signal);
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public FailureMode failureMode() {
            return failureMode;
        }
    }

    /** A subscriber whose onSignal always throws, with a configurable failure mode. */
    private static final class FailingSubscriber implements SignalSubscriber {
        private final String name;
        private final int order;
        private final FailureMode failureMode;

        FailingSubscriber(String name, int order, FailureMode failureMode) {
            this.name = name;
            this.order = order;
            this.failureMode = failureMode;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean interestedIn(String signalType) {
            return true;
        }

        @Override
        public void onSignal(Signal signal) {
            throw new RuntimeException("boom");
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public FailureMode failureMode() {
            return failureMode;
        }
    }
}
