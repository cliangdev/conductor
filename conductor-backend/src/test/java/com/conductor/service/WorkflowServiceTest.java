package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowSecretRepository;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.WorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectSecurityService projectSecurityService;
    @Mock private WorkflowValidator validator;
    @Mock private WorkflowSecretRepository secretRepository;
    @Mock private WorkflowTriggerService workflowTriggerService;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowRepository, projectRepository, projectSecurityService,
                validator, secretRepository, workflowTriggerService, new ObjectMapper());
    }

    @Test
    void setSidebarEnabledTogglesFlagWithoutTouchingStateVersionOrDefinition() throws Exception {
        Project project = new Project();
        project.setId("proj-1");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf-1");
        def.setProject(project);
        def.setName("ENGINEERING");
        def.setState("PUBLISHED");
        def.setVersion(3);
        def.setDefinition(new ObjectMapper().readTree("{\"id\":\"ENGINEERING\"}"));
        def.setSidebarEnabled(false);

        when(projectSecurityService.isAdminOrCreator("proj-1", "user-1")).thenReturn(true);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(def));
        when(workflowRepository.save(any(WorkflowDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDefinition result = service.setSidebarEnabled("proj-1", "wf-1", "user-1", true);

        assertThat(result.isSidebarEnabled()).isTrue();
        // AC-P0-2.5: the live toggle must not republish or mutate the statechart.
        assertThat(result.getState()).isEqualTo("PUBLISHED");
        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getDefinition().get("id").asText()).isEqualTo("ENGINEERING");
    }
}
