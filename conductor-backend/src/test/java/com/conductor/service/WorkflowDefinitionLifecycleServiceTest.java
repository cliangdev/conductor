package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.lifecycle.SkillRegistry;
import com.conductor.workflow.lifecycle.WorkflowDefinitionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDefinitionLifecycleServiceTest {

    private static final String PROJECT_ID = "project-1";
    private static final String WORKFLOW_ID = "wf-1";
    private static final String CALLER_ID = "user-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowDefinitionRepository repository;
    private ProjectSecurityService security;
    private WorkflowDefinitionLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(WorkflowDefinitionRepository.class);
        security = Mockito.mock(ProjectSecurityService.class);
        WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator(new SkillRegistry(mapper));
        service = new WorkflowDefinitionLifecycleService(repository, security, validator);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowDefinition definitionRow(JsonNode def) {
        WorkflowDefinition row = new WorkflowDefinition();
        Project project = new Project();
        project.setId(PROJECT_ID);
        row.setProject(project);
        row.setState("DRAFT");
        row.setDefinition(def);
        return row;
    }

    private JsonNode engineeringDefinition() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            return mapper.readTree(in);
        }
    }

    @Test
    void nonAdminCannotPublish() {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void missingWorkflowThrowsNotFound() {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(true);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void definitionInAnotherProjectThrowsNotFound() throws Exception {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(true);
        WorkflowDefinition row = definitionRow(engineeringDefinition());
        row.getProject().setId("other-project");
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void rowWithoutDefinitionIsUnprocessable() {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(true);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definitionRow(null)));

        assertThatThrownBy(() -> service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no definition");
    }

    @Test
    void invalidDefinitionIsUnprocessable() throws Exception {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(true);
        JsonNode invalid = mapper.readTree(
                mapper.writeValueAsString(engineeringDefinition()).replace("conductor:implement", "conductor:ghost"));
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definitionRow(invalid)));

        assertThatThrownBy(() -> service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("invalid");
        verify(repository, never()).save(any());
    }

    @Test
    void validDraftIsPromotedToPublished() throws Exception {
        when(security.isAdminOrCreator(PROJECT_ID, CALLER_ID)).thenReturn(true);
        WorkflowDefinition row = definitionRow(engineeringDefinition());
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(row));

        WorkflowDefinition published = service.publish(PROJECT_ID, WORKFLOW_ID, CALLER_ID);

        assertThat(published.getState()).isEqualTo("PUBLISHED");
        assertThat(published.getVersion()).isEqualTo(1);
        assertThat(published.getSchemaVersion()).isEqualTo(1);
        verify(repository).save(row);
    }
}
