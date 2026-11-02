package com.conductor.repository;

import com.conductor.entity.Milestone;
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
 * The tree queries behind Work Item hierarchy: the recursive subtree CTE, the parent / top-level / milestone /
 * priority / assignee filters on {@code findByProjectFiltered}, and the grouped child counts. Real Postgres,
 * because the CTE and the partial indexes are the point.
 */
@Transactional
class WorkItemHierarchyRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MilestoneRepository milestoneRepository;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    private User user;
    private Project project;
    private int nextSequence;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Hierarchy Project");
        project.setKey("HIE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);
        nextSequence = 1;
    }

    private WorkItem item(String parentId) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("TASK");
        item.setTitle("Item " + nextSequence);
        item.setCurrentStatus("TODO");
        item.setWorkflow("TASK");
        item.setWorkflowVersion(1);
        item.setCreatedByLabel("test-actor");
        item.setSequenceNumber(nextSequence++);
        item.setParentWorkItemId(parentId);
        return workItemRepository.saveAndFlush(item);
    }

    private List<WorkItem> list(String parentId, boolean topLevelOnly, String milestoneId, Integer priority,
                                String assigneeId) {
        return workItemRepository.findByProjectFiltered(project.getId(), null, null, null, null,
                parentId, topLevelOnly, milestoneId, priority, assigneeId);
    }

    @Test
    void subtreeIdsListTheRootFirstThenEveryDescendantParentsBeforeChildren() {
        WorkItem root = item(null);
        WorkItem a = item(root.getId());
        WorkItem b = item(root.getId());
        WorkItem a1 = item(a.getId());
        WorkItem a1x = item(a1.getId());
        item(null); // unrelated top-level item

        List<String> ids = workItemRepository.findSubtreeIds(root.getId());

        assertThat(ids).containsExactlyInAnyOrder(root.getId(), a.getId(), b.getId(), a1.getId(), a1x.getId());
        assertThat(ids.get(0)).isEqualTo(root.getId());
        assertThat(ids.indexOf(a.getId())).isLessThan(ids.indexOf(a1.getId()));
        assertThat(ids.indexOf(a1.getId())).isLessThan(ids.indexOf(a1x.getId()));
    }

    @Test
    void subtreeOfALeafIsJustItself() {
        WorkItem leaf = item(null);

        assertThat(workItemRepository.findSubtreeIds(leaf.getId())).containsExactly(leaf.getId());
    }

    @Test
    void subtreeStopsAtTenLevelsSoACorruptCycleCannotLoopForever() {
        WorkItem a = item(null);
        WorkItem b = item(a.getId());
        // Corrupt the data into a cycle a -> b -> a, which the service would never allow.
        a.setParentWorkItemId(b.getId());
        workItemRepository.saveAndFlush(a);

        List<String> ids = workItemRepository.findSubtreeIds(a.getId());

        assertThat(ids).hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void topLevelOnlyKeepsItemsWithNoParent() {
        WorkItem prd = item(null);
        item(prd.getId());

        List<WorkItem> top = list(null, true, null, null, null);

        assertThat(top).extracting(WorkItem::getId).containsExactly(prd.getId());
    }

    @Test
    void notTopLevelOnlyWithNoParentFilterIncludesChildren() {
        WorkItem prd = item(null);
        WorkItem child = item(prd.getId());

        assertThat(list(null, false, null, null, null)).extracting(WorkItem::getId)
                .containsExactlyInAnyOrder(prd.getId(), child.getId());
    }

    @Test
    void parentIdKeepsOnlyThatItemsDirectChildren() {
        WorkItem prd = item(null);
        WorkItem child = item(prd.getId());
        item(child.getId()); // a grandchild is not a direct child
        item(null);

        assertThat(list(prd.getId(), false, null, null, null)).extracting(WorkItem::getId)
                .containsExactly(child.getId());
    }

    @Test
    void milestonePriorityAndAssigneeFiltersNarrowTheList() {
        Milestone milestone = new Milestone();
        milestone.setProject(project);
        milestone.setName("Beta");
        milestoneRepository.saveAndFlush(milestone);

        WorkItem hit = item(null);
        hit.setMilestone(milestone);
        hit.setPriority(1);
        hit.setAssignee(user);
        workItemRepository.saveAndFlush(hit);
        WorkItem otherPriority = item(null);
        otherPriority.setMilestone(milestone);
        otherPriority.setPriority(2);
        workItemRepository.saveAndFlush(otherPriority);
        item(null);

        assertThat(list(null, true, milestone.getId(), null, null)).extracting(WorkItem::getId)
                .containsExactlyInAnyOrder(hit.getId(), otherPriority.getId());
        assertThat(list(null, true, null, 1, null)).extracting(WorkItem::getId).containsExactly(hit.getId());
        assertThat(list(null, true, null, null, user.getId())).extracting(WorkItem::getId)
                .containsExactly(hit.getId());
        assertThat(list(null, true, milestone.getId(), 2, user.getId())).isEmpty();
    }

    @Test
    void priorityZeroIsAFilterNotAnAbsentOne() {
        WorkItem urgent = item(null);
        urgent.setPriority(0);
        workItemRepository.saveAndFlush(urgent);
        item(null);

        assertThat(list(null, true, null, 0, null)).extracting(WorkItem::getId).containsExactly(urgent.getId());
    }

    @Test
    void childCountsAreGroupedPerParentAndOmitChildlessOnes() {
        WorkItem prd = item(null);
        item(prd.getId());
        item(prd.getId());
        WorkItem epic = item(prd.getId());
        item(epic.getId());
        WorkItem childless = item(null);

        List<Object[]> rows = workItemRepository.countChildrenGrouped(
                List.of(prd.getId(), epic.getId(), childless.getId()));

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(prd.getId());
            assertThat(((Number) r[1]).intValue()).isEqualTo(3);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(epic.getId());
            assertThat(((Number) r[1]).intValue()).isEqualTo(1);
        });
    }

    @Test
    void childrenComeBackInCreationOrder() {
        WorkItem prd = item(null);
        WorkItem first = item(prd.getId());
        WorkItem second = item(prd.getId());

        assertThat(workItemRepository.findChildren(prd.getId())).extracting(WorkItem::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void deletingAMilestoneDetachesItsItemsRatherThanDeletingThem() {
        Milestone milestone = new Milestone();
        milestone.setProject(project);
        milestone.setName("Doomed");
        milestoneRepository.saveAndFlush(milestone);
        WorkItem inMilestone = item(null);
        inMilestone.setMilestone(milestone);
        workItemRepository.saveAndFlush(inMilestone);

        // Drop the session's copies first: a managed item still pointing at a removed milestone cannot flush.
        entityManager.flush();
        entityManager.clear();
        milestoneRepository.deleteById(milestone.getId());
        milestoneRepository.flush();
        // The foreign key nulls the column in the database; read it back from there.
        entityManager.clear();
        WorkItem reloaded = workItemRepository.findById(inMilestone.getId()).orElseThrow();

        assertThat(reloaded.getMilestone()).isNull();
    }

    @Test
    void completedAtAndStartedAtRoundTrip() {
        WorkItem done = item(null);
        done.setStartedAt(java.time.OffsetDateTime.now().minusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        done.setCompletedAt(java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        done.setAppetite("2 weeks");
        done.setAppetiteDays(14);
        workItemRepository.saveAndFlush(done);

        WorkItem loaded = workItemRepository.findById(done.getId()).orElseThrow();

        assertThat(loaded.getAppetite()).isEqualTo("2 weeks");
        assertThat(loaded.getAppetiteDays()).isEqualTo(14);
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getCompletedAt()).isNotNull();
    }
}
