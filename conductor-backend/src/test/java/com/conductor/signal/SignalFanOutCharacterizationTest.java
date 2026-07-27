package com.conductor.signal;

import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.signal.KnowledgeSignalSink;
import com.conductor.notification.DiscordProvider;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.notification.signal.NotificationSignalSink;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemService;
import com.conductor.service.signal.PullRequestMergeSubscriber;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.signal.LifecycleSignalSubscriber;
import com.conductor.workflow.signal.WorkflowAutomationSignalSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Composition-level characterization of the ordered fan-out, over a REAL {@link InProcessSignalBus}
 * wired to all five REAL production subscribers (their collaborators are mocked, the subscribers are
 * not). Successor to the pre-refactor {@code NotificationDispatcherFanOutCharacterizationTest}, whose
 * subject class no longer exists -- the behaviour it pinned is a property of the fan-out, not of the
 * deleted dispatcher, so it is re-pinned here at the new entry point rather than dropped.
 *
 * <p><b>Why this exists alongside the narrower tests.</b> {@code InProcessSignalBusTest} proves the
 * PROPAGATE/SWALLOW <i>mechanism</i> using stub subscribers, and {@code NotificationSignalSinkTest}
 * asserts the sink <i>declares</i> {@code PROPAGATE}. Neither composes: wrap
 * {@code deliveryService.deliver(...)} in a try/catch inside {@link NotificationSignalSink#onSignal}
 * and both of those still pass while the real behaviour silently changes. Only an end-to-end assertion
 * over the real beans catches that, and the behaviour in question is load-bearing -- it is a live
 * branch of the GitHub webhook retry contract (a thrown exception marks the {@code webhook_event}
 * FAILED so {@code WebhookRetryScheduler} retries it).
 */
@ExtendWith(MockitoExtension.class)
class SignalFanOutCharacterizationTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private NotificationGroupConfigRepository groupConfigRepository;
    @Mock private DiscordProvider discordProvider;
    @Mock private WorkflowTriggerService workflowTriggerService;
    @Mock private LifecycleTriggerDispatcher lifecycleTriggerDispatcher;
    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private ProjectSettingsService projectSettingsService;
    @Mock private WorkItemService workItemService;
    @Mock private ObjectProvider<List<SignalSubscriber>> subscribersProvider;

    private SignalBus signalBus;

    @BeforeEach
    void setUp() {
        // A REAL NotificationDeliveryService over the mocked repository, not a mock of it: the point of
        // notificationLookupFailurePropagates... is that the genuine, unguarded config lookup is in the
        // path. Mocking the delivery service would make that test assert nothing about production code.
        NotificationDeliveryService deliveryService =
                new NotificationDeliveryService(groupConfigRepository, discordProvider);

        List<SignalSubscriber> subscribers = List.of(
                new NotificationSignalSink(deliveryService, new NotificationSignalMapper()),
                new WorkflowAutomationSignalSubscriber(workflowTriggerService),
                new LifecycleSignalSubscriber(lifecycleTriggerDispatcher),
                new KnowledgeSignalSink(knowledgeIngestionService, projectSettingsService, new ObjectMapper()),
                new PullRequestMergeSubscriber(workItemService));
        lenient().when(subscribersProvider.getIfAvailable(any())).thenReturn(subscribers);

        signalBus = new InProcessSignalBus(subscribersProvider);
    }

    private Signal statusChanged() {
        return Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, PROJECT_ID, "conductor:wi-1",
                Instant.now(), Map.of("workItemId", "wi-1", "fromStatus", "A", "toStatus", "B"),
                new SignalOrigin("test", null));
    }

    @Test
    void fanOutOrderIsNotificationThenWorkflowThenLifecycleThenKnowledge() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(java.util.Optional.empty());
        Signal signal = statusChanged();

        signalBus.publish(signal);

        InOrder inOrder = inOrder(groupConfigRepository, workflowTriggerService, lifecycleTriggerDispatcher,
                projectSettingsService);
        inOrder.verify(groupConfigRepository).findByProjectIdAndChannelGroup(any(), any());
        inOrder.verify(workflowTriggerService).onConductorEvent(signal);
        inOrder.verify(lifecycleTriggerDispatcher).onConductorEvent(signal);
        inOrder.verify(projectSettingsService).isKnowledgeEnabled(PROJECT_ID);
    }

    /**
     * The single most important assertion in this file, and the reason it exists. Delivery runs FIRST
     * and its config lookup is deliberately unguarded, so a DB failure there escapes {@code publish()}
     * and the remaining subscribers never run. See {@code NotificationDeliveryService}'s javadoc.
     */
    @Test
    void notificationLookupFailurePropagatesAndShortCircuitsAllLaterSubscribers() {
        RuntimeException boom = new RuntimeException("db down");
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> signalBus.publish(statusChanged())).isSameAs(boom);

        verifyNoInteractions(workflowTriggerService, lifecycleTriggerDispatcher, projectSettingsService,
                knowledgeIngestionService);
    }

    @Test
    void aSwallowingSubscriberFailureDoesNotStopTheOnesAfterIt() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(java.util.Optional.empty());
        Signal signal = statusChanged();
        doThrow(new RuntimeException("boom")).when(workflowTriggerService).onConductorEvent(signal);

        assertThatNoException().isThrownBy(() -> signalBus.publish(signal));

        verify(lifecycleTriggerDispatcher).onConductorEvent(signal);
        verify(projectSettingsService).isKnowledgeEnabled(PROJECT_ID);
    }

    @Test
    void lifecycleFailureDoesNotStopKnowledgeIngestion() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(java.util.Optional.empty());
        Signal signal = statusChanged();
        doThrow(new RuntimeException("boom")).when(lifecycleTriggerDispatcher).onConductorEvent(signal);

        assertThatNoException().isThrownBy(() -> signalBus.publish(signal));

        verify(projectSettingsService).isKnowledgeEnabled(PROJECT_ID);
    }

    /**
     * Every subscriber now filters by exact signal type in {@code interestedIn}, so a type no subscriber
     * claims must fan out to nothing at all -- and must not throw. Pins that narrowing {@code
     * interestedIn} in A6 did not make an unclaimed type explode.
     */
    @ParameterizedTest
    @EnumSource(value = UnclaimedType.class)
    void unclaimedSignalTypesFanOutToNothing(UnclaimedType type) {
        Signal signal = Signal.of(type.signalType, PROJECT_ID, null, Instant.now(), Map.of(),
                new SignalOrigin("test", null));

        assertThatNoException().isThrownBy(() -> signalBus.publish(signal));

        verifyNoInteractions(workflowTriggerService, lifecycleTriggerDispatcher, knowledgeIngestionService,
                workItemService);
    }

    private enum UnclaimedType {
        REVIEWER_ASSIGNED(SignalTypes.CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED),
        COMMENT_ADDED(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED),
        ASSET_ADDED(SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED),
        WORKFLOW_AUTO_PAUSED(SignalTypes.CONDUCTOR_WORKFLOW_AUTO_PAUSED);

        private final String signalType;

        UnclaimedType(String signalType) {
            this.signalType = signalType;
        }
    }

    /**
     * As of A8, {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED} is no longer unclaimed -- {@code
     * KnowledgeSignalSink} and {@code PullRequestMergeSubscriber} both claim it, in that order (order
     * {@link SignalDispatchOrder#KNOWLEDGE} then {@link SignalDispatchOrder#PULL_REQUEST_MERGE}),
     * matching the pre-A8 {@code submitMergedPrKnowledge} -> {@code completeFromPullRequest} sequence
     * inline in {@code GitHubConnector}. This also re-pins the prefix-collision guard formerly covered by
     * {@code UnclaimedType.PULL_REQUEST_MERGED}: a merged-PR signal must NOT reach {@code
     * NotificationSignalSink} or {@code WorkflowAutomationSignalSubscriber}, whose {@code interestedIn}
     * checks {@link SignalTypes#GITHUB_PULL_REQUEST} by exact equality -- {@code
     * "github.pull_request_merged"} must never be (mis)treated as a prefix match for {@code
     * "github.pull_request"}.
     */
    @Test
    void mergedPullRequestReachesKnowledgeThenPullRequestMergeSubscriber_andNoOthers() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Signal signal = Signal.of(SignalTypes.GITHUB_PULL_REQUEST_MERGED, PROJECT_ID, "3", Instant.now(),
                Map.of(
                        "repoFullName", "x/y",
                        "number", 3,
                        "body", "closes conductor/PROJ-1",
                        "htmlUrl", "https://github.com/x/y/pull/3"),
                new SignalOrigin("test", null));

        signalBus.publish(signal);

        InOrder inOrder = inOrder(knowledgeIngestionService, workItemService);
        inOrder.verify(knowledgeIngestionService).submit(any());
        inOrder.verify(workItemService).completeFromPullRequest(PROJECT_ID, "PROJ", 1,
                "https://github.com/x/y/pull/3");
        // The prefix-collision guard: not delivered where "github.pull_request" (unmerged) is handled,
        // and not delivered to notification delivery (no EventType maps to the merged type).
        verifyNoInteractions(groupConfigRepository, workflowTriggerService, lifecycleTriggerDispatcher);
    }
}
