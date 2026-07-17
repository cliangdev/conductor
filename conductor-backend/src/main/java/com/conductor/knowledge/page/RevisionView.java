package com.conductor.knowledge.page;

import com.conductor.knowledge.Actor;

import java.time.OffsetDateTime;
import java.util.List;

public record RevisionView(
        int version,
        KnowledgePageRevision.ChangeKind changeKind,
        Actor actor,
        OffsetDateTime createdAt,
        List<String> sourceRefs
) {
}
