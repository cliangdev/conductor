package com.conductor.integration.ingest;

import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestSpec;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import org.springframework.stereotype.Component;

/**
 * Default {@link DigestSink}: relays each pulled {@link IngestItem} straight into the Knowledge Center
 * inbox via {@link KnowledgeIngestionService#submit}, unfiltered. {@code domain} is left {@code null}
 * so {@code KnowledgeDomainResolver} routes by {@code sourceType} pattern — {@link IngestSpec}'s
 * {@code suggestedDomain} is a provisioning-time seed only, never read here (see its javadoc).
 */
@Component
public class KnowledgeIngestionDigestSink implements DigestSink {

    private final KnowledgeIngestionService knowledgeIngestionService;

    public KnowledgeIngestionDigestSink(KnowledgeIngestionService knowledgeIngestionService) {
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    @Override
    public void accept(String projectId, IngestItem item) {
        knowledgeIngestionService.submit(new KnowledgeSubmission(
                projectId,
                item.sourceType(),
                item.sourceRef(),
                item.title(),
                item.contentType(),
                item.payload(),
                item.occurredAt(),
                item.dedupKey(),
                new KnowledgeSubmission.Origin("connector_feed", item.sourceRef()),
                item.metadata(),
                null));
    }
}
