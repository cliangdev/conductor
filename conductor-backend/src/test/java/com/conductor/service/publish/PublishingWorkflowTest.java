package com.conductor.service.publish;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishingWorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PublishPlatformRegistry registry = new PublishPlatformRegistry();
    private WorkflowDefinitionResolver resolver;
    private PublishingWorkflow publishingWorkflow;
    private Statechart marketing;
    private Statechart autopilot;
    private Statechart engineering;
    private Statechart legacyMarketing;
    private Statechart queued;

    @BeforeEach
    void setUp() throws Exception {
        resolver = mock(WorkflowDefinitionResolver.class);
        publishingWorkflow = new PublishingWorkflow(registry, resolver);
        marketing = chart("/schema/examples/marketing.workflow.json");
        autopilot = chart("/schema/examples/marketing-autopilot.workflow.json");
        engineering = chart("/schema/examples/engineering.workflow.json");
        // A snapshot pinned before publishes_from existed: identical to MARKETING, minus the marker.
        var legacy = mapper.readTree(getClass().getResourceAsStream("/schema/examples/marketing.workflow.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("publishes_from");
        legacyMarketing = Statechart.parse(legacy);
        queued = Statechart.parse(mapper.readTree("""
                {"id":"Q","noun":"Post","asset_types":["instagram_post"],"publishes_from":"QUEUED",
                 "statuses":[{"id":"DRAFT","category":"open","initial":true},
                             {"id":"QUEUED","category":"in_progress"},
                             {"id":"LIVE","category":"terminal","terminal":true},
                             {"id":"BROKEN","category":"in_progress"}],
                 "transitions":[{"from":"DRAFT","to":"QUEUED"},{"from":"QUEUED","to":"DRAFT"},
                                {"from":"QUEUED","to":"LIVE"},{"from":"QUEUED","to":"BROKEN"},
                                {"from":"BROKEN","to":"QUEUED"}]}
                """));
    }

    @Test
    void scheduledStatusIsDeclaredOrTheLegacyName() {
        assertThat(PublishingWorkflow.scheduledStatus(marketing)).contains("SCHEDULED");
        assertThat(PublishingWorkflow.scheduledStatus(autopilot)).contains("SCHEDULED");
        assertThat(PublishingWorkflow.scheduledStatus(legacyMarketing)).contains("SCHEDULED");
        assertThat(PublishingWorkflow.scheduledStatus(queued)).contains("QUEUED");
        assertThat(PublishingWorkflow.scheduledStatus(engineering)).isEmpty();
        assertThat(PublishingWorkflow.scheduledStatus(null)).isEmpty();
    }

    @Test
    void gateEdgesAreTheReviewGateAndEveryEntryIntoTheScheduledStatus() {
        assertThat(PublishingWorkflow.isGateEdge(marketing, "IN_REVIEW", "APPROVED")).isTrue();
        assertThat(PublishingWorkflow.isGateEdge(marketing, "APPROVED", "SCHEDULED")).isTrue();
        assertThat(PublishingWorkflow.isGateEdge(marketing, "FAILED", "SCHEDULED")).isTrue();
        assertThat(PublishingWorkflow.isGateEdge(marketing, "SCHEDULED", "APPROVED")).as("Unschedule stays free").isFalse();
        assertThat(PublishingWorkflow.isGateEdge(marketing, "DRAFT", "IN_REVIEW")).isFalse();
        assertThat(PublishingWorkflow.isGateEdge(marketing, "DRAFT", "SCHEDULED")).as("not an edge").isFalse();
        assertThat(PublishingWorkflow.isGateEdge(autopilot, "DRAFT", "SCHEDULED")).isTrue();
        assertThat(PublishingWorkflow.isGateEdge(autopilot, "SCHEDULED", "DRAFT")).isFalse();
        assertThat(PublishingWorkflow.isGateEdge(engineering, "CODE_REVIEW", "DONE")).as("a review gate is a gate edge on any chart").isTrue();
        assertThat(PublishingWorkflow.isGateEdge(null, "A", "B")).isFalse();
    }

    @Test
    void publishedAndFailedStatusesAreDerivedFromTheScheduledStatus() {
        assertThat(PublishingWorkflow.publishedStatus(marketing)).contains("PUBLISHED");
        assertThat(PublishingWorkflow.failedStatus(marketing)).contains("FAILED");
        assertThat(PublishingWorkflow.publishedStatus(autopilot)).contains("PUBLISHED");
        assertThat(PublishingWorkflow.failedStatus(autopilot)).as("DRAFT is before scheduling, not a failure").contains("FAILED");
        assertThat(PublishingWorkflow.publishedStatus(queued)).contains("LIVE");
        assertThat(PublishingWorkflow.failedStatus(queued)).contains("BROKEN");
        assertThat(PublishingWorkflow.publishedStatus(engineering)).isEmpty();
    }

    @Test
    void theRegionBeforeSchedulingAndAfterItPartitionTheChart() {
        assertThat(PublishingWorkflow.statusesBeforeScheduling(marketing))
                .containsExactlyInAnyOrder("DRAFT", "IN_REVIEW", "CHANGES_REQUESTED", "APPROVED");
        assertThat(PublishingWorkflow.statusesBeforeScheduling(autopilot)).containsExactly("DRAFT");
        for (String status : new String[] {"SCHEDULED", "PUBLISHED", "FAILED"}) {
            assertThat(PublishingWorkflow.isScheduledOrLater(marketing, status)).as(status).isTrue();
            assertThat(PublishingWorkflow.isScheduledOrLater(autopilot, status)).as(status).isTrue();
        }
        for (String status : new String[] {"DRAFT", "IN_REVIEW", "CHANGES_REQUESTED", "APPROVED"}) {
            assertThat(PublishingWorkflow.isScheduledOrLater(marketing, status)).as(status).isFalse();
        }
        assertThat(PublishingWorkflow.isScheduledOrLater(engineering, "DONE")).isFalse();
        assertThat(PublishingWorkflow.isScheduledOrLater(marketing, null)).isFalse();
    }

    @Test
    void aPostIsInItsOwnWorkflowsScheduledStatus() {
        WorkItem post = post("QUEUED", "Q", 3);
        when(resolver.resolve(eq("proj"), eq("Q"), eq(3))).thenReturn(Optional.of(queued));
        assertThat(publishingWorkflow.isInScheduledStatus(post)).isTrue();
        assertThat(publishingWorkflow.scheduledStatusOf(post)).isEqualTo("QUEUED");

        post.setCurrentStatus("SCHEDULED");
        assertThat(publishingWorkflow.isInScheduledStatus(post)).as("the legacy name is not this chart's").isFalse();
    }

    @Test
    void anUnresolvableWorkflowFallsBackToTheLegacyName() {
        WorkItem post = post("SCHEDULED", "GONE", 1);
        when(resolver.resolve(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(publishingWorkflow.isInScheduledStatus(post)).isTrue();
        assertThat(publishingWorkflow.isInScheduledStatus(null)).isFalse();
        assertThat(publishingWorkflow.scheduledStatusOf(null)).isEqualTo("SCHEDULED");
    }

    @Test
    void declaresPublishingReadsTheRegistry() {
        assertThat(publishingWorkflow.declaresPublishing(marketing)).isTrue();
        assertThat(publishingWorkflow.declaresPublishing(engineering)).isFalse();
    }

    private Statechart chart(String resource) throws Exception {
        return Statechart.parse(mapper.readTree(getClass().getResourceAsStream(resource)));
    }

    private static WorkItem post(String status, String workflow, Integer version) {
        Project project = new Project();
        project.setId("proj");
        WorkItem item = new WorkItem();
        item.setId("post-1");
        item.setProject(project);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(version);
        item.setCurrentStatus(status);
        return item;
    }
}
