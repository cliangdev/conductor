package com.conductor.repository;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code findByProjectFilteredLimited} -- the query-level {@code LIMIT} that backs the
 * coordinator's {@code list_work_items} tool (see the repository method's javadoc for why the cap
 * belongs in the query rather than fetched-then-truncated in application code).
 */
@Transactional
class WorkItemRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private Project project;
    private int nextSequence;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Test Project");
        project.setKey("WIR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);
        nextSequence = 1;
    }

    private WorkItem newItem(String type, String status, String workflow) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType(type);
        item.setTitle("Item " + nextSequence);
        item.setCurrentStatus(status);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCreatedByLabel("test-actor");
        item.setSequenceNumber(nextSequence++);
        return workItemRepository.saveAndFlush(item);
    }

    @Test
    void limitCapsResultCountAtTheQueryLevel() {
        for (int i = 0; i < 5; i++) {
            newItem("BUG", "OPEN", "ENGINEERING");
        }

        List<WorkItem> results = workItemRepository.findByProjectFilteredLimited(
                project.getId(), null, null, null, 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void ordersMostRecentSequenceFirst() {
        newItem("BUG", "OPEN", "ENGINEERING");
        newItem("BUG", "OPEN", "ENGINEERING");
        WorkItem third = newItem("BUG", "OPEN", "ENGINEERING");

        List<WorkItem> results = workItemRepository.findByProjectFilteredLimited(
                project.getId(), null, null, null, 10);

        assertThat(results.get(0).getId()).isEqualTo(third.getId());
    }

    @Test
    void filtersByTypeStatusAndWorkflowIndependently() {
        newItem("BUG", "OPEN", "ENGINEERING");
        WorkItem prd = newItem("PRD", "DRAFT", "PRODUCT");

        List<WorkItem> byType = workItemRepository.findByProjectFilteredLimited(
                project.getId(), "PRD", null, null, 10);
        List<WorkItem> byStatus = workItemRepository.findByProjectFilteredLimited(
                project.getId(), null, "DRAFT", null, 10);
        List<WorkItem> byWorkflow = workItemRepository.findByProjectFilteredLimited(
                project.getId(), null, null, "PRODUCT", 10);

        assertThat(byType).extracting(WorkItem::getId).containsExactly(prd.getId());
        assertThat(byStatus).extracting(WorkItem::getId).containsExactly(prd.getId());
        assertThat(byWorkflow).extracting(WorkItem::getId).containsExactly(prd.getId());
    }

    @Test
    void scopedToProject() {
        newItem("BUG", "OPEN", "ENGINEERING");

        Project otherProject = new Project();
        otherProject.setName("Other Project");
        otherProject.setKey("OTH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        otherProject.setCreatedBy(project.getCreatedBy());
        projectRepository.save(otherProject);

        List<WorkItem> results = workItemRepository.findByProjectFilteredLimited(
                otherProject.getId(), null, null, null, 10);

        assertThat(results).isEmpty();
    }
}
