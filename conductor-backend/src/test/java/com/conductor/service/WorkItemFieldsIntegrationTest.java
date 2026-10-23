package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.MissingFieldsException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Typed Work Item fields and due dates end to end on the built-in MARKETING_CHANGES Workflow: what a
 * create/patch stores and refuses, the required-fields gate on transitions, and the {@code dueBefore}
 * filter. Against a real database because the fields live in a JSONB column and the filter in a query.
 */
class WorkItemFieldsIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final String WORKFLOW = "MARKETING_CHANGES";

    @Autowired private WorkItemService workItemService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private WorkflowSeeder workflowSeeder;

    private User caller;
    private Project project;

    @BeforeEach
    void setUp() {
        caller = new User();
        caller.setFirebaseUid("fields-uid-" + UUID.randomUUID());
        caller.setEmail(UUID.randomUUID() + "@example.com");
        caller.setName("Field Author");
        caller = userRepository.save(caller);

        project = new Project();
        project.setName("Fields Project");
        project.setKey("FD" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(caller);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(caller);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        workflowSeeder.seedEngineering(project);
        workflowSeeder.seedMarketingChanges(project);
    }

    private WorkItem create(String title, Map<String, Object> fields, LocalDate dueOn) {
        return workItemService.createWorkItem(project.getId(), "LISTING", title, null, WORKFLOW,
                null, fields, dueOn, caller);
    }

    private WorkItem patch(WorkItem item, String status, Map<String, Object> fields, String dueOn) {
        return workItemService.patchWorkItem(project.getId(), item.getId(), null, null, status, null, null,
                null, null, null, fields, dueOn, caller);
    }

    private void approve(WorkItem item) {
        Review review = new Review();
        review.setWorkItemId(item.getId());
        review.setReviewerId(caller.getId());
        review.setVerdict("APPROVED");
        reviewRepository.save(review);
    }

    @Test
    void createStoresFieldsAndDueDateAndReadsThemBack() {
        WorkItem item = create("Shorter title", Map.of("hypothesis", "Clicks go up"), LocalDate.of(2026, 11, 5));

        WorkItem read = workItemService.getWorkItemEntity(project.getId(), item.getId(), caller);
        assertThat(read.getFields().get("hypothesis").asText()).isEqualTo("Clicks go up");
        assertThat(read.getDueOn()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(read.getCurrentStatus()).isEqualTo("PLANNED");
    }

    @Test
    void createRejectsUnknownKeysAndFieldsOnAWorkflowWithoutAny() {
        assertThatThrownBy(() -> create("Bad", Map.of("mood", "good"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("unknown field 'mood'");
        assertThatThrownBy(() -> workItemService.createWorkItem(project.getId(), "PRD", "Eng", null,
                "ENGINEERING", null, Map.of("hypothesis", "x"), null, caller))
                .isInstanceOf(BusinessException.class).hasMessageContaining("defines no fields");
    }

    @Test
    void patchMergesFieldsAndSetsAndClearsTheDueDate() {
        WorkItem item = create("Shorter title", Map.of("hypothesis", "Clicks go up"), null);

        patch(item, null, Map.of("control", "Old title"), "2026-11-05");
        WorkItem read = workItemService.getWorkItemEntity(project.getId(), item.getId(), caller);
        assertThat(read.getFields().get("hypothesis").asText()).isEqualTo("Clicks go up");
        assertThat(read.getFields().get("control").asText()).isEqualTo("Old title");
        assertThat(read.getDueOn()).isEqualTo(LocalDate.of(2026, 11, 5));

        Map<String, Object> clear = new HashMap<>();
        clear.put("control", null);
        patch(item, null, clear, "");
        read = workItemService.getWorkItemEntity(project.getId(), item.getId(), caller);
        assertThat(read.getFields().has("control")).isFalse();
        assertThat(read.getDueOn()).isNull();

        assertThatThrownBy(() -> patch(item, null, null, "next week"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("dueOn");
    }

    @Test
    void movingToReviewedNeedsTheRequiredFieldsAndAcceptsThemOnceFilled() {
        WorkItem item = create("Shorter title", Map.of("hypothesis", "Clicks go up"), null);
        // PLANNED -> LIVE needs liveSince.
        assertThatThrownBy(() -> patch(item, "LIVE", null, null))
                .isInstanceOfSatisfying(MissingFieldsException.class,
                        e -> assertThat(e.getMissingFields()).containsExactly("liveSince"));
        // One patch can fill the field and take the transition.
        patch(item, "LIVE", Map.of("liveSince", "2026-10-01"), null);

        approve(item);
        assertThatThrownBy(() -> patch(item, "REVIEWED", null, null))
                .isInstanceOfSatisfying(MissingFieldsException.class,
                        e -> assertThat(e.getMissingFields()).containsExactly("outcome", "verdict", "lesson"));
        assertThatThrownBy(() -> patch(item, "REVIEWED", Map.of("outcome", "Up 12%", "lesson", "Short wins"), null))
                .isInstanceOfSatisfying(MissingFieldsException.class,
                        e -> assertThat(e.getMissingFields()).containsExactly("verdict"));

        // A refused patch rolls back whole, so the fields it carried were not kept: send them all together.
        WorkItem reviewed = patch(item, "REVIEWED",
                Map.of("outcome", "Up 12%", "lesson", "Short wins", "verdict", "worked"), null);
        assertThat(reviewed.getCurrentStatus()).isEqualTo("REVIEWED");
    }

    @Test
    void editingFieldsCannotClearWhatTheCurrentStatusRequires() {
        WorkItem item = create("Shorter title", Map.of("hypothesis", "Clicks go up"), null);
        patch(item, "LIVE", Map.of("liveSince", "2026-10-01"), null);
        approve(item);
        patch(item, "REVIEWED", Map.of("outcome", "Up 12%", "lesson", "Short wins", "verdict", "worked"), null);

        Map<String, Object> clear = new HashMap<>();
        clear.put("verdict", null);
        assertThatThrownBy(() -> patch(item, null, clear, null))
                .isInstanceOfSatisfying(MissingFieldsException.class,
                        e -> assertThat(e.getMissingFields()).containsExactly("verdict"));

        // A field the status does not require stays freely editable.
        WorkItem edited = patch(item, null, Map.of("control", "Old title"), null);
        assertThat(edited.getFields().get("control").asText()).isEqualTo("Old title");
        assertThat(edited.getFields().get("verdict").asText()).isEqualTo("worked");
    }

    @Test
    void abandoningNeedsNoFields() {
        WorkItem item = create("Dropped idea", null, null);
        assertThat(patch(item, "ABANDONED", null, null).getCurrentStatus()).isEqualTo("ABANDONED");
    }

    @Test
    void dueBeforeListsOnlyOpenItemsDueBeforeTheDateSoonestFirst() {
        create("Due later", null, LocalDate.of(2026, 12, 1));
        create("No date", null, null);
        WorkItem soon = create("Due soon", null, LocalDate.of(2026, 10, 20));
        create("Due sooner", null, LocalDate.of(2026, 10, 10));
        WorkItem abandoned = create("Abandoned and overdue", null, LocalDate.of(2026, 10, 1));
        patch(abandoned, "ABANDONED", null, null);

        List<WorkItem> overdue = workItemService.listWorkItemEntities(
                project.getId(), null, null, null, null, LocalDate.of(2026, 11, 1), caller);

        assertThat(overdue).extracting(WorkItem::getTitle).containsExactly("Due sooner", "Due soon");
        // Strictly before: an item due on the cutoff date is not yet overdue.
        assertThat(workItemService.listWorkItemEntities(
                project.getId(), null, null, null, null, LocalDate.of(2026, 10, 20), caller))
                .extracting(WorkItem::getTitle).containsExactly("Due sooner");
        assertThat(soon.getDueOn()).isEqualTo(LocalDate.of(2026, 10, 20));
    }
}
