package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.BusinessException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void setSidebarEnabledRejectsAutomationWorkflows() {
        WorkflowDefinition automation = new WorkflowDefinition();
        automation.setId("wf-auto");
        automation.setProject(projectWithId("proj-1"));
        automation.setName("my-workflow");
        automation.setYaml("on: { workflow_dispatch: {} }");
        automation.setDefinition(null); // YAML automation — not a lifecycle workflow

        when(projectSecurityService.isAdminOrCreator("proj-1", "user-1")).thenReturn(true);
        when(workflowRepository.findById("wf-auto")).thenReturn(Optional.of(automation));

        assertThatThrownBy(() -> service.setSidebarEnabled("proj-1", "wf-auto", "user-1", true))
                .isInstanceOf(BusinessException.class);
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void listWorkflowsFiltersAreAuthoritativeAndCompose() throws Exception {
        WorkflowDefinition lifecycle = new WorkflowDefinition();
        lifecycle.setId("wf-life");
        lifecycle.setName("ENGINEERING");
        lifecycle.setState("PUBLISHED");
        lifecycle.setSidebarEnabled(true);
        lifecycle.setDefinition(new ObjectMapper().readTree("{\"id\":\"ENGINEERING\",\"statuses\":[{\"id\":\"DRAFT\"}]}"));

        WorkflowDefinition automation = new WorkflowDefinition();
        automation.setId("wf-auto");
        automation.setName("my-workflow");
        automation.setState("PUBLISHED");
        automation.setDefinition(null);

        when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(lifecycle, automation));

        // lifecycle=true excludes the automation (authoritative isLifecycle predicate).
        assertThat(service.listWorkflows("proj-1", true, null, null)).containsExactly(lifecycle);
        // lifecycle=false returns only the automation.
        assertThat(service.listWorkflows("proj-1", false, null, null)).containsExactly(automation);
        // sidebar=true composes with the rest.
        assertThat(service.listWorkflows("proj-1", true, "PUBLISHED", true)).containsExactly(lifecycle);
        // No filters → everything (regression: existing behavior preserved).
        assertThat(service.listWorkflows("proj-1", null, null, null)).containsExactly(lifecycle, automation);
    }

    private Project projectWithId(String id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }
}
