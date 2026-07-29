package com.conductor.knowledge;

import com.conductor.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetentionServiceTest {

    private static final int COMPACT_AFTER_DAYS = 30;
    private static final int DELETE_DEAD_AFTER_DAYS = 90;
    private static final int DELETE_SKIPPED_AFTER_DAYS = 90;

    @Mock
    private KnowledgeSourceRepository repository;
    @Mock
    private StorageService storageService;

    private KnowledgeRetentionService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRetentionService(repository, storageService, COMPACT_AFTER_DAYS,
                DELETE_DEAD_AFTER_DAYS, DELETE_SKIPPED_AFTER_DAYS);
        // No real Spring proxy in a unit test — point the self-reference at the instance itself so the
        // REQUIRES_NEW helpers are still reachable (mirrors ActionInvocationServiceTest's precedent).
        service.self = service;
        // Every sweep() runs all three passes -- default every test to an empty SKIPPED batch so tests
        // focused on PROCESSED/DEAD don't also need to reason about the SKIPPED pass. lenient(): the
        // SKIPPED-focused tests below re-stub this same matcher with real data, which would otherwise
        // make Mockito's strict-stub checker flag this default as an unused/"unnecessary" stubbing.
        lenient().when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.SKIPPED), any(), any())).thenReturn(List.of());
    }

    private KnowledgeSource source(String id, KnowledgeSourceStatus status, String payload, String payloadUri) {
        KnowledgeSource s = new KnowledgeSource();
        s.setId(id);
        s.setProjectId("proj-1");
        s.setStatus(status);
        s.setPayload(payload);
        s.setPayloadUri(payloadUri);
        return s;
    }

    // ---- compact ----

    @Test
    void compactsInlinePayloadSource_nullsPayloadAndStampsPurgedAt() {
        KnowledgeSource src = source("src-1", KnowledgeSourceStatus.PROCESSED, "inline content", null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-1")).thenReturn(Optional.of(src));

        service.sweep();

        assertThat(src.getPayload()).isNull();
        assertThat(src.getPayloadUri()).isNull();
        assertThat(src.getPurgedAt()).isNotNull();
        verify(repository).save(src);
        verify(storageService, never()).delete(any());
    }

    @Test
    void compactsOffloadedPayloadSource_deletesGcsObjectAndNullsPayloadUri() {
        KnowledgeSource src = source("src-2", KnowledgeSourceStatus.PROCESSED, null, "knowledge-sources/proj-1/src-2");
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-2")).thenReturn(Optional.of(src));

        service.sweep();

        verify(storageService).delete("knowledge-sources/proj-1/src-2");
        assertThat(src.getPayloadUri()).isNull();
        assertThat(src.getPurgedAt()).isNotNull();
        verify(repository).save(src);
    }

    @Test
    void gcsDeleteFailure_skipsRowEntirelyForRetryNextTick() {
        KnowledgeSource src = source("src-3", KnowledgeSourceStatus.PROCESSED, null, "knowledge-sources/proj-1/src-3");
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-3")).thenReturn(Optional.of(src));
        org.mockito.Mockito.doThrow(new RuntimeException("bucket unreachable"))
                .when(storageService).delete("knowledge-sources/proj-1/src-3");

        service.sweep();

        // Never null payload_uri (or stamp purgedAt) unless the object it points at is confirmed
        // deleted -- otherwise the object becomes an orphan nothing can ever clean up. The row is left
        // exactly as it was so the next hourly tick retries it.
        assertThat(src.getPurgedAt()).isNull();
        assertThat(src.getPayloadUri()).isEqualTo("knowledge-sources/proj-1/src-3");
        verify(repository, never()).save(any());
    }

    @Test
    void alreadyPurgedSource_isNotReCompacted() {
        KnowledgeSource src = source("src-4", KnowledgeSourceStatus.PROCESSED, null, null);
        src.setPurgedAt(OffsetDateTime.now().minusDays(1));
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-4")).thenReturn(Optional.of(src));

        service.sweep();

        verify(repository, never()).save(any());
    }

    @Test
    void compactQueryUsesConfiguredCompactAfterDays() {
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusDays(COMPACT_AFTER_DAYS).minusSeconds(5);
        service.sweep();
        OffsetDateTime after = OffsetDateTime.now().minusDays(COMPACT_AFTER_DAYS).plusSeconds(5);

        org.mockito.ArgumentCaptor<OffsetDateTime> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), cutoffCaptor.capture(), any());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    // ---- delete ----

    @Test
    void deletesDeadSourcePastThreshold_hardDeletesRow() {
        KnowledgeSource src = source("src-5", KnowledgeSourceStatus.DEAD, null, null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-5")).thenReturn(Optional.of(src));

        service.sweep();

        verify(repository).delete(src);
    }

    @Test
    void deletingDeadSourceWithOffloadedPayload_deletesGcsObjectFirst() {
        KnowledgeSource src = source("src-6", KnowledgeSourceStatus.DEAD, null, "knowledge-sources/proj-1/src-6");
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-6")).thenReturn(Optional.of(src));

        service.sweep();

        verify(storageService).delete("knowledge-sources/proj-1/src-6");
        verify(repository).delete(src);
    }

    @Test
    void gcsDeleteFailureOnDeadSource_skipsDeletionForRetryNextTick() {
        KnowledgeSource src = source("src-8", KnowledgeSourceStatus.DEAD, null, "knowledge-sources/proj-1/src-8");
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-8")).thenReturn(Optional.of(src));
        org.mockito.Mockito.doThrow(new RuntimeException("bucket unreachable"))
                .when(storageService).delete("knowledge-sources/proj-1/src-8");

        service.sweep();

        // Never delete the row unless its offloaded object is confirmed gone first.
        verify(repository, never()).delete(any());
    }

    @Test
    void sourceNoLongerDead_isNotDeleted() {
        // Raced with something moving it out of DEAD between the query and the per-row transaction.
        KnowledgeSource src = source("src-7", KnowledgeSourceStatus.PENDING, null, null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-7")).thenReturn(Optional.of(src));

        service.sweep();

        verify(repository, never()).delete(any());
    }

    @Test
    void deleteQueryUsesConfiguredDeleteDeadAfterDays() {
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusDays(DELETE_DEAD_AFTER_DAYS).minusSeconds(5);
        service.sweep();
        OffsetDateTime after = OffsetDateTime.now().minusDays(DELETE_DEAD_AFTER_DAYS).plusSeconds(5);

        org.mockito.ArgumentCaptor<OffsetDateTime> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), cutoffCaptor.capture(), any());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    // ---- delete: SKIPPED (mirrors the DEAD tests above -- same terminal-unfiled treatment) ----

    @Test
    void deletesSkippedSourcePastThreshold_hardDeletesRow() {
        KnowledgeSource src = source("src-skip-1", KnowledgeSourceStatus.SKIPPED, null, null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.SKIPPED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-skip-1")).thenReturn(Optional.of(src));

        service.sweep();

        verify(repository).delete(src);
    }

    @Test
    void gcsDeleteFailureOnSkippedSource_skipsDeletionForRetryNextTick() {
        KnowledgeSource src = source("src-skip-2", KnowledgeSourceStatus.SKIPPED, null, "knowledge-sources/proj-1/src-skip-2");
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.SKIPPED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-skip-2")).thenReturn(Optional.of(src));
        org.mockito.Mockito.doThrow(new RuntimeException("bucket unreachable"))
                .when(storageService).delete("knowledge-sources/proj-1/src-skip-2");

        service.sweep();

        // Never delete the row unless its offloaded object is confirmed gone first -- same rule as DEAD.
        verify(repository, never()).delete(any());
    }

    /** Mirrors {@code KnowledgeRetentionServiceIntegrationTest#referencedDeadSourceIsTombstonedNotDeleted}
     *  for SKIPPED -- a skip produced no page, so this is a defensive hedge (not expected to trigger in
     *  practice) rather than the DEAD path's known race, but the guard must behave identically either way. */
    @Test
    void referencedSkippedSourceIsTombstonedNotDeleted() {
        KnowledgeSource src = source("src-skip-3", KnowledgeSourceStatus.SKIPPED, null, null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.SKIPPED), any(), any())).thenReturn(List.of(src));
        when(repository.findById("src-skip-3")).thenReturn(Optional.of(src));
        when(repository.isReferencedByRevision("src-skip-3")).thenReturn(true);

        service.sweep();

        assertThat(src.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(src.getPurgedAt()).isNotNull();
        verify(repository, never()).delete(any());
        verify(repository).save(src);
    }

    @Test
    void deleteSkippedQueryUsesConfiguredDeleteSkippedAfterDays() {
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of());
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.DEAD), any(), any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusDays(DELETE_SKIPPED_AFTER_DAYS).minusSeconds(5);
        service.sweep();
        OffsetDateTime after = OffsetDateTime.now().minusDays(DELETE_SKIPPED_AFTER_DAYS).plusSeconds(5);

        org.mockito.ArgumentCaptor<OffsetDateTime> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.SKIPPED), cutoffCaptor.capture(), any());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    @Test
    void compactFailureForOneRow_doesNotBlockOtherRowsInBatch() {
        KnowledgeSource bad = source("bad", KnowledgeSourceStatus.PROCESSED, "x", null);
        KnowledgeSource good = source("good", KnowledgeSourceStatus.PROCESSED, "y", null);
        when(repository.findByStatusAndPurgedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                eq(KnowledgeSourceStatus.PROCESSED), any(), any())).thenReturn(List.of(bad, good));
        when(repository.findById("bad")).thenThrow(new RuntimeException("db hiccup"));
        when(repository.findById("good")).thenReturn(Optional.of(good));

        service.sweep();

        assertThat(good.getPurgedAt()).isNotNull();
        verify(repository, times(1)).save(good);
    }
}
