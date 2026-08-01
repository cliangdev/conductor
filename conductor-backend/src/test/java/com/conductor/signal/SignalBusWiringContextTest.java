package com.conductor.signal;

import com.conductor.disposition.DispositionPolicySubscriber;
import com.conductor.knowledge.signal.KnowledgeSignalSink;
import com.conductor.notification.signal.NotificationSignalSink;
import com.conductor.service.signal.PullRequestMergeSubscriber;
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
 * Full Spring context test proving the production {@link SignalSubscriber} beans resolve without a
 * circular-dependency failure ({@code BeanCurrentlyInCreationException}) and register in the expected
 * dispatch order. {@code BeanConstructorResolutionTest} is reflection-only and does NOT catch this class
 * of failure -- it never actually asks the context to construct the graph. See {@link
 * InProcessSignalBus}'s javadoc for the latent cycle this exercises: {@code WorkItemService ->
 * NotificationDispatcher -> SignalBus -> LifecycleSignalSubscriber -> LifecycleTriggerDispatcher ->
 * WorkItemService}, broken by {@code InProcessSignalBus}'s {@code ObjectProvider}-based lazy,
 * post-refresh subscriber resolution. {@code PullRequestMergeSubscriber} (added in A8) closes the same
 * kind of cycle a second way -- {@code GitHubConnector -> SignalBus -> PullRequestMergeSubscriber ->
 * WorkItemService} -- so it belongs in this same proof. {@code DispositionPolicySubscriber} (added in
 * B6) has no such cycle -- it depends only on {@code DispositionPolicyCache}/{@code
 * DispositionPolicyRepository} -- but is included here too so this test stays the single source of
 * truth for "every production subscriber is registered, in order."
 */
class SignalBusWiringContextTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private SignalBus signalBus;

    @Autowired
    private List<SignalSubscriber> subscribers;

    @Test
    void allSixProductionSubscribersAreRegisteredInOrder() {
        List<SignalSubscriber> sorted = subscribers.stream()
                .sorted(Comparator.comparingInt(SignalSubscriber::order))
                .toList();

        assertThat(sorted).extracting(Object::getClass).containsExactly(
                NotificationSignalSink.class,
                WorkflowAutomationSignalSubscriber.class,
                LifecycleSignalSubscriber.class,
                KnowledgeSignalSink.class,
                PullRequestMergeSubscriber.class,
                DispositionPolicySubscriber.class);
        assertThat(sorted).extracting(SignalSubscriber::order).containsExactly(10, 20, 30, 40, 50, 60);
    }

    @Test
    void publishResolvesTheFullSubscriberChainWithoutACircularDependencyFailure() {
        // ASSET_ADDED has no ChannelGroup and is none of WORK_ITEM_STATUS_CHANGED, GITHUB_PULL_REQUEST,
        // or GITHUB_PULL_REQUEST_MERGED, so every production subscriber's internal type guard makes this
        // a real no-op fan-out -- the point is proving the bean graph resolves and publish() completes
        // end-to-end through real (not mocked) beans, not exercising any one subscriber's business
        // logic (that's covered by the per-subscriber unit tests).
        Signal signal = Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED, "proj-context-test", null,
                Instant.now(), Map.of(), new SignalOrigin("test", null));

        assertThatNoException().isThrownBy(() -> signalBus.publish(signal));
    }
}
