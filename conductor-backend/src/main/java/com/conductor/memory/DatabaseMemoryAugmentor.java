package com.conductor.memory;

import com.conductor.agent.provider.ChatMessage;
import com.conductor.conversation.MemoryAugmentor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The one {@link MemoryAugmentor} implementation: retrieves scored memories via {@link MemoryRetriever}
 * and renders them into a system-prompt addendum, never into {@code window} (see {@link
 * MemoryAugmentor}'s javadoc for why). Never lets memory retrieval fail a conversation turn -- any
 * exception is logged and swallowed, returning {@link MemoryAugmentor.Augmentation#unchanged}.
 */
@Component
public class DatabaseMemoryAugmentor implements MemoryAugmentor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMemoryAugmentor.class);

    private static final int RETRIEVAL_LIMIT = 8;
    private static final int ADDENDUM_CHAR_BUDGET = 1_800;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String HEADER = "## Long-term memory\n"
            + "Workspace memories that may be relevant (background context; may be stale -- the live "
            + "conversation takes precedence; the knowledge base is the authoritative source for "
            + "documentation):\n";

    private final MemoryRetriever retriever;
    private final AgentMemoryRepository repository;
    private final boolean enabled;

    public DatabaseMemoryAugmentor(MemoryRetriever retriever, AgentMemoryRepository repository,
                                    @Value("${conductor.memory.enabled:true}") boolean enabled) {
        this.retriever = retriever;
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    public Augmentation augment(String projectId, String agentId, String conversationId,
                                 String latestUserContent, List<ChatMessage> window) {
        if (!enabled || latestUserContent == null || latestUserContent.isBlank()) {
            return Augmentation.unchanged(window);
        }
        try {
            List<MemoryRetriever.ScoredMemory> scored = retriever.retrieve(projectId, latestUserContent, RETRIEVAL_LIMIT);
            if (scored.isEmpty()) {
                return Augmentation.unchanged(window);
            }

            StringBuilder sb = new StringBuilder(HEADER);
            List<String> includedIds = new ArrayList<>();
            int i = 0;
            for (; i < scored.size(); i++) {
                MemoryRetriever.ScoredMemory s = scored.get(i);
                String line = "- [" + s.memory().getMemoryType().name().toLowerCase() + " · "
                        + s.memory().getValidFrom().format(DATE_FORMAT) + "] " + s.memory().getContent() + "\n";
                if (sb.length() + line.length() > ADDENDUM_CHAR_BUDGET) {
                    break;
                }
                sb.append(line);
                includedIds.add(s.memory().getId());
            }
            if (includedIds.isEmpty()) {
                // The budget couldn't fit even the single highest-scored memory -- emitting the header
                // plus an "(N more omitted)" line with zero actual memories would be pure noise.
                return Augmentation.unchanged(window);
            }

            int omitted = scored.size() - i;
            if (omitted > 0) {
                sb.append("(").append(omitted).append(" more omitted for space)\n");
            }

            repository.bumpAccess(includedIds);
            return new Augmentation(window, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warn("Memory augmentation failed for project {} conversation {}: {}",
                    projectId, conversationId, e.getMessage());
            return Augmentation.unchanged(window);
        }
    }
}
