package com.conductor.knowledge;

/** Result of {@link KnowledgeIngestionService#submit}. */
public record SourceReceipt(String sourceId, Status status) {

    public enum Status {
        /** A new {@code knowledge_sources} row was inserted. */
        ACCEPTED,
        /** The dedup key matched an existing row; {@code sourceId} is that row's id. */
        DUPLICATE
    }
}
