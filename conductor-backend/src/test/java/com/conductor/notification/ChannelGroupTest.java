package com.conductor.notification;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

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

    /** No event type may sit in two groups, or {@code forEventType} would resolve it arbitrarily. */
    @Test
    void noEventTypeBelongsToMoreThanOneGroup() {
        assertThat(Arrays.stream(ChannelGroup.values()).flatMap(g -> g.getEventTypes().stream()))
                .doesNotHaveDuplicates();
    }
}
