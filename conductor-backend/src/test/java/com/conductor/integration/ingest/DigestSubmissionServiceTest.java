package com.conductor.integration.ingest;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigestSubmissionServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String JOB_RUN_ID = "jobrun-1";

    private final WorkflowJobRunRepository jobRunRepository = mock(WorkflowJobRunRepository.class);
    private final WorkflowStepRunRepository stepRunRepository = mock(WorkflowStepRunRepository.class);
    private final ConnectorFeedRepository feedRepository = mock(ConnectorFeedRepository.class);
    private final ConnectorFeedDigestRepository digestRepository = mock(ConnectorFeedDigestRepository.class);
    private final ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
    private final KnowledgeIngestionService knowledgeIngestionService = mock(KnowledgeIngestionService.class);

    private DigestSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new DigestSubmissionService(jobRunRepository, stepRunRepository, feedRepository, digestRepository,
                connectorRegistry, knowledgeIngestionService, new ObjectMapper());
        service.self = service;
    }

    private static final class FakeConnector implements Connector {
        @Override public String getId() { return "gsc"; }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("gsc", "Google Search Console", ConnectorCategory.ANALYTICS, "desc", "GSC");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }

        @Override
        public IntegrationToolSpec getToolSpec() {
            IngestSpec spec = new IngestSpec("search_analytics_weekly", "label", "desc", IngestMode.SNAPSHOT,
                    "op", "metrics.digest.{connector}.{ingest}", null, null, "KNOWLEDGE", "marketing", null);
            return new IntegrationToolSpec("gsc", List.of(), List.of(), List.of(spec));
        }
    }

    private ConnectorFeed feed() {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId("proj-1");
        feed.setConnectionId("conn-1");
        feed.setConnectorId("gsc");
        feed.setIngestId("search_analytics_weekly");
        return feed;
    }

    private ConnectorFeedDigest digest() {
        ConnectorFeedDigest digest = new ConnectorFeedDigest();
        digest.setId("digest-1");
        digest.setProjectId("proj-1");
        digest.setFeedId("feed-1");
        digest.setPeriodKey("2026-W30");
        digest.setWindowEnd(OffsetDateTime.parse("2026-07-26T00:00:00Z"));
        digest.setChangeReport(Map.of("metrics", List.of(Map.of("key", "clicks", "material", true)), "pagePath", "x.md"));
        digest.setDedupKey("knowledge-digest:feed-1:2026-W30");
        return digest;
    }

    private void stubStepOutput(String outputJson) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(JOB_RUN_ID);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(jobRun));
        WorkflowStepRun stepRun = new WorkflowStepRun();
        stepRun.setOutputJson(outputJson);
        when(stepRunRepository.findByJobRunIdAndStepId(JOB_RUN_ID, "narrate")).thenReturn(Optional.of(stepRun));
    }

    @Test
    void missingJobRun_returnsFalse() {
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of());

        boolean result = service.trySubmit(digest(), RUN_ID);

        assertThat(result).isFalse();
        verify(knowledgeIngestionService, never()).submit(any());
    }

    @Test
    void missingStepOutput_returnsFalse() {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(JOB_RUN_ID);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(jobRun));
        when(stepRunRepository.findByJobRunIdAndStepId(JOB_RUN_ID, "narrate")).thenReturn(Optional.empty());

        boolean result = service.trySubmit(digest(), RUN_ID);

        assertThat(result).isFalse();
        verify(knowledgeIngestionService, never()).submit(any());
    }

    @Test
    void blankNarrative_returnsFalseWithoutSubmitting() {
        stubStepOutput("{\"title\":\"Weekly update\",\"narrative\":\"   \"}");

        boolean result = service.trySubmit(digest(), RUN_ID);

        assertThat(result).isFalse();
        verify(knowledgeIngestionService, never()).submit(any());
    }

    @Test
    void unparsableOutput_returnsFalse() {
        stubStepOutput("not json");

        boolean result = service.trySubmit(digest(), RUN_ID);

        assertThat(result).isFalse();
        verify(knowledgeIngestionService, never()).submit(any());
    }

    @Test
    void validNarrative_submitsNarrativeOnlyAndMarksDigestSubmitted() {
        stubStepOutput("{\"title\":\"Weekly clicks up\",\"narrative\":\"Clicks rose 18%. So what: keep going.\","
                + "\"significance\":\"notable\"}");
        when(feedRepository.findById("feed-1")).thenReturn(Optional.of(feed()));
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(new FakeConnector()));
        when(knowledgeIngestionService.submit(any())).thenReturn(new SourceReceipt("source-1", SourceReceipt.Status.ACCEPTED));
        ConnectorFeedDigest digest = digest();
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest));

        boolean result = service.trySubmit(digest, RUN_ID);

        assertThat(result).isTrue();
        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(knowledgeIngestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo("proj-1");
        assertThat(submission.sourceType()).isEqualTo("metrics.digest.gsc.search_analytics_weekly");
        assertThat(submission.sourceRef()).isEqualTo("connector://gsc/conn-1/search_analytics_weekly@2026-W30");
        assertThat(submission.contentType()).isEqualTo("text/markdown");
        assertThat(submission.payload()).isEqualTo("Clicks rose 18%. So what: keep going.");
        // Never the raw change report -- only the narrative prose.
        assertThat(submission.payload()).doesNotContain("material");
        assertThat(submission.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-07-26T00:00:00Z"));
        assertThat(submission.dedupKey()).isEqualTo("knowledge-digest:feed-1:2026-W30");
        assertThat(submission.origin().kind()).isEqualTo("connector_feed_digest");
        assertThat(submission.origin().id()).isEqualTo("digest-1");
        assertThat(submission.domain()).isEqualTo("marketing");

        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SUBMITTED);
        assertThat(digest.getKnowledgeSourceId()).isEqualTo("source-1");
    }

    @Test
    void duplicateReceipt_stillMarksDigestSubmitted() {
        stubStepOutput("{\"title\":\"t\",\"narrative\":\"n\"}");
        when(feedRepository.findById("feed-1")).thenReturn(Optional.of(feed()));
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(new FakeConnector()));
        when(knowledgeIngestionService.submit(any())).thenReturn(new SourceReceipt("source-existing", SourceReceipt.Status.DUPLICATE));
        ConnectorFeedDigest digest = digest();
        when(digestRepository.findById("digest-1")).thenReturn(Optional.of(digest));

        boolean result = service.trySubmit(digest, RUN_ID);

        assertThat(result).isTrue();
        assertThat(digest.getStatus()).isEqualTo(DigestStatus.SUBMITTED);
        assertThat(digest.getKnowledgeSourceId()).isEqualTo("source-existing");
    }
}
