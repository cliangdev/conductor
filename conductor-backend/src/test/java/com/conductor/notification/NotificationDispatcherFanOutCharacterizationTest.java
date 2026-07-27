package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.knowledge.KnowledgeEventTap;
import com.conductor.knowledge.signal.KnowledgeSignalSink;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.notification.signal.NotificationSignalSink;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.signal.InProcessSignalBus;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalSubscriber;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.signal.LifecycleSignalSubscriber;
import com.conductor.workflow.signal.WorkflowAutomationSignalSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
 * Characterization tests for {@link NotificationDispatcher#dispatch}'s ordered fan-out. These pin
 * CURRENT behaviour -- including the asymmetry between the Discord delivery step (bare, un-caught
 * lookup) and the four downstream consumers (each individually try/caught) -- across the A3 refactor
 * from "hardcoded fan-out in one method" to "translator + SignalBus + four SignalSubscriber beans".
 * {@code dispatcher} here is wired over a REAL {@link InProcessSignalBus} and REAL subscriber instances
 * (each constructed with mocked collaborators), so this still exercises genuine end-to-end fan-out
 * rather than a mock of the bus. Do not "fix" anything here even where the behaviour looks like a bug;
 * see {@link #notificationLookupFailurePropagatesAndShortCircuitsAllConsumers()}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherFanOutCharacterizationTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private NotificationGroupConfigRepository groupConfigRepository;
    @Mock private DiscordProvider discordProvider;
    @Mock private WorkflowTriggerService workflowTriggerService;
    @Mock private LifecycleTriggerDispatcher lifecycleTriggerDispatcher;
    @Mock private KnowledgeEventTap knowledgeEventTap;
    @Mock private ObjectProvider<List<SignalSubscriber>> subscribersProvider;

    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        NotificationSignalMapper mapper = new NotificationSignalMapper();

        // A REAL delivery service over the mocked repository/provider, not a mock of it: these tests
        // assert that a failing config *lookup* escapes dispatch(), which is only meaningful if the
        // real (unguarded) lookup is in the path.
        NotificationDeliveryService deliveryService =
                new NotificationDeliveryService(groupConfigRepository, discordProvider);

        List<SignalSubscriber> subscribers = List.of(
                new NotificationSignalSink(deliveryService, mapper),
                new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper),
                new LifecycleSignalSubscriber(lifecycleTriggerDispatcher, mapper),
                new KnowledgeSignalSink(knowledgeEventTap, mapper));
        lenient().when(subscribersProvider.getIfAvailable(any())).thenReturn(subscribers);

        SignalBus signalBus = new InProcessSignalBus(subscribersProvider);
        dispatcher = new NotificationDispatcher(signalBus, mapper);
    }

    private NotificationEvent eventOf(EventType type) {
        return NotificationEvent.of(type, PROJECT_ID, Map.of("workItemId", "wi-1"));
    }

    @Test
    void fanOutOrderIs_notification_workflow_prWorkflow_lifecycle_knowledge() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(Optional.empty());
        NotificationEvent event = eventOf(EventType.WORK_ITEM_STATUS_CHANGED);

        dispatcher.dispatch(event);

        InOrder inOrder = inOrder(groupConfigRepository, workflowTriggerService, lifecycleTriggerDispatcher,
                knowledgeEventTap);
        inOrder.verify(groupConfigRepository).findByProjectIdAndChannelGroup(any(), any());
        inOrder.verify(workflowTriggerService).onConductorEvent(event);
        inOrder.verify(workflowTriggerService).onGitHubPullRequest(event);
        inOrder.verify(lifecycleTriggerDispatcher).onConductorEvent(event);
        inOrder.verify(knowledgeEventTap).onConductorEvent(event);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyConsumerIsInvokedForEveryEventType(EventType type) {
        // Pins that dispatch() does NOT filter by event type before fanning out to the four consumers --
        // each consumer is responsible for its own type filtering internally. lenient(): three event
        // types (ASSET_ADDED, WORKFLOW_AUTO_PAUSED, GITHUB_PULL_REQUEST) have no ChannelGroup, so
        // sendNotification short-circuits before ever calling the repository for those types.
        lenient().when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(Optional.empty());
        NotificationEvent event = eventOf(type);

        dispatcher.dispatch(event);

        verify(workflowTriggerService).onConductorEvent(event);
        verify(workflowTriggerService).onGitHubPullRequest(event);
        verify(lifecycleTriggerDispatcher).onConductorEvent(event);
        verify(knowledgeEventTap).onConductorEvent(event);
    }

    private enum Consumer {
        WORKFLOW_CONDUCTOR_EVENT, WORKFLOW_GITHUB_PR, LIFECYCLE, KNOWLEDGE
    }

    static Stream<Consumer> consumers() {
        return Stream.of(Consumer.values());
    }

    @ParameterizedTest
    @MethodSource("consumers")
    void eachConsumerFailureIsIsolatedFromTheOtherThree(Consumer failing) {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenReturn(Optional.empty());
        NotificationEvent event = eventOf(EventType.WORK_ITEM_STATUS_CHANGED);

        switch (failing) {
            case WORKFLOW_CONDUCTOR_EVENT ->
                    doThrow(new RuntimeException("boom")).when(workflowTriggerService).onConductorEvent(event);
            case WORKFLOW_GITHUB_PR ->
                    doThrow(new RuntimeException("boom")).when(workflowTriggerService).onGitHubPullRequest(event);
            case LIFECYCLE ->
                    doThrow(new RuntimeException("boom")).when(lifecycleTriggerDispatcher).onConductorEvent(event);
            case KNOWLEDGE ->
                    doThrow(new RuntimeException("boom")).when(knowledgeEventTap).onConductorEvent(event);
        }

        assertThatNoException().isThrownBy(() -> dispatcher.dispatch(event));

        verify(workflowTriggerService).onConductorEvent(event);
        verify(workflowTriggerService).onGitHubPullRequest(event);
        verify(lifecycleTriggerDispatcher).onConductorEvent(event);
        verify(knowledgeEventTap).onConductorEvent(event);
    }

    /**
     * Most important test in the file: {@code sendNotification}'s {@code groupConfigRepository} lookup is
     * NOT wrapped in a try/catch at the {@code dispatch()} level (unlike the four downstream consumers,
     * which each get their own try/catch -> log.warn). A repository failure therefore escapes {@code
     * dispatch()} entirely and short-circuits every other consumer -- workflow triggers, lifecycle
     * cascades, and knowledge ingestion all silently never run for this event. This is arguably a bug
     * (one flaky Discord-config lookup can suppress a Work Item's lifecycle auto-transition), but it is
     * CURRENT behaviour and must be preserved until a deliberate fix.
     */
    @Test
    void notificationLookupFailurePropagatesAndShortCircuitsAllConsumers() {
        RuntimeException boom = new RuntimeException("db down");
        when(groupConfigRepository.findByProjectIdAndChannelGroup(any(), any())).thenThrow(boom);
        NotificationEvent event = eventOf(EventType.WORK_ITEM_STATUS_CHANGED);

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isSameAs(boom);

        verifyNoInteractions(workflowTriggerService, lifecycleTriggerDispatcher, knowledgeEventTap);
    }

    @Test
    void notificationSendFailureIsSwallowedAndConsumersStillRun() {
        NotificationGroupConfig config = enabledConfig();
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));
        NotificationEvent event = eventOf(EventType.WORK_ITEM_STATUS_CHANGED);
        when(discordProvider.format(event)).thenThrow(new RuntimeException("format failed"));

        assertThatNoException().isThrownBy(() -> dispatcher.dispatch(event));

        verify(workflowTriggerService).onConductorEvent(event);
        verify(workflowTriggerService).onGitHubPullRequest(event);
        verify(lifecycleTriggerDispatcher).onConductorEvent(event);
        verify(knowledgeEventTap).onConductorEvent(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSET_ADDED", "WORKFLOW_AUTO_PAUSED", "GITHUB_PULL_REQUEST"})
    void noChannelGroupTypesSkipDeliveryButStillReachAllConsumers(String typeName) {
        EventType type = EventType.valueOf(typeName);
        NotificationEvent event = eventOf(type);

        dispatcher.dispatch(event);

        verifyNoInteractions(discordProvider);
        verify(workflowTriggerService).onConductorEvent(event);
        verify(workflowTriggerService).onGitHubPullRequest(event);
        verify(lifecycleTriggerDispatcher).onConductorEvent(event);
        verify(knowledgeEventTap).onConductorEvent(event);
    }

    private NotificationGroupConfig enabledConfig() {
        NotificationGroupConfig config = new NotificationGroupConfig();
        config.setProjectId(PROJECT_ID);
        config.setChannelGroup(ChannelGroup.ISSUES);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl("https://discord.com/api/webhooks/1/token");
        config.setEnabled(true);
        config.setEnabledEventTypes(Set.of("WORK_ITEM_STATUS_CHANGED"));
        return config;
    }
}
