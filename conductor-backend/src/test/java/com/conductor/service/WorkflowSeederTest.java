package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSeederTest {

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowDefinitionVersionRepository versionRepository;

    private WorkflowSeeder seeder;
    private Project project;

    @BeforeEach
    void setUp() {
        seeder = new WorkflowSeeder(workflowRepository, versionRepository, new ObjectMapper());
        project = new Project();
        project.setId("proj-1");
    }

    @Test
    void seedsEngineeringHeaderAndVersionSnapshot() {
        when(workflowRepository.findByProjectIdAndName("proj-1", "ENGINEERING")).thenReturn(Optional.empty());
        when(workflowRepository.save(any(WorkflowDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedEngineering(project);

        ArgumentCaptor<WorkflowDefinition> headerCaptor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(workflowRepository).save(headerCaptor.capture());
        WorkflowDefinition header = headerCaptor.getValue();
        assertThat(header.getName()).isEqualTo("ENGINEERING");
        assertThat(header.getState()).isEqualTo("PUBLISHED");
        assertThat(header.getArea()).isEqualTo("ENGINEERING");
        assertThat(header.getVersion()).isEqualTo(1);
        assertThat(header.getSchemaVersion()).isEqualTo(1);
        assertThat(header.isSidebarEnabled()).isTrue();
        assertThat(header.isLifecycle()).isTrue();
        assertThat(header.getDefinition().get("id").asText()).isEqualTo("ENGINEERING");

        ArgumentCaptor<WorkflowDefinitionVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowDefinitionVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        WorkflowDefinitionVersion snapshot = versionCaptor.getValue();
        assertThat(snapshot.getVersion()).isEqualTo(1);
        assertThat(snapshot.getSchemaVersion()).isEqualTo(1);
        assertThat(snapshot.getDefinition().get("id").asText()).isEqualTo("ENGINEERING");
        assertThat(snapshot.getWorkflowDefinition()).isSameAs(header);
    }

    @Test
    void isIdempotentWhenEngineeringAlreadyExists() {
        when(workflowRepository.findByProjectIdAndName("proj-1", "ENGINEERING"))
                .thenReturn(Optional.of(new WorkflowDefinition()));

        seeder.seedEngineering(project);

        verify(workflowRepository, never()).save(any());
        verifyNoInteractions(versionRepository);
    }
}
