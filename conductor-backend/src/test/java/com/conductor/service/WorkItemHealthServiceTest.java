package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemHealthUpdate;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemHealthUpdateRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ThroughputForecastService.Fit;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.service.ThroughputForecastService.State;
import com.conductor.service.WorkItemHealthService.Health;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Health drafts, confirmation, expiry, staleness and atRisk against the real TASK statechart and a fixed clock. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkItemHealthServiceTest {

    private static final String PROJECT = "proj-1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T12:00:00Z");

    @Mock private WorkItemHealthUpdateRepository updates;
    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemWorkflowService workflowService;
    @Mock private UserRepository userRepository;

    private WorkItemHealthService service;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        Statechart task;
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/task.workflow.json")) {
            task = Statechart.parse(new ObjectMapper().readTree(in));
        }
        when(workflowService.resolveFor(eq(PROJECT), anyString(), any())).thenReturn(task);
        project = new Project();
        project.setId(PROJECT);
        service = new WorkItemHealthService(updates, workItemRepository, workflowService, userRepository,
                Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
        when(updates.save(any(WorkItemHealthUpdate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(List.of());
        when(updates.findLatestDraftsSince(anyCollection(), any())).thenReturn(List.of());
    }

    private WorkItem item(String id, String status) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(project);
        w.setWorkflow("TASK");
        w.setWorkflowVersion(1);
        w.setCurrentStatus(status);
        when(workItemRepository.findById(id)).thenReturn(Optional.of(w));
        return w;
    }

    private static WorkItemHealthUpdate update(String id, String itemId, String health, OffsetDateTime createdAt,
                                               OffsetDateTime confirmedAt) {
        WorkItemHealthUpdate u = new WorkItemHealthUpdate();
        u.setId(id);
        u.setProjectId(PROJECT);
        u.setWorkItemId(itemId);
        u.setHealth(health);
        u.setNote("note " + id);
        u.setDraftedByLabel("Agent");
        u.setCreatedAt(createdAt);
        u.setConfirmedAt(confirmedAt);
        if (confirmedAt != null) {
            u.setConfirmedBy("user-1");
        }
        return u;
    }

    private static User person() {
        User u = new User();
        u.setId("user-1");
        u.setEmail("p@example.com");
        return u;
    }

    // --- current ---

    @Test
    void anItemWithNothingToSayIsAbsent() {
        WorkItem prd = item("prd", "IN_PROGRESS");

        assertThat(service.current(PROJECT, List.of(prd))).isEmpty();
    }

    @Test
    void theConfirmedUpdateIsTheCurrentHealth() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(
                List.of(update("u1", "prd", "ON_TRACK", NOW.minusDays(1), NOW.minusDays(1))));

        Health h = service.current(PROJECT, List.of(prd)).get("prd");

        assertThat(h.health()).isEqualTo("ON_TRACK");
        assertThat(h.note()).isEqualTo("note u1");
        assertThat(h.updatedAt()).isEqualTo(NOW.minusDays(1));
        assertThat(h.pendingDraft()).isNull();
        assertThat(h.stale()).isFalse();
        assertThat(h.atRisk()).isFalse();
    }

    @Test
    void aDraftNewerThanTheConfirmedUpdateIsPendingAndDoesNotReplaceIt() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(
                List.of(update("u1", "prd", "ON_TRACK", NOW.minusDays(2), NOW.minusDays(2))));
        when(updates.findLatestDraftsSince(anyCollection(), any())).thenReturn(
                List.of(update("d1", "prd", "AT_RISK", NOW.minusHours(3), null)));

        Health h = service.current(PROJECT, List.of(prd)).get("prd");

        assertThat(h.health()).isEqualTo("ON_TRACK");
        assertThat(h.pendingDraft()).isNotNull();
        assertThat(h.pendingDraft().updateId()).isEqualTo("d1");
        assertThat(h.pendingDraft().health()).isEqualTo("AT_RISK");
        assertThat(h.pendingDraft().draftedByLabel()).isEqualTo("Agent");
        // A draft is not a confirmed risk.
        assertThat(h.atRisk()).isFalse();
    }

    @Test
    void aDraftOlderThanTheConfirmedUpdateIsNotPending() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(
                List.of(update("u1", "prd", "ON_TRACK", NOW.minusHours(10), NOW.minusHours(5))));
        when(updates.findLatestDraftsSince(anyCollection(), any())).thenReturn(
                List.of(update("d1", "prd", "AT_RISK", NOW.minusHours(8), null)));

        assertThat(service.current(PROJECT, List.of(prd)).get("prd").pendingDraft()).isNull();
    }

    @Test
    void aDraftAloneIsPendingWithNoCurrentHealth() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        when(updates.findLatestDraftsSince(anyCollection(), any())).thenReturn(
                List.of(update("d1", "prd", "OFF_TRACK", NOW.minusHours(1), null)));

        Health h = service.current(PROJECT, List.of(prd)).get("prd");

        assertThat(h.health()).isNull();
        assertThat(h.updatedAt()).isNull();
        assertThat(h.pendingDraft().health()).isEqualTo("OFF_TRACK");
        assertThat(h.atRisk()).isFalse();
    }

    @Test
    void draftsOlderThanFortyEightHoursAreNotReadAtAll() {
        WorkItem prd = item("prd", "IN_PROGRESS");

        service.current(PROJECT, List.of(prd));

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(updates).findLatestDraftsSince(anyCollection(), since.capture());
        assertThat(since.getValue()).isEqualTo(NOW.minusHours(48));
    }

    @Test
    void aConfirmedUpdateOverSevenDaysOldIsStaleWhileInProgress() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(
                List.of(update("u1", "prd", "ON_TRACK", NOW.minusDays(8), NOW.minusDays(8))));

        Health h = service.current(PROJECT, List.of(prd)).get("prd");

        assertThat(h.stale()).isTrue();
        assertThat(h.atRisk()).isTrue();
    }

    @Test
    void aConfirmedUpdateIsNotStaleBeforeSevenDaysOrOutsideInProgress() {
        WorkItem recent = item("a", "IN_PROGRESS");
        WorkItem open = item("b", "TODO");
        WorkItem finished = item("c", "DONE");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(List.of(
                update("u1", "a", "ON_TRACK", NOW.minusDays(6), NOW.minusDays(6)),
                update("u2", "b", "ON_TRACK", NOW.minusDays(30), NOW.minusDays(30)),
                update("u3", "c", "ON_TRACK", NOW.minusDays(30), NOW.minusDays(30))));

        Map<String, Health> h = service.current(PROJECT, List.of(recent, open, finished));

        assertThat(h.get("a").stale()).isFalse();
        assertThat(h.get("b").stale()).isFalse();
        assertThat(h.get("c").stale()).isFalse();
    }

    @Test
    void anExplicitAtRiskOrOffTrackIsAtRisk() {
        WorkItem a = item("a", "IN_PROGRESS");
        WorkItem b = item("b", "IN_PROGRESS");
        when(updates.findLatestConfirmedByWorkItem(anyCollection())).thenReturn(List.of(
                update("u1", "a", "AT_RISK", NOW.minusDays(1), NOW.minusDays(1)),
                update("u2", "b", "OFF_TRACK", NOW.minusDays(1), NOW.minusDays(1))));

        Map<String, Health> h = service.current(PROJECT, List.of(a, b));

        assertThat(h.get("a").atRisk()).isTrue();
        assertThat(h.get("b").atRisk()).isTrue();
    }

    @Test
    void aForecastPastTheDateMakesAnItemAtRiskEvenWithNoHealthRecorded() {
        WorkItem prd = item("prd", "IN_PROGRESS");
        Forecast tight = new Forecast(State.OK, ThroughputForecastService.Mode.THROUGHPUT, 5, 10, null, 2.0,
                LocalDate.of(2026, 11, 15), LocalDate.of(2026, 12, 6), LocalDate.of(2026, 11, 20), Fit.TIGHT, null);
        Forecast fits = new Forecast(State.OK, ThroughputForecastService.Mode.THROUGHPUT, 5, 10, null, 2.0,
                LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 30), Fit.FITS, null);

        Health risky = service.current(PROJECT, List.of(prd), Map.of("prd", tight)).get("prd");
        Map<String, Health> fine = service.current(PROJECT, List.of(prd), Map.of("prd", fits));

        assertThat(risky.atRisk()).isTrue();
        assertThat(risky.health()).isNull();
        assertThat(fine).isEmpty();
    }

    // --- writes ---

    @Test
    void draftRecordsAnUnconfirmedUpdateAttributedToTheCaller() {
        item("prd", "IN_PROGRESS");

        WorkItemHealthUpdate saved = service.draft(PROJECT, "prd", "at_risk", "  slipping  ", "Agent (run abcd1234)");

        assertThat(saved.isConfirmed()).isFalse();
        assertThat(saved.getHealth()).isEqualTo("AT_RISK");
        assertThat(saved.getNote()).isEqualTo("slipping");
        assertThat(saved.getDraftedByLabel()).isEqualTo("Agent (run abcd1234)");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void draftRejectsABadHealthOrAnEmptyNote() {
        item("prd", "IN_PROGRESS");

        assertThatThrownBy(() -> service.draft(PROJECT, "prd", "GREEN", "x", "Agent"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.draft(PROJECT, "prd", "ON_TRACK", "  ", "Agent"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void confirmStampsThePersonAndTheTimeAndAppliesOverrides() {
        item("prd", "IN_PROGRESS");
        when(updates.findById("d1")).thenReturn(
                Optional.of(update("d1", "prd", "AT_RISK", NOW.minusHours(2), null)));

        WorkItemHealthUpdate confirmed = service.confirm(PROJECT, "prd", "d1", person(), "OFF_TRACK", "worse");

        assertThat(confirmed.isConfirmed()).isTrue();
        assertThat(confirmed.getConfirmedBy()).isEqualTo("user-1");
        assertThat(confirmed.getConfirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.getHealth()).isEqualTo("OFF_TRACK");
        assertThat(confirmed.getNote()).isEqualTo("worse");
    }

    @Test
    void confirmWithoutOverridesKeepsTheDraftedContent() {
        item("prd", "IN_PROGRESS");
        when(updates.findById("d1")).thenReturn(
                Optional.of(update("d1", "prd", "AT_RISK", NOW.minusHours(2), null)));

        WorkItemHealthUpdate confirmed = service.confirm(PROJECT, "prd", "d1", person(), null, null);

        assertThat(confirmed.getHealth()).isEqualTo("AT_RISK");
        assertThat(confirmed.getNote()).isEqualTo("note d1");
    }

    @Test
    void aMachineCredentialCannotConfirmOrRecordAConfirmedUpdate() {
        item("prd", "IN_PROGRESS");

        assertThatThrownBy(() -> service.confirm(PROJECT, "prd", "d1", null, null, null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.record(PROJECT, "prd", "ON_TRACK", "fine", null))
                .isInstanceOf(ForbiddenException.class);
        verify(updates, never()).save(any());
    }

    @Test
    void aPersonRecordingDirectlyConfirmsInTheSameWrite() {
        item("prd", "IN_PROGRESS");

        WorkItemHealthUpdate saved = service.record(PROJECT, "prd", "ON_TRACK", "all good", person());

        assertThat(saved.isConfirmed()).isTrue();
        assertThat(saved.getConfirmedBy()).isEqualTo("user-1");
        assertThat(saved.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void anAlreadyConfirmedOrExpiredDraftCannotBeConfirmed() {
        item("prd", "IN_PROGRESS");
        when(updates.findById("done")).thenReturn(
                Optional.of(update("done", "prd", "ON_TRACK", NOW.minusHours(2), NOW.minusHours(1))));
        when(updates.findById("old")).thenReturn(
                Optional.of(update("old", "prd", "ON_TRACK", NOW.minusHours(49), null)));

        assertThatThrownBy(() -> service.confirm(PROJECT, "prd", "done", person(), null, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirm(PROJECT, "prd", "old", person(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anUpdateOfAnotherItemIsNotFound() {
        item("prd", "IN_PROGRESS");
        when(updates.findById("x")).thenReturn(
                Optional.of(update("x", "other", "ON_TRACK", NOW.minusHours(1), null)));

        assertThatThrownBy(() -> service.confirm(PROJECT, "prd", "x", person(), null, null))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
