package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed coverage for the generic per-item scheduling columns (V130): {@code scheduled_for} and
 * {@code schedule_timezone} on {@code work_items}. These are workflow-agnostic foundation columns — no
 * domain vocabulary in the schema, the entity, or the API.
 *
 * <p>Needs a real Postgres because two of the three criteria are about the migration itself (the columns
 * and the partial index a due-item poll relies on) and about values actually surviving a round trip
 * through the database rather than a mocked repository. Deliberately NOT {@code @Transactional}, so each
 * service call commits and the read-back runs in a fresh transaction with a fresh persistence context.
 * Isolation comes from per-test random project/user ids, per the shared-database contract in
 * {@code docs/testing-guidelines.md}.
 */
class WorkItemSchedulingTest extends AbstractNoneWebIntegrationTest {

    @Autowired private WorkItemService workItemService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User caller;
    private Project project;
    private WorkItem workItem;

    @BeforeEach
    void setUp() {
        caller = new User();
        caller.setFirebaseUid("test-uid-" + UUID.randomUUID());
        caller.setEmail(UUID.randomUUID() + "@example.com");
        caller.setName("Scheduling Caller");
        caller = userRepository.save(caller);

        project = new Project();
        project.setName("Scheduling Test Project");
        project.setKey("SC" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(caller);
        project = projectRepository.save(project);

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(caller);
        member.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(member);

        workItem = new WorkItem();
        workItem.setProject(project);
        workItem.setType("TASK");
        workItem.setTitle("Schedulable item");
        workItem.setCreatedBy(caller);
        workItem.setWorkflow("ENGINEERING");
        workItem.setWorkflowVersion(1);
        workItem.setCurrentStatus("DRAFT");
        workItem.setSequenceNumber(1);
        workItem = workItemRepository.save(workItem);
    }

    // [auto] V130 applies cleanly and adds both columns plus the partial index
    @Test
    void migrationAddsBothSchedulingColumns() {
        List<String> types = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'work_items' AND column_name = ?",
                String.class, "scheduled_for");
        assertThat(types).containsExactly("timestamp with time zone");

        List<String> tzTypes = jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'work_items' AND column_name = ?",
                String.class, "schedule_timezone");
        assertThat(tzTypes).containsExactly("character varying");

        List<Integer> tzLength = jdbcTemplate.queryForList(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'work_items' AND column_name = ?",
                Integer.class, "schedule_timezone");
        assertThat(tzLength).containsExactly(64);
    }

    // [auto] V130 applies cleanly and adds both columns plus the partial index
    @Test
    void migrationAddsPartialIndexOnScheduledFor() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'work_items' "
                        + "AND indexdef ILIKE '%scheduled_for%'",
                String.class);

        assertThat(definitions)
                .as("a partial index on scheduled_for backs the due-item poll")
                .anySatisfy(def -> {
                    assertThat(def).containsIgnoringCase("(scheduled_for)");
                    assertThat(def).containsIgnoringCase("WHERE (scheduled_for IS NOT NULL)");
                });
    }

    // [auto] scheduledFor / scheduleTimezone round-trip through the v2 Work Item API
    @Test
    void patchPersistsScheduleAndRoundTripsOnRead() {
        OffsetDateTime scheduledFor =
                OffsetDateTime.of(2026, 11, 3, 14, 30, 0, 0, ZoneOffset.UTC);

        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, null, null,
                scheduledFor, "America/New_York", caller);

        WorkItem reread = workItemService.getWorkItemEntity(project.getId(), workItem.getId(), caller);
        assertThat(reread.getScheduledFor())
                .isNotNull()
                .isEqualTo(scheduledFor.truncatedTo(ChronoUnit.MICROS));
        assertThat(reread.getScheduleTimezone()).isEqualTo("America/New_York");
    }

    // [auto] scheduledFor / scheduleTimezone round-trip through the v2 Work Item API
    @Test
    void patchLeavesScheduleUnchangedWhenFieldsAbsent() {
        OffsetDateTime scheduledFor =
                OffsetDateTime.of(2026, 12, 1, 9, 0, 0, 0, ZoneOffset.UTC);
        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, null, null,
                scheduledFor, "Europe/London", caller);

        workItemService.patchWorkItem(project.getId(), workItem.getId(), "Renamed", null, null, null,
                null, null, caller);

        WorkItem reread = workItemService.getWorkItemEntity(project.getId(), workItem.getId(), caller);
        assertThat(reread.getTitle()).isEqualTo("Renamed");
        assertThat(reread.getScheduledFor()).isEqualTo(scheduledFor.truncatedTo(ChronoUnit.MICROS));
        assertThat(reread.getScheduleTimezone()).isEqualTo("Europe/London");
    }

    // [auto] An invalid IANA timezone is rejected with a 4xx
    @Test
    void unknownTimezoneIsRejected() {
        assertThatThrownBy(() -> workItemService.patchWorkItem(
                project.getId(), workItem.getId(), null, null, null, null,
                OffsetDateTime.now(), "Mars/Olympus_Mons", caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Mars/Olympus_Mons");
    }

    // [auto] An invalid IANA timezone is rejected with a 4xx
    @Test
    void unknownTimezoneRejectionPersistsNothing() {
        assertThatThrownBy(() -> workItemService.patchWorkItem(
                project.getId(), workItem.getId(), "Should not stick", null, null, null,
                OffsetDateTime.now(), "Not/AZone", caller))
                .isInstanceOf(BusinessException.class);

        WorkItem reread = workItemService.getWorkItemEntity(project.getId(), workItem.getId(), caller);
        assertThat(reread.getTitle()).isEqualTo("Schedulable item");
        assertThat(reread.getScheduledFor()).isNull();
        assertThat(reread.getScheduleTimezone()).isNull();
    }

    @Test
    void unscheduledWorkItemReadsBackNullsForBothFields() {
        WorkItem reread = workItemService.getWorkItemEntity(project.getId(), workItem.getId(), caller);
        assertThat(reread.getScheduledFor()).isNull();
        assertThat(reread.getScheduleTimezone()).isNull();
    }

    @Test
    void blankTimezoneClearsTheStoredZone() {
        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, null, null,
                OffsetDateTime.now(), "Asia/Tokyo", caller);

        workItemService.patchWorkItem(project.getId(), workItem.getId(), null, null, null, null,
                null, "", caller);

        WorkItem reread = workItemService.getWorkItemEntity(project.getId(), workItem.getId(), caller);
        assertThat(reread.getScheduleTimezone()).isNull();
    }
}
