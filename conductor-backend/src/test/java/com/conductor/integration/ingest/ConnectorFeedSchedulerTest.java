package com.conductor.integration.ingest;

import com.conductor.knowledge.MetricsNarratorDispatchService;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit coverage for {@link ConnectorFeedScheduler}'s bounded NARRATING sweep -- the
 * DB-backed end-to-end paths (pull/dispatch/sweep outcomes) live in {@link
 * ConnectorFeedSchedulerIntegrationTest}. No Spring context: {@link ConnectorFeedScheduler#self} is
 * wired to the instance itself so the {@code self.*InNewTx} calls inside {@link
 * ConnectorFeedScheduler#poll()} resolve without a proxy -- the {@code @Transactional} annotations on
 * those methods are simply inert here, which is fine since this test isn't exercising transactional
 * behavior.
 */
class ConnectorFeedSchedulerTest {

    private final ConnectorFeedRepository feedRepository = mock(ConnectorFeedRepository.class);
    private final ConnectorFeedDigestRepository digestRepository = mock(ConnectorFeedDigestRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final FeedPullService feedPullService = mock(FeedPullService.class);
    private final MetricsNarratorDispatchService narratorDispatchService = mock(MetricsNarratorDispatchService.class);
    private final DigestSubmissionService digestSubmissionService = mock(DigestSubmissionService.class);

    private ConnectorFeedScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ConnectorFeedScheduler(feedRepository, digestRepository, workflowRunRepository,
                feedPullService, narratorDispatchService, digestSubmissionService, true);
        scheduler.self = scheduler;

        when(feedRepository.claimDue(any(), anyInt())).thenReturn(List.of());
        when(digestRepository.claimDuePending(any(), anyInt())).thenReturn(List.of());
        when(digestRepository.findByStatusOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
    }

    @Test
    void sweep_usesBoundedPageRequest_atTheDefaultBatchSize() {
        scheduler.poll();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(digestRepository).findByStatusOrderByCreatedAtAsc(eq(DigestStatus.NARRATING), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    void sweep_shrinkingSweepBatchSize_shrinksTheRequestedPage() {
        scheduler.sweepBatchSize = 5;

        scheduler.poll();

        verify(digestRepository).findByStatusOrderByCreatedAtAsc(DigestStatus.NARRATING, PageRequest.of(0, 5));
    }

    @Test
    void sweep_neverCallsTheUnboundedFindByStatus() {
        scheduler.poll();

        verify(digestRepository, org.mockito.Mockito.never()).findByStatus(any());
    }
}
