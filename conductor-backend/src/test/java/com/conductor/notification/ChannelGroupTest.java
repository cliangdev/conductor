package com.conductor.notification;

import org.junit.jupiter.api.Test;

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
        assertThat(ChannelGroup.ISSUES.getLabel()).isEqualTo("Issues");
        assertThat(ChannelGroup.MEMBERS.getLabel()).isEqualTo("Members");
        assertThat(ChannelGroup.WORKFLOWS.getLabel()).isEqualTo("Workflows");
    }
}
