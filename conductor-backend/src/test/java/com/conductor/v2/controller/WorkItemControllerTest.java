package com.conductor.v2.controller;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.model.WorkItemResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test (no Spring) for {@link WorkItemController#toResponse}: the null-{@code
 * createdBy}/{@code createdByLabel} fallback for a machine-authored Work Item (V111 --
 * {@code coordinator:create_work_item} creates rows this way via {@code
 * WorkItemService#createWorkItem(..., ProjectActor)}).
 */
class WorkItemControllerTest {

    private WorkItem workItem(User createdBy, String createdByLabel) {
        Project project = new Project();
        project.setId("proj-1");
        project.setKey("COND");

        WorkItem item = new WorkItem();
        item.setId("wi-1");
        item.setProject(project);
        item.setType("PRD");
        item.setTitle("Title");
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(3);
        item.setCreatedBy(createdBy);
        item.setCreatedByLabel(createdByLabel);
        item.setCreatedAt(OffsetDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());
        return item;
    }

    @Test
    void machineAuthoredWorkItemSerializesNullCreatedByAndPopulatedLabel() {
        WorkItem item = workItem(null, "Agent (ceo)");

        WorkItemResponse response = WorkItemController.toResponse(item, 0L);

        assertThat(response.getCreatedBy()).isNull();
        assertThat(response.getCreatedByLabel()).isEqualTo("Agent (ceo)");
    }

    @Test
    void humanAuthoredWorkItemSerializesCreatedByIdAndNullLabel() {
        User user = new User();
        user.setId("user-1");
        WorkItem item = workItem(user, null);

        WorkItemResponse response = WorkItemController.toResponse(item, 0L);

        assertThat(response.getCreatedBy()).isEqualTo("user-1");
        assertThat(response.getCreatedByLabel()).isNull();
    }
}
