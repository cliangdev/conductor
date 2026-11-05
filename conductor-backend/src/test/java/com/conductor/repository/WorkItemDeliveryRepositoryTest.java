package com.conductor.repository;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.entity.WorkItemCriterion;
import com.conductor.entity.WorkItemDependency;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queries behind dependencies, criteria counts and agent sessions, against real Postgres: the recursive
 * reachability check and its depth cap, the blocker join, the {@code FILTER} counts, the {@code DISTINCT ON}
 * latest-open-session read, and the table constraints.
 */
@Transactional
class WorkItemDeliveryRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkItemDependencyRepository dependencyRepository;
    @Autowired private WorkItemCriterionRepository criterionRepository;
    @Autowired private WorkItemAgentSessionRepository sessionRepository;

    private Project project;
    private int nextSequence;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Delivery Project");
        project.setKey("DEL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);
        nextSequence = 1;
    }

    private WorkItem item(String status) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("TASK");
        item.setTitle("Item " + nextSequence);
        item.setCurrentStatus(status);
        item.setWorkflow("TASK");
        item.setWorkflowVersion(1);
        item.setCreatedByLabel("test-actor");
        item.setSequenceNumber(nextSequence++);
        return workItemRepository.saveAndFlush(item);
    }

    private WorkItemDependency link(WorkItem from, String kind, WorkItem to) {
        WorkItemDependency d = new WorkItemDependency();
        d.setProjectId(project.getId());
        d.setWorkItemId(from.getId());
        d.setRelatedWorkItemId(to.getId());
        d.setKind(kind);
        return dependencyRepository.saveAndFlush(d);
    }

    private void criterion(WorkItem item, int position, boolean met) {
        WorkItemCriterion c = new WorkItemCriterion();
        c.setProjectId(project.getId());
        c.setWorkItemId(item.getId());
        c.setPosition(position);
        c.setText("Criterion " + position);
        c.setMet(met);
        criterionRepository.saveAndFlush(c);
    }

    private WorkItemAgentSession session(WorkItem item, String key, String activity, OffsetDateTime heartbeat,
                                         OffsetDateTime endedAt) {
        WorkItemAgentSession s = new WorkItemAgentSession();
        s.setProjectId(project.getId());
        s.setWorkItemId(item.getId());
        s.setSessionKey(key);
        s.setAgentLabel("claude");
        s.setActivity(activity);
        s.setLastHeartbeatAt(heartbeat);
        s.setEndedAt(endedAt);
        return sessionRepository.saveAndFlush(s);
    }

    // --- reachability ---

    @Test
    void reachabilityFollowsAThreeHopBlocksChainInOneDirectionOnly() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        WorkItem c = item("TODO");
        WorkItem d = item("TODO");
        link(a, "BLOCKS", b);
        link(b, "BLOCKS", c);
        link(c, "BLOCKS", d);

        assertThat(dependencyRepository.isReachableViaBlocks(a.getId(), d.getId())).isTrue();
        assertThat(dependencyRepository.isReachableViaBlocks(b.getId(), d.getId())).isTrue();
        assertThat(dependencyRepository.isReachableViaBlocks(d.getId(), a.getId())).isFalse();
        assertThat(dependencyRepository.isReachableViaBlocks(a.getId(), a.getId())).isFalse();
    }

    @Test
    void reachabilityIgnoresDiscoveredFromLinks() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        link(a, "DISCOVERED_FROM", b);

        assertThat(dependencyRepository.isReachableViaBlocks(a.getId(), b.getId())).isFalse();
    }

    @Test
    void reachabilityStopsAtDepthOneHundred() {
        List<WorkItem> chain = new ArrayList<>();
        for (int i = 0; i < 102; i++) {
            chain.add(item("TODO"));
        }
        for (int i = 0; i < 101; i++) {
            link(chain.get(i), "BLOCKS", chain.get(i + 1));
        }

        // 100 hops is reachable, the 101st is beyond the cap.
        assertThat(dependencyRepository.isReachableViaBlocks(chain.get(0).getId(), chain.get(100).getId())).isTrue();
        assertThat(dependencyRepository.isReachableViaBlocks(chain.get(0).getId(), chain.get(101).getId())).isFalse();
    }

    @Test
    void aCorruptCycleDoesNotLoopForever() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        link(a, "BLOCKS", b);
        link(b, "BLOCKS", a);

        assertThat(dependencyRepository.isReachableViaBlocks(a.getId(), b.getId())).isTrue();
        assertThat(dependencyRepository.isReachableViaBlocks(a.getId(), UUID.randomUUID().toString())).isFalse();
    }

    // --- blockers ---

    @Test
    void findBlockersForJoinsTheBlockerRowInOneQuery() {
        WorkItem blocker = item("IN_PROGRESS");
        WorkItem other = item("DONE");
        WorkItem blocked = item("TODO");
        WorkItem unrelated = item("TODO");
        link(blocker, "BLOCKS", blocked);
        link(other, "BLOCKS", blocked);
        link(blocker, "DISCOVERED_FROM", unrelated); // not a blocker

        List<BlockerRow> rows = dependencyRepository.findBlockersFor(List.of(blocked.getId(), unrelated.getId()));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.blockedId().equals(blocked.getId()));
        BlockerRow row = rows.stream().filter(r -> r.blockerId().equals(blocker.getId())).findFirst().orElseThrow();
        assertThat(row.blockerStatus()).isEqualTo("IN_PROGRESS");
        assertThat(row.blockerWorkflow()).isEqualTo("TASK");
        assertThat(row.blockerWorkflowVersion()).isEqualTo(1);
        assertThat(row.blockerTitle()).isEqualTo(blocker.getTitle());
        assertThat(row.blockerDisplayId()).isEqualTo(project.getKey() + "-" + blocker.getSequenceNumber());
    }

    @Test
    void findBlocksIntoReturnsOnlyBlocksEdgesIntoTheGivenItems() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        WorkItem c = item("TODO");
        link(a, "BLOCKS", b);
        link(c, "DISCOVERED_FROM", b);

        assertThat(dependencyRepository.findBlocksInto(List.of(b.getId())))
                .extracting(WorkItemDependency::getWorkItemId).containsExactly(a.getId());
    }

    @Test
    void theTableRejectsSelfLinksAndDuplicates() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        link(a, "BLOCKS", b);

        assertThatThrownBy(() -> link(a, "BLOCKS", a)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateLinksViolateTheUniqueConstraint() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        link(a, "BLOCKS", b);

        assertThatThrownBy(() -> link(a, "BLOCKS", b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- criteria ---

    @Test
    void countsByWorkItemGroupsTotalAndMetAndOmitsItemsWithNone() {
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        WorkItem none = item("TODO");
        criterion(a, 0, true);
        criterion(a, 1, false);
        criterion(a, 2, true);
        criterion(b, 0, false);

        List<Object[]> rows = criterionRepository.countsByWorkItem(List.of(a.getId(), b.getId(), none.getId()));

        assertThat(rows).hasSize(2);
        Object[] forA = rows.stream().filter(r -> r[0].equals(a.getId())).findFirst().orElseThrow();
        assertThat(((Number) forA[1]).intValue()).isEqualTo(3);
        assertThat(((Number) forA[2]).intValue()).isEqualTo(2);
        Object[] forB = rows.stream().filter(r -> r[0].equals(b.getId())).findFirst().orElseThrow();
        assertThat(((Number) forB[1]).intValue()).isEqualTo(1);
        assertThat(((Number) forB[2]).intValue()).isZero();
    }

    @Test
    void criteriaListInPositionOrder() {
        WorkItem a = item("TODO");
        criterion(a, 2, false);
        criterion(a, 0, false);
        criterion(a, 1, false);

        assertThat(criterionRepository.findByWorkItemIdOrderByPositionAscCreatedAtAsc(a.getId()))
                .extracting(WorkItemCriterion::getPosition).containsExactly(0, 1, 2);
    }

    // --- sessions ---

    @Test
    void latestOpenSessionIsTheMostRecentlyHeardFromOpenOneOnePerItem() {
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        WorkItem a = item("IN_PROGRESS");
        WorkItem b = item("IN_PROGRESS");
        WorkItem closedOnly = item("IN_PROGRESS");
        session(a, "old", "WORKING", now.minusHours(5), null);
        session(a, "new", "NEEDS_INPUT", now.minusHours(1), null);
        // Newer still, but closed: it is not open.
        session(a, "done", "DONE", now, now);
        session(b, "only", "WORKING", now.minusDays(1), null);
        session(closedOnly, "gone", "ERRORED", now, now);

        List<WorkItemAgentSession> latest = sessionRepository.findLatestOpenByWorkItem(
                List.of(a.getId(), b.getId(), closedOnly.getId()));

        assertThat(latest).hasSize(2);
        assertThat(latest).filteredOn(s -> s.getWorkItemId().equals(a.getId()))
                .extracting(WorkItemAgentSession::getSessionKey).containsExactly("new");
        assertThat(latest).filteredOn(s -> s.getWorkItemId().equals(b.getId()))
                .extracting(WorkItemAgentSession::getSessionKey).containsExactly("only");
    }

    @Test
    void aSessionKeyIsUniquePerItemButReusableAcrossItems() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkItem a = item("TODO");
        WorkItem b = item("TODO");
        session(a, "same", "WORKING", now, null);
        session(b, "same", "WORKING", now, null);

        assertThatThrownBy(() -> session(a, "same", "WORKING", now, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
