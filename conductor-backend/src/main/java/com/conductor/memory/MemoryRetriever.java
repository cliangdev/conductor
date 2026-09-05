package com.conductor.memory;

import java.util.List;

/**
 * The retrieval seam for agent memory. {@link FtsMemoryRetriever} (Postgres full-text search) is the
 * only implementation today; this interface is the seam for a future pgvector/hybrid retriever.
 * Consumers (tools, conversation augmentation) must always go through a {@code MemoryRetriever} and
 * never query {@link AgentMemoryRepository}'s search methods directly, so retrieval strategy stays
 * swappable in one place.
 */
public interface MemoryRetriever {

    /** Top {@code limit} live memories for {@code projectId}, ranked by {@link MemoryScoring}. */
    List<ScoredMemory> retrieve(String projectId, String query, int limit);

    record ScoredMemory(AgentMemory memory, double score, double relevance, double recency) {
    }
}
