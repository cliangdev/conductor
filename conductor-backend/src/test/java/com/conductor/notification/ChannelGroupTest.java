package com.conductor.notification;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelGroupTest {

    @Test
    void workflowsGroupContainsRunFailedAndAutoPaused() {
        assertThat(ChannelGroup.WORKFLOWS.getEventTypes())
                .containsExactlyInAnyOrder(EventType.WORKFLOW_RUN_FAILED, EventType.WORKFLOW_AUTO_PAUSED);
    }

    @Test
    void forEventType_resolvesWorkflowRunFailedToWorkflowsGroup() {
        assertThat(ChannelGroup.forEventType(EventType.WORKFLOW_RUN_FAILED)).contains(ChannelGroup.WORKFLOWS);
    }

    @Test
    void forEventType_resolvesWorkflowAutoPausedToWorkflowsGroup() {
        // Regression coverage for the gap this PR closes: WORKFLOW_AUTO_PAUSED used to belong to no
        // group at all, so NotificationDeliveryService.deliver silently no-op'd for it -- it never
        // produced a Discord message.
        assertThat(ChannelGroup.forEventType(EventType.WORKFLOW_AUTO_PAUSED)).contains(ChannelGroup.WORKFLOWS);
    }

    @Test
    void assetAddedAndGithubPullRequestRemainUngrouped() {
        assertThat(ChannelGroup.forEventType(EventType.ASSET_ADDED)).isEmpty();
        assertThat(ChannelGroup.forEventType(EventType.GITHUB_PULL_REQUEST)).isEmpty();
    }

    @Test
    void everyChannelGroupHasADistinctLabel() {
        assertThat(Arrays.stream(ChannelGroup.values()).map(ChannelGroup::getLabel))
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .hasSize(ChannelGroup.values().length);
    }

    /**
     * The general groups must still partition the event types between them, or {@code forEventType} would
     * resolve one arbitrarily by declaration order. A *specialised* group is exempt precisely because it is
     * never reachable that way — only {@code forEvent} can select it, from the event's own metadata.
     */
    @Test
    void noEventTypeBelongsToMoreThanOneGeneralGroup() {
        assertThat(Arrays.stream(ChannelGroup.values())
                .filter(g -> !g.isSpecialised())
                .flatMap(g -> g.getEventTypes().stream()))
                .doesNotHaveDuplicates();
    }

    @Test
    void aSpecialisedGroupIsNeverResolvedFromAnEventTypeAlone() {
        // Publishing shares WORK_ITEM_STATUS_CHANGED with Issues. Without this rule, which of the two you
        // got would depend on the order they happen to be declared in.
        assertThat(ChannelGroup.PUBLISHING.isSpecialised()).isTrue();
        assertThat(ChannelGroup.forEventType(EventType.WORK_ITEM_STATUS_CHANGED))
                .contains(ChannelGroup.ISSUES);
        assertThat(ChannelGroup.forEventType(EventType.POST_AWAITING_MANUAL)).isEmpty();
    }

    @Test
    void anEventFromAPublishingWorkflowPrefersPublishingAndFallsBackToIssues() {
        // Ordered, not singular: a project that never configured a Publishing channel keeps getting its
        // Post activity in the Issues channel rather than silently losing it.
        assertThat(ChannelGroup.forEvent(EventType.WORK_ITEM_STATUS_CHANGED,
                Map.of(ChannelGroup.META_PUBLISHES, "true")))
                .containsExactly(ChannelGroup.PUBLISHING, ChannelGroup.ISSUES);
    }

    @Test
    void anEventFromAnyOtherWorkflowNeverReachesThePublishingChannel() {
        assertThat(ChannelGroup.forEvent(EventType.WORK_ITEM_STATUS_CHANGED, Map.of()))
                .containsExactly(ChannelGroup.ISSUES);
        assertThat(ChannelGroup.forEvent(EventType.WORK_ITEM_STATUS_CHANGED,
                Map.of(ChannelGroup.META_PUBLISHES, "false")))
                .containsExactly(ChannelGroup.ISSUES);
        assertThat(ChannelGroup.forEvent(EventType.WORK_ITEM_STATUS_CHANGED, null))
                .containsExactly(ChannelGroup.ISSUES);
    }

    @Test
    void aManualPublishAlertHasNowhereToGoButThePublishingChannel() {
        // It belongs to no general group, so a project with no Publishing channel simply does not get it —
        // there is no sensible fallback for an event only publishing produces.
        assertThat(ChannelGroup.forEvent(EventType.POST_AWAITING_MANUAL,
                Map.of(ChannelGroup.META_PUBLISHES, "true")))
                .containsExactly(ChannelGroup.PUBLISHING);
        assertThat(ChannelGroup.forEvent(EventType.POST_AWAITING_MANUAL, Map.of())).isEmpty();
    }
}
