package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemSnapshotService;
import com.conductor.service.view.WorkItemSnapshot;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As of A6, {@code KnowledgeEventTap} folded directly into {@link KnowledgeSignalSink} (it was already
 * written as the ingestion anti-corruption adapter, so there was no longer a reason for it to be a
 * separate injected collaborator). This file absorbs the old {@code KnowledgeEventTapTest} coverage
 * alongside the pre-existing subscriber-contract tests (order/failureMode/interestedIn).
 *
 * <p>As of the terminal-status-ingestion change, the status-changed section below covers: the terminal
 * gate (including the {@code toTerminal} hardening for a category-less Workflow), the "did this Work Item
 * actually produce anything" significance gate, the shape of the snapshot-based submission, and the
 * content-addressed dedup key. {@link WorkItemSnapshotService} is mocked throughout -- its own assembly
 * logic (including the 32 KB document truncation) is covered by {@code WorkItemSnapshotServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSignalSinkTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String WORK_ITEM_ID = "wi-1";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Mock private KnowledgeIngestionService ingestionService;
    @Mock private ProjectSettingsService projectSettingsService;
    @Mock private WorkItemSnapshotService snapshotService;

    private KnowledgeSignalSink sink;

    @BeforeEach
    void setUp() {
        sink = new KnowledgeSignalSink(ingestionService, projectSettingsService, snapshotService, new ObjectMapper());
    }

    // ---- helpers ----

    private Signal statusChangedSignal(Map<String, Object> payload) {
        return Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, PROJECT_ID, null, Instant.now(),
                payload, new SignalOrigin("test", null));
    }

    /** A transition on {@link #WORK_ITEM_ID} landing on a terminal status -- the shape the sink now
     *  requires before it will even attempt a snapshot. */
    private Map<String, Object> terminalTransitionPayload() {
        return terminalTransitionPayload("IN_PROGRESS", "DONE", "Done");
    }

    /** A transition landing on an arbitrary terminal status -- lets a test model the two *different* edges
     *  a {@code LifecycleTriggerDispatcher} cascade dispatches for one Work Item. */
    private Map<String, Object> terminalTransitionPayload(String fromStatus, String toStatus, String toStatusLabel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workItemId", WORK_ITEM_ID);
        payload.put("workItemTitle", "Ship the thing");
        payload.put("fromStatus", fromStatus);
        payload.put("toStatus", toStatus);
        payload.put("toStatusLabel", toStatusLabel);
        payload.put("toCategory", "terminal");
        payload.put("noun", "Issue");
        return payload;
    }

    private WorkItemSnapshot snapshotWith(List<WorkItemSnapshot.Doc> docs, List<WorkItemSnapshot.Note> comments,
                                          List<WorkItemSnapshot.Artifact> assets, List<WorkItemSnapshot.Verdict> reviews) {
        return new WorkItemSnapshot(WORK_ITEM_ID, PROJECT_ID, "ENG", 42, "Ship the thing", "the description",
                "TASK", "ENGINEERING", "DONE", "Alice", T0, T0, docs, comments, assets, reviews);
    }

    private WorkItemSnapshot snapshotWithOneComment() {
        return snapshotWith(List.of(),
                List.of(new WorkItemSnapshot.Note("c-1", "Alice", null, "Looks good", T0)),
                List.of(), List.of());
    }

    private void stubSnapshot(WorkItemSnapshot snapshot) {
        when(snapshotService.snapshot(WORK_ITEM_ID)).thenReturn(Optional.of(snapshot));
    }

    // ---- subscriber contract (unchanged) ----

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
    void nonStatusChangedSignal_isIgnoredWithoutCheckingSettings() {
        Signal signal = Signal.of(SignalTypes.CONDUCTOR_PROJECT_MEMBER_JOINED, PROJECT_ID, null, Instant.now(),
                Map.of("memberName", "Alice"), new SignalOrigin("test", null));

        sink.onSignal(signal);

        verify(projectSettingsService, never()).isKnowledgeEnabled(anyString());
        verify(ingestionService, never()).submit(any());
    }

    // ---- terminal-status gate ----

    @Test
    void nonTerminalTransition_doesNotSnapshotOrSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Map<String, Object> payload = terminalTransitionPayload();
        payload.put("toCategory", "in_progress");

        sink.onSignal(statusChangedSignal(payload));

        verify(snapshotService, never()).snapshot(any());
        verify(ingestionService, never()).submit(any());
    }

    /** Pins the Part 1 hardening: a category-less (but still terminal) Workflow -- e.g. one seeded by
     *  {@code WorkflowSeeder}, which bypasses {@code WorkflowDefinitionValidator} -- must not silently
     *  file nothing forever just because {@code toCategory} never got stamped. */
    @Test
    void missingToCategoryButToTerminalTrue_submits() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Map<String, Object> payload = terminalTransitionPayload();
        payload.remove("toCategory");
        payload.put("toTerminal", true);
        stubSnapshot(snapshotWithOneComment());

        sink.onSignal(statusChangedSignal(payload));

        verify(ingestionService).submit(any());
    }

    @Test
    void knowledgeDisabled_doesNotSnapshotOrSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        verify(snapshotService, never()).snapshot(any());
        verify(ingestionService, never()).submit(any());
    }

    /**
     * Previously (see the pre-existing {@code missingWorkItemIdFallsBackToUnknownRef}), a status-changed
     * signal with no {@code workItemId} still submitted, under a synthetic {@code conductor:unknown} ref.
     * Under the snapshot-based contract there is nothing meaningful to snapshot without an id, so the new
     * contract is simply: no id, no submit, no throw.
     */
    @Test
    void missingWorkItemId_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        Map<String, Object> payload = terminalTransitionPayload();
        payload.remove("workItemId");

        sink.onSignal(statusChangedSignal(payload));

        verify(snapshotService, never()).snapshot(any());
        verify(ingestionService, never()).submit(any());
    }

    // ---- significance gate ----

    @Test
    void terminalButNoArtifacts_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        WorkItemSnapshot empty = new WorkItemSnapshot(WORK_ITEM_ID, PROJECT_ID, "ENG", 42, "Ship the thing", "   ",
                "TASK", "ENGINEERING", "DONE", "Alice", T0, T0, List.of(), List.of(), List.of(), List.of());
        stubSnapshot(empty);

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        verify(ingestionService, never()).submit(any());
    }

    // ---- submission shape ----

    @Test
    void terminalWithOneComment_submitsAsWorkItemCompleted() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        stubSnapshot(snapshotWithOneComment());

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(PROJECT_ID);
        assertThat(submission.sourceType()).isEqualTo("conductor.work_item.completed");
        assertThat(submission.sourceRef()).isEqualTo("conductor:wi-1");
        assertThat(submission.title()).isEqualTo("Ship the thing");
        assertThat(submission.origin().kind()).isEqualTo("EVENT_TAP");
    }

    @Test
    void payloadContainsAllProducedContent() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        WorkItemSnapshot.Doc doc = new WorkItemSnapshot.Doc("spec.md", "text/markdown", "the spec body", false, T0, T0);
        WorkItemSnapshot.Note note = new WorkItemSnapshot.Note("c-1", "Alice", "spec.md", "looks good to me", T0);
        WorkItemSnapshot.Artifact asset = new WorkItemSnapshot.Artifact("github_pr", "Pull Request", "link",
                "https://github.com/x/y/pull/3", true, T0);
        WorkItemSnapshot.Verdict verdict = new WorkItemSnapshot.Verdict("Bob", "APPROVED", "ship it", T0);
        stubSnapshot(snapshotWith(List.of(doc), List.of(note), List.of(asset), List.of(verdict)));

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().payload()).contains(
                "the spec body", "looks good to me", "https://github.com/x/y/pull/3", "ship it", "APPROVED");
    }

    /** Pins that the payload tree is built as fixed-order {@code LinkedHashMap}s (the declared field
     *  order), not the alphabetically-sorted {@code TreeMap} the old scalar payload used -- {@code
     *  documents}/{@code comments}/{@code assets}/{@code reviews} would sort as
     *  assets/comments/documents/reviews if this regressed to alphabetical ordering. */
    @Test
    void payloadJsonHasFixedFieldOrder_notAlphabeticallySorted() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        stubSnapshot(snapshotWithOneComment());

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        String payload = captor.getValue().payload();
        int workItemIdx = payload.indexOf("\"workItem\"");
        int documentsIdx = payload.indexOf("\"documents\"");
        int commentsIdx = payload.indexOf("\"comments\"");
        int assetsIdx = payload.indexOf("\"assets\"");
        int reviewsIdx = payload.indexOf("\"reviews\"");
        assertThat(workItemIdx).isGreaterThanOrEqualTo(0).isLessThan(documentsIdx);
        assertThat(documentsIdx).isLessThan(commentsIdx);
        assertThat(commentsIdx).isLessThan(assetsIdx);
        assertThat(assetsIdx).isLessThan(reviewsIdx);
    }

    /** The 32 KB per-document cap itself is {@code WorkItemSnapshotServiceTest}'s job (it's where the
     *  truncation happens); this only pins that the sink faithfully carries a snapshot's already-truncated
     *  content and flag through into the submitted payload rather than losing or re-deriving them. */
    @Test
    void truncatedDocumentFlowsThroughToPayload() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        String truncatedContent = "x".repeat(40_000) + "\n\n[... truncated at 32 KB; content continues beyond this point ...]";
        WorkItemSnapshot.Doc doc = new WorkItemSnapshot.Doc("spec.md", "text/markdown", truncatedContent, true, T0, T0);
        stubSnapshot(snapshotWith(List.of(doc), List.of(), List.of(), List.of()));

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().payload()).contains("\"truncated\":true", "truncated at 32 KB");
    }

    @Test
    void originKindIsExactlyEventTap() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        stubSnapshot(snapshotWithOneComment());

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        assertThat(captor.getValue().origin().kind()).isEqualTo("EVENT_TAP");
        assertThat(captor.getValue().origin().id()).isEqualTo("wi-1");
    }

    // ---- dedup key ----

    @Test
    void dedupKeyFormatIsStable() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        stubSnapshot(snapshotWithOneComment());

        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));
        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService, times(2)).submit(captor.capture());
        List<KnowledgeSubmission> submissions = captor.getAllValues();
        assertThat(submissions.get(0).dedupKey())
                .matches("work-item-completed:wi-1:[0-9a-f]{16}")
                .isEqualTo(submissions.get(1).dedupKey());
    }

    /**
     * Content-addressed, not {@code {fromStatus}->{toStatus}}: two dispatches of the identical snapshot
     * (e.g. a {@code LifecycleTriggerDispatcher} cascade re-running the sink at a different edge for the
     * same already-saved Work Item) collapse to the same key, while a snapshot that actually changed
     * (one more comment -- a genuine reopen-with-new-material case) gets a different one. Also pins that
     * the payload itself is byte-identical across the two identical dispatches -- the wall-clock-free
     * contract {@link WorkItemSnapshotService#snapshot} depends on.
     */
    @Test
    void dedupKeyIsContentAddressed() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        WorkItemSnapshot snapshot = snapshotWithOneComment();
        stubSnapshot(snapshot);

        // The two dispatches a real cascade produces: the nested one for the edge the cascade advanced to,
        // and the outer one for the edge that triggered it. Different fromStatus/toStatus/toStatusLabel,
        // same already-saved Work Item -- so nothing edge-derived may reach the payload, or these two stop
        // hashing alike and the cascade double-files. (An earlier revision put toStatusLabel in the payload
        // and this is the case that catches it; asserting with two *identical* payloads would not.)
        sink.onSignal(statusChangedSignal(terminalTransitionPayload("CODE_REVIEW", "DONE", "Done")));
        sink.onSignal(statusChangedSignal(terminalTransitionPayload("DONE", "CLOSED", "Closed")));

        WorkItemSnapshot.Note extraComment = new WorkItemSnapshot.Note("c-2", "Bob", null, "one more note", T0.plusMinutes(1));
        WorkItemSnapshot withExtraComment = snapshotWith(snapshot.documents(),
                List.of(snapshot.comments().get(0), extraComment), snapshot.assets(), snapshot.reviews());
        stubSnapshot(withExtraComment);
        sink.onSignal(statusChangedSignal(terminalTransitionPayload()));

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService, times(3)).submit(captor.capture());
        List<KnowledgeSubmission> submissions = captor.getAllValues();
        assertThat(submissions.get(1).dedupKey()).isEqualTo(submissions.get(0).dedupKey());
        assertThat(submissions.get(1).payload()).isEqualTo(submissions.get(0).payload());
        assertThat(submissions.get(2).dedupKey()).isNotEqualTo(submissions.get(0).dedupKey());
    }

    // ---- failure modes ----

    @Test
    void aFailingIngestIsSwallowedInsideOnSignal() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        stubSnapshot(snapshotWithOneComment());
        doThrow(new RuntimeException("boom")).when(ingestionService).submit(any());

        assertThatNoException().isThrownBy(() -> sink.onSignal(statusChangedSignal(terminalTransitionPayload())));
    }

    @Test
    void snapshotThrows_swallowedNoSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        when(snapshotService.snapshot(WORK_ITEM_ID)).thenThrow(new RuntimeException("db down"));

        assertThatNoException().isThrownBy(() -> sink.onSignal(statusChangedSignal(terminalTransitionPayload())));
        verify(ingestionService, never()).submit(any());
    }

    @Test
    void snapshotEmpty_noSubmitNoThrow() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        when(snapshotService.snapshot(WORK_ITEM_ID)).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> sink.onSignal(statusChangedSignal(terminalTransitionPayload())));
        verify(ingestionService, never()).submit(any());
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
