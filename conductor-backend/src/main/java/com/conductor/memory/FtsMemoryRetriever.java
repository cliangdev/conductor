package com.conductor.memory;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only {@link MemoryRetriever} today: Postgres full-text search over {@code agent_memories}, blended
 * with an importance/recency floor pool so a query that matches nothing on text still surfaces the
 * project's most salient memories rather than an empty result.
 */
@Component
public class FtsMemoryRetriever implements MemoryRetriever {

    private static final int FTS_CANDIDATE_LIMIT = 50;
    private static final int FLOOR_CANDIDATE_LIMIT = 20;
    private static final int MIN_TOKEN_LENGTH = 4;
    private static final int MAX_TOKENS = 12;

    private final AgentMemoryRepository repository;

    public FtsMemoryRetriever(AgentMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ScoredMemory> retrieve(String projectId, String query, int limit) {
        OffsetDateTime now = OffsetDateTime.now();

        String tsQuery = buildTsQuery(query);
        Map<String, Double> rankById = new HashMap<>();
        double maxRank = 0.0;
        if (!tsQuery.isEmpty()) {
            for (AgentMemoryRepository.MemorySearchRow row : repository.search(projectId, tsQuery, FTS_CANDIDATE_LIMIT)) {
                double rank = row.getRank() != null ? row.getRank() : 0.0;
                rankById.put(row.getId(), rank);
                maxRank = Math.max(maxRank, rank);
            }
        }

        List<AgentMemory> floor = repository.findByProjectIdAndValidToIsNullOrderByImportanceDescCreatedAtDesc(
                projectId, PageRequest.of(0, FLOOR_CANDIDATE_LIMIT));

        Set<String> candidateIds = new LinkedHashSet<>(rankById.keySet());
        for (AgentMemory memory : floor) {
            candidateIds.add(memory.getId());
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        double finalMaxRank = maxRank;
        return repository.findAllById(candidateIds).stream()
                .map(memory -> score(memory, rankById.getOrDefault(memory.getId(), 0.0), finalMaxRank, now))
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(limit)
                .toList();
    }

    private ScoredMemory score(AgentMemory memory, double rank, double maxRank, OffsetDateTime now) {
        double relevance = MemoryScoring.relevance(rank, maxRank);
        double recency = MemoryScoring.recency(memory.getLastAccessedAt(), memory.getValidFrom(), now);
        double importanceNorm = MemoryScoring.importanceNorm(memory.getImportance());
        return new ScoredMemory(memory, MemoryScoring.score(relevance, recency, importanceNorm), relevance, recency);
    }

    /**
     * Builds a {@code websearch_to_tsquery}-ready string from free text. Load-bearing: plain
     * {@code websearch_to_tsquery} ANDs bare terms together, so passing a whole chat message would
     * require every word to match and return nothing for realistic queries. Instead this tokenizes,
     * keeps distinct terms of at least {@value #MIN_TOKEN_LENGTH} characters, takes the
     * {@value #MAX_TOKENS} longest, and OR-joins them so a query matches on its most distinctive words.
     */
    static String buildTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : query.split("[^a-zA-Z0-9]+")) {
            if (token.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(token.toLowerCase());
            }
        }
        return tokens.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_TOKENS)
                .collect(Collectors.joining(" OR "));
    }
}
