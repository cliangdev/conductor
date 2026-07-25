package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowTriggerService#onGitHubPullRequest} — the {@code github.pull_request} sibling of
 * {@code onConductorEvent}, same trigger/filter/skip contract.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTriggerServiceGitHubPullRequestTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowExecutionEngine executionEngine;
    @Mock private WorkflowScheduleRepository scheduleRepository;
    @Mock private WorkflowFailureCircuitBreaker circuitBreaker;

    private WorkflowTriggerService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerService(workflowRepository, workflowRunRepository, executionEngine,
                scheduleRepository, new ObjectMapper(), new WorkflowYamlParser(), circuitBreaker);
        lenient().when(workflowRunRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> {
            WorkflowRun run = inv.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            return run;
        });
    }

    private WorkflowDefinition workflow(String yaml) {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId("wf-1");
        workflow.setEnabled(true);
        workflow.setYaml(yaml);
        return workflow;
    }

    private NotificationEvent prEvent(Map<String, String> metadata) {
        return NotificationEvent.of(EventType.GITHUB_PULL_REQUEST, PROJECT_ID, metadata);
    }

    private static final String YAML_NO_FILTERS = """
            on:
              github.pull_request: {}
            jobs:
              review:
                steps:
                  - type: http
                    url: https://example.com
            """;

    private static final String YAML_ACTION_FILTER_OPENED = """
            on:
              github.pull_request:
                filters:
                  actions: [opened]
            jobs:
              review:
                steps:
                  - type: http
                    url: https://example.com
            """;

    private static final String YAML_LABEL_FILTER = """
            on:
              github.pull_request:
                filters:
                  labels: [code_review_ready]
            jobs:
              review:
                steps:
                  - type: http
                    url: https://example.com
            """;

    @Test
    void wrongEventType_noWorkflowsCreated() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1", "fromStatus", "TODO", "toStatus", "DONE"));

        service.onGitHubPullRequest(event);

        verify(workflowRepository, never()).findByProjectId(any());
        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void noFilters_anyAction_createsRun() {
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_NO_FILTERS)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "opened", "repoFullName", "org/repo")));

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerType()).isEqualTo("github.pull_request");
    }

    @Test
    void actionFilter_matchingAction_createsRun() {
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_ACTION_FILTER_OPENED)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "opened")));

        verify(workflowRunRepository).save(any(WorkflowRun.class));
    }

    @Test
    void actionFilter_nonMatchingAction_noRunCreated() {
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_ACTION_FILTER_OPENED)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "assigned")));

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void labelFilterDeclared_openedActionWithNoLabelKey_correctlyFailsFilter() {
        // `opened` never carries a `label` metadata key — a declared labelFilter must exclude it
        // rather than throw on the missing key.
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_LABEL_FILTER)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "opened")));

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void labelFilterDeclared_labeledActionWithMatchingLabel_createsRun() {
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_LABEL_FILTER)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "labeled", "label", "code_review_ready")));

        verify(workflowRunRepository).save(any(WorkflowRun.class));
    }

    @Test
    void labelFilterDeclared_labeledActionWithNonMatchingLabel_noRunCreated() {
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(workflow(YAML_LABEL_FILTER)));

        service.onGitHubPullRequest(prEvent(Map.of("action", "labeled", "label", "wontfix")));

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void disabledWorkflow_isSkipped() {
        WorkflowDefinition disabled = workflow(YAML_NO_FILTERS);
        disabled.setEnabled(false);
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(disabled));

        service.onGitHubPullRequest(prEvent(Map.of("action", "opened")));

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void lifecycleWorkflow_withNullYaml_isSkipped() {
        WorkflowDefinition lifecycle = workflow(null);
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(lifecycle));

        service.onGitHubPullRequest(prEvent(Map.of("action", "opened")));

        verify(workflowRunRepository, never()).save(any());
    }
}
