package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.generated.model.OutcomeMetricResponse;
import com.conductor.generated.model.RecordMetricObservationRequest;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class OutcomeMetricServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private WorkflowDefinitionVersionRepository versionRepository;
    private OutcomeMetricService service;
    // Mirror the production ObjectMapper bean (RestTemplateConfig) — JavaTimeModule for OffsetDateTime.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        workItemRepository = Mockito.mock(WorkItemRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        versionRepository = Mockito.mock(WorkflowDefinitionVersionRepository.class);
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository, mapper);
        service = new OutcomeMetricService(workItemRepository, projectSecurityService, resolver, mapper);
        when(workItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private WorkItem issue(String workflow) {
        WorkItem issue = new WorkItem();
        issue.setId(ISSUE_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        issue.setProject(project);
        issue.setWorkflow(workflow);
        return issue;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void recordsAndAccumulatesObservations() {
        WorkItem issue = issue("ENGINEERING");
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

        service.record(PROJECT_ID, ISSUE_ID, new RecordMetricObservationRequest().value(10.0), caller());
        OutcomeMetricResponse response =
                service.record(PROJECT_ID, ISSUE_ID, new RecordMetricObservationRequest().value(12.5), caller());

        assertThat(response.getObservations()).hasSize(2);
        assertThat(response.getObservations()).extracting(o -> o.getValue()).containsExactly(10.0, 12.5);
        // ENGINEERING opts out of metrics -> no metadata.
        assertThat(response.getName()).isNull();
    }

    @Test
    void includesWorkflowMetricMetadataWhenDefined() throws Exception {
        String def = """
                {
                  "schemaVersion": 1, "id": "GROWTH", "area": "MARKETING", "version": 1, "state": "PUBLISHED",
                  "noun": "Campaign", "default_view": "list", "types": ["SEO"],
                  "metric": { "name": "clicks", "unit": "count", "direction": "higher_better" },
                  "statuses": [
                    {"id": "OPEN", "category": "open", "initial": true},
                    {"id": "DONE", "category": "terminal", "terminal": true}
                  ],
                  "transitions": [ {"from": "OPEN", "to": "DONE", "label": "Close"} ]
                }
                """;
        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setVersion(1);
        snapshot.setDefinition(mapper.readTree(def));
        when(versionRepository.findLatestPublished(PROJECT_ID, "GROWTH")).thenReturn(Optional.of(snapshot));
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue("GROWTH")));

        OutcomeMetricResponse response =
                service.record(PROJECT_ID, ISSUE_ID, new RecordMetricObservationRequest().value(42.0), caller());

        assertThat(response.getName()).isEqualTo("clicks");
        assertThat(response.getUnit()).isEqualTo("count");
        assertThat(response.getDirection()).isEqualTo("higher_better");
        assertThat(response.getObservations()).hasSize(1);
    }
}
