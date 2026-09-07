package com.conductor.knowledge;

import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One lane's dispatch failure must be that lane's alone. Before this, an exception from the first
 * lane's dispatch unwound the whole project's tick and the lanes after it never got claimed — which is
 * what the integration test's "other lanes still dispatch" case saw whenever something collided under
 * the first dispatch.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestSchedulerTest {

    @Mock KnowledgeSourceRepository sourceRepository;
    @Mock ProjectSettingsService projectSettingsService;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock LibrarianDispatchService dispatchService;
    KnowledgeIngestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KnowledgeIngestScheduler(sourceRepository, projectSettingsService, workflowRepository,
                workflowRunRepository, dispatchService, true);
        scheduler.self = scheduler;
    }

    private static KnowledgeSource pending(String id) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setStatus(KnowledgeSourceStatus.PENDING);
        return source;
    }

    @Test
    void aLaneWhoseDispatchThrows_doesNotStopTheLanesAfterIt() {
        when(sourceRepository.findProjectIdsWithDuePending(any())).thenReturn(List.of("p1"));
        when(projectSettingsService.isKnowledgeEnabled("p1")).thenReturn(true);
        when(workflowRepository.findByProjectIdAndName(eq("p1"), anyString())).thenReturn(Optional.empty());
        when(sourceRepository.findLanesWithDuePending(eq("p1"), any())).thenReturn(java.util.Arrays.asList(null, "product"));
        when(sourceRepository.existsProcessingInLane(eq("p1"), any())).thenReturn(false);
        when(sourceRepository.findDuePendingForProjectAndDomain(eq("p1"), any(), any(OffsetDateTime.class), anyInt()))
                .thenAnswer(inv -> List.of(pending("src-" + inv.getArgument(1))));
        when(sourceRepository.findByStatus(KnowledgeSourceStatus.PROCESSING)).thenReturn(List.of());
        doThrow(new IllegalStateException("duplicate key value violates unique constraint"))
                .when(dispatchService).dispatch("p1", null, List.of("src-null"));

        scheduler.poll();

        // The lane after the failing one was still claimed and dispatched.
        verify(dispatchService).dispatch("p1", "product", List.of("src-product"));
    }
}
