package com.conductor.signal;

import com.conductor.knowledge.signal.KnowledgeSignalSink;
import com.conductor.notification.signal.NotificationSignalSink;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.workflow.signal.LifecycleSignalSubscriber;
import com.conductor.workflow.signal.WorkflowAutomationSignalSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Full Spring context test proving the four production {@link SignalSubscriber} beans resolve without
 * a circular-dependency failure ({@code BeanCurrentlyInCreationException}) and register in the expected
 * dispatch order. {@code BeanConstructorResolutionTest} is reflection-only and does NOT catch this class
 * of failure -- it never actually asks the context to construct the graph. See {@link
 * InProcessSignalBus}'s javadoc for the latent cycle this exercises: {@code WorkItemService ->
 * NotificationDispatcher -> SignalBus -> LifecycleSignalSubscriber -> LifecycleTriggerDispatcher ->
 * WorkItemService}, broken by {@code InProcessSignalBus}'s {@code ObjectProvider}-based lazy,
 * post-refresh subscriber resolution.
 */
class SignalBusWiringContextTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private SignalBus signalBus;

    @Autowired
    private List<SignalSubscriber> subscribers;

    @Test
    void allFourProductionSubscribersAreRegisteredInOrder() {
        List<SignalSubscriber> sorted = subscribers.stream()
                .sorted(Comparator.comparingInt(SignalSubscriber::order))
                .toList();

        assertThat(sorted).extracting(Object::getClass).containsExactly(
                NotificationSignalSink.class,
                WorkflowAutomationSignalSubscriber.class,
                LifecycleSignalSubscriber.class,
                KnowledgeSignalSink.class);
        assertThat(sorted).extracting(SignalSubscriber::order).containsExactly(10, 20, 30, 40);
    }

    @Test
    void publishResolvesTheFullSubscriberChainWithoutACircularDependencyFailure() {
        // ASSET_ADDED has no ChannelGroup and is neither WORK_ITEM_STATUS_CHANGED nor
        // GITHUB_PULL_REQUEST, so every production subscriber's internal type guard makes this a real
        // no-op fan-out -- the point is proving the bean graph resolves and publish() completes
        // end-to-end through real (not mocked) beans, not exercising any one subscriber's business
        // logic (that's covered by the per-subscriber unit tests).
        Signal signal = Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED, "proj-context-test", null,
                Instant.now(), Map.of(), new SignalOrigin("test", null));

        assertThatNoException().isThrownBy(() -> signalBus.publish(signal));
    }
}
