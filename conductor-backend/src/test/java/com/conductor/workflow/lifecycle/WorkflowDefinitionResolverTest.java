package com.conductor.workflow.lifecycle;

import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class WorkflowDefinitionResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowDefinitionVersionRepository versionRepository;
    private WorkflowDefinitionResolver resolver;

    @BeforeEach
    void setUp() {
        versionRepository = Mockito.mock(WorkflowDefinitionVersionRepository.class);
        resolver = new WorkflowDefinitionResolver(versionRepository, mapper);
    }

    @Test
    void resolvesBuiltInEngineeringWhenNoPublishedSnapshot() {
        when(versionRepository.findLatestPublished(any(), eq("ENGINEERING"))).thenReturn(Optional.empty());

        Statechart sc = resolver.resolveRequired("project-1", "ENGINEERING");

        assertThat(sc.slug()).isEqualTo("ENGINEERING");
        assertThat(sc.noun()).isEqualTo("Issue");
        assertThat(resolver.isBuiltIn("ENGINEERING")).isTrue();
    }

    @Test
    void unknownSlugResolvesEmptyAndResolveRequiredThrows() {
        when(versionRepository.findLatestPublished(any(), any())).thenReturn(Optional.empty());

        assertThat(resolver.resolve("project-1", "MYSTERY")).isEmpty();
        assertThatThrownBy(() -> resolver.resolveRequired("project-1", "MYSTERY"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(resolver.isBuiltIn("MYSTERY")).isFalse();
    }

    @Test
    void prefersProjectPublishedSnapshotOverBuiltIn() throws Exception {
        // A project that published its own ENGINEERING (different noun) wins over the built-in.
        String custom = """
                {
                  "schemaVersion": 1, "id": "ENGINEERING", "area": "ENGINEERING", "version": 2,
                  "state": "PUBLISHED", "noun": "Ticket", "default_view": "board", "types": ["BUG"],
                  "statuses": [
                    {"id": "OPEN", "category": "open", "initial": true},
                    {"id": "DONE", "category": "terminal", "terminal": true}
                  ],
                  "transitions": [ {"from": "OPEN", "to": "DONE", "label": "Close"} ]
                }
                """;
        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setVersion(2);
        snapshot.setDefinition(mapper.readTree(custom));
        when(versionRepository.findLatestPublished("project-1", "ENGINEERING")).thenReturn(Optional.of(snapshot));

        Statechart sc = resolver.resolveRequired("project-1", "ENGINEERING");

        assertThat(sc.noun()).isEqualTo("Ticket");
        assertThat(sc.version()).isEqualTo(2);
    }

    @Test
    void resolvesPinnedVersionSnapshotExactly() {
        // Version-pinned resolution: a Work Item pinned to (ENGINEERING, 3) resolves that exact snapshot.
        String v3 = """
                {
                  "schemaVersion": 1, "id": "ENGINEERING", "area": "ENGINEERING", "version": 3,
                  "state": "PUBLISHED", "noun": "Ticket", "default_view": "board", "types": ["BUG"],
                  "statuses": [
                    {"id": "OPEN", "category": "open", "initial": true},
                    {"id": "DONE", "category": "terminal", "terminal": true}
                  ],
                  "transitions": [ {"from": "OPEN", "to": "DONE", "label": "Close"} ]
                }
                """;
        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setVersion(3);
        snapshot.setDefinition(mapperReadTree(v3));
        when(versionRepository.findByProjectSlugAndVersion("project-1", "ENGINEERING", 3))
                .thenReturn(Optional.of(snapshot));

        Statechart sc = resolver.resolveRequired("project-1", "ENGINEERING", 3);

        assertThat(sc.version()).isEqualTo(3);
        assertThat(sc.noun()).isEqualTo("Ticket");
    }

    private com.fasterxml.jackson.databind.JsonNode mapperReadTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
