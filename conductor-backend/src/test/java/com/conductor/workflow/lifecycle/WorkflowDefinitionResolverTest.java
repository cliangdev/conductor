package com.conductor.workflow.lifecycle;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.WorkflowDefinitionRepository;
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
    private WorkflowDefinitionRepository repository;
    private WorkflowDefinitionResolver resolver;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WorkflowDefinitionRepository.class);
        resolver = new WorkflowDefinitionResolver(repository, mapper);
    }

    @Test
    void resolvesBuiltInEngineeringWhenNoProjectRow() {
        when(repository.findLatestPublishedBySlug(any(), eq("ENGINEERING"))).thenReturn(Optional.empty());

        Statechart sc = resolver.resolveRequired("project-1", "ENGINEERING");

        assertThat(sc.slug()).isEqualTo("ENGINEERING");
        assertThat(sc.noun()).isEqualTo("Issue");
        assertThat(resolver.isBuiltIn("ENGINEERING")).isTrue();
    }

    @Test
    void unknownSlugResolvesEmptyAndResolveRequiredThrows() {
        when(repository.findLatestPublishedBySlug(any(), any())).thenReturn(Optional.empty());

        assertThat(resolver.resolve("project-1", "MYSTERY")).isEmpty();
        assertThatThrownBy(() -> resolver.resolveRequired("project-1", "MYSTERY"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(resolver.isBuiltIn("MYSTERY")).isFalse();
    }

    @Test
    void prefersProjectPublishedDefinitionOverBuiltIn() throws Exception {
        // A project that authored its own ENGINEERING (different noun) wins over the built-in.
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
        WorkflowDefinition row = new WorkflowDefinition();
        Project project = new Project();
        project.setId("project-1");
        row.setProject(project);
        row.setState("PUBLISHED");
        row.setVersion(2);
        row.setDefinition(mapper.readTree(custom));
        when(repository.findLatestPublishedBySlug("project-1", "ENGINEERING")).thenReturn(Optional.of(row));

        Statechart sc = resolver.resolveRequired("project-1", "ENGINEERING");

        assertThat(sc.noun()).isEqualTo("Ticket");
        assertThat(sc.version()).isEqualTo(2);
    }
}
