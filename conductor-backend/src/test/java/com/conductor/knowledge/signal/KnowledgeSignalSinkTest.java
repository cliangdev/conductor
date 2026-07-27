package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.service.ProjectSettingsService;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As of A6, {@code KnowledgeEventTap} folded directly into {@link KnowledgeSignalSink} (it was already
 * written as the ingestion anti-corruption adapter, so there was no longer a reason for it to be a
 * separate injected collaborator). This file absorbs the old {@code KnowledgeEventTapTest} coverage
 * alongside the pre-existing subscriber-contract tests (order/failureMode/interestedIn).
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSignalSinkTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeIngestionService ingestionService;
    @Mock private ProjectSettingsService projectSettingsService;

    private KnowledgeSignalSink sink;

    @BeforeEach
    void setUp() {
        sink = new KnowledgeSignalSink(ingestionService, projectSettingsService, new ObjectMapper());
    }

    private Signal statusChangedSignal(Map<String, String> payload) {
        return Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, PROJECT_ID, null, Instant.now(),
                Map.copyOf(payload), new SignalOrigin("test", null));
    }

    private Signal statusChangedSignal() {
        return statusChangedSignal(Map.of(
                "workItemId", "wi-1",
                "workItemTitle", "Ship the thing",
                "fromStatus", "IN_PROGRESS",
                "toStatus", "CODE_REVIEW"));
    }

    @Test
    void orderIsKnowledgeLast() {
        assertThat(sink.order()).isEqualTo(SignalDispatchOrder.KNOWLEDGE);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        assertThat(sink.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    /** Narrowed in A6, widened in A8: exact string equality against the two types this subscriber acts on. */
    @Test
    void interestedInStatusChangedAndMergedPullRequestOnly() {
        assertThat(sink.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)).isTrue();
        assertThat(sink.interestedIn(SignalTypes.GITHUB_PULL_REQUEST_MERGED)).isTrue();
        assertThat(sink.interestedIn(SignalTypes.GITHUB_PULL_REQUEST)).isFalse();
        assertThat(sink.interestedIn("anything")).isFalse();
    }

    @Test
    void knowledgeEnabled_submitsNormalizedEnvelope() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(PROJECT_ID);
        assertThat(submission.sourceType()).isEqualTo("conductor.work_item.status_changed");
        assertThat(submission.sourceRef()).isEqualTo("conductor:wi-1");
        assertThat(submission.title()).isEqualTo("Ship the thing");
        assertThat(submission.origin().kind()).isEqualTo("EVENT_TAP");
        assertThat(submission.payload()).contains("IN_PROGRESS", "CODE_REVIEW", "wi-1");
        assertThat(submission.dedupKey()).isEqualTo("work-item-status-changed:proj-1:wi-1:IN_PROGRESS->CODE_REVIEW");
    }

    @Test
    void knowledgeDisabled_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        sink.onSignal(statusChangedSignal());

        verify(ingestionService, never()).submit(any());
    }

    @Test
    void nonStatusChangedSignal_isIgnoredWithoutCheckingSettings() {
        Signal signal = Signal.of(SignalTypes.CONDUCTOR_PROJECT_MEMBER_JOINED, PROJECT_ID, null, Instant.now(),
                Map.of("memberName", "Alice"), new SignalOrigin("test", null));

        sink.onSignal(signal);

        verify(projectSettingsService, never()).isKnowledgeEnabled(anyString());
        verify(ingestionService, never()).submit(any());
    }

    @Test
    void aFailingIngestIsSwallowedInsideOnSignal() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(ingestionService).submit(any());

        assertThatNoException().isThrownBy(() -> sink.onSignal(statusChangedSignal()));
    }

    @Test
    void dedupKeyFormatIsStable() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().dedupKey())
                .isEqualTo("work-item-status-changed:proj-1:wi-1:IN_PROGRESS->CODE_REVIEW");
    }

    @Test
    void payloadJsonIsSortedAndByteStable() {
        // Pins the TreeMap-based serialization in KnowledgeSignalSink#toJson — key order in the JSON
        // payload is alphabetical regardless of the metadata Map's insertion/iteration order.
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().payload()).isEqualTo(
                "{\"fromStatus\":\"IN_PROGRESS\",\"toStatus\":\"CODE_REVIEW\","
                + "\"workItemId\":\"wi-1\",\"workItemTitle\":\"Ship the thing\"}");
    }

    @Test
    void originKindIsExactlyEventTap() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().origin().kind()).isEqualTo("EVENT_TAP");
        assertThat(captor.getValue().origin().id()).isEqualTo("wi-1");
    }

    @Test
    void sourceTypeIsExactly_conductor_work_item_status_changed() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("conductor.work_item.status_changed");
    }

    /**
     * The ingest logic only filters on signal type -- it has no notion of "is this shaped like a real
     * Work Item event," so a status-changed signal with no {@code workItemId} key still submits, with a
     * synthetic {@code conductor:unknown} ref, rather than being rejected. That is a legitimate,
     * still-relied-on fallback (it must not throw or silently drop the submission just because a key is
     * missing).
     *
     * <p>Previously this was exercised (in the pre-A6 {@code KnowledgeEventTapTest}) with a {@code
     * {test: true}}-shaped payload to document that {@code NotificationChannelService}/{@code
     * NotificationGroupService}'s admin "send a test notification" buttons abused this fallback by
     * routing straight through the tap. As of the A5 refactor those buttons call {@code
     * NotificationDeliveryService.deliver} directly and no longer touch the {@code SignalBus} at all, so
     * this sink is never reachable from them any more -- see {@code
     * NotificationGroupServiceTest}/{@code NotificationChannelServiceTest} for that guarantee. What
     * remains here is just the defensive contract for a malformed/incomplete signal payload.
     */
    @Test
    void missingWorkItemIdFallsBackToUnknownRef() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(statusChangedSignal(Map.of("fromStatus", "A", "toStatus", "B")));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().sourceRef()).isEqualTo("conductor:unknown");
    }

    // --- GITHUB_PULL_REQUEST_MERGED : formerly GitHubConnector#submitMergedPrKnowledge, moved here in
    //     A8 -- submits regardless of whether the PR body references a Conductor issue. ---

    private Signal mergedPrSignal(Map<String, Object> payload) {
        return Signal.of(SignalTypes.GITHUB_PULL_REQUEST_MERGED, PROJECT_ID, "3", Instant.now(),
                payload, new SignalOrigin("test", null));
    }

    private Map<String, Object> mergedPrPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repoFullName", "x/y");
        payload.put("number", 3);
        payload.put("title", "Add feature");
        payload.put("body", "just a PR, no conductor link");
        payload.put("labels", List.of("enhancement"));
        payload.put("mergedBy", "alice");
        payload.put("baseSha", "aaa");
        payload.put("headSha", "bbb");
        return payload;
    }

    @Test
    void mergedPr_knowledgeEnabled_submitsSource() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(mergedPrSignal(mergedPrPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(PROJECT_ID);
        assertThat(submission.sourceType()).isEqualTo("github.pr_merged");
        assertThat(submission.sourceRef()).isEqualTo("github:x/y#3");
        assertThat(submission.title()).isEqualTo("Add feature");
        assertThat(submission.payload()).contains("\"enhancement\"", "\"alice\"", "\"aaa\"", "\"bbb\"");
        assertThat(submission.dedupKey()).isEqualTo("github-pr-merged:github:x/y#3");
        assertThat(submission.origin().kind()).isEqualTo("GITHUB_CONNECTOR");
        assertThat(submission.origin().id()).isEqualTo("github:x/y#3");
    }

    @Test
    void mergedPr_knowledgeDisabled_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        sink.onSignal(mergedPrSignal(mergedPrPayload()));

        verify(ingestionService, never()).submit(any());
    }

    @Test
    void mergedPr_missingRepoFullNameOrNumber_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(mergedPrSignal(Map.of("body", "no repo info here")));

        verify(ingestionService, never()).submit(any());
    }

    @Test
    void mergedPr_changedFilesCountIncludedOnlyWhenPresent() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Map<String, Object> withCount = mergedPrPayload();
        withCount.put("changedFilesCount", 5);

        sink.onSignal(mergedPrSignal(withCount));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().payload()).contains("\"changedFilesCount\":5");
    }

    @Test
    void mergedPr_changedFilesCountAbsent_omitsKey() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        sink.onSignal(mergedPrSignal(mergedPrPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().payload()).doesNotContain("changedFilesCount");
    }

    @Test
    void mergedPrIngestFailure_isSwallowed() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(ingestionService).submit(any());

        assertThatNoException().isThrownBy(() -> sink.onSignal(mergedPrSignal(mergedPrPayload())));
    }

    /** Pins that the submission's occurredAt is stamped fresh at submission time -- not derived from
     *  {@link Signal#occurredAt()}, which may be considerably older by the time this sink runs. */
    @Test
    void mergedPr_occurredAtIsSubmissionTime_notSignalOccurredAt() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        Signal signal = Signal.of(SignalTypes.GITHUB_PULL_REQUEST_MERGED, PROJECT_ID, "3", longAgo,
                mergedPrPayload(), new SignalOrigin("test", null));

        sink.onSignal(signal);

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().occurredAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }
}
