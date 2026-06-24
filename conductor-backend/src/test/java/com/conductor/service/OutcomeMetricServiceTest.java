package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.generated.model.OutcomeMetricResponse;
import com.conductor.generated.model.RecordMetricObservationRequest;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
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

    private IssueRepository issueRepository;
    private ProjectSecurityService projectSecurityService;
    private WorkflowDefinitionRepository definitionRepository;
    private OutcomeMetricService service;
    // Mirror the production ObjectMapper bean (RestTemplateConfig) — JavaTimeModule for OffsetDateTime.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        issueRepository = Mockito.mock(IssueRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        definitionRepository = Mockito.mock(WorkflowDefinitionRepository.class);
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(definitionRepository, mapper);
        service = new OutcomeMetricService(issueRepository, projectSecurityService, resolver, mapper);
        when(issueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private Issue issue(String workflow) {
        Issue issue = new Issue();
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
        Issue issue = issue("ENGINEERING");
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));

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
        WorkflowDefinition row = new WorkflowDefinition();
        Project p = new Project();
        p.setId(PROJECT_ID);
        row.setProject(p);
        row.setState("PUBLISHED");
        row.setVersion(1);
        row.setDefinition(mapper.readTree(def));
        when(definitionRepository.findLatestPublishedBySlug(PROJECT_ID, "GROWTH")).thenReturn(Optional.of(row));
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue("GROWTH")));

        OutcomeMetricResponse response =
                service.record(PROJECT_ID, ISSUE_ID, new RecordMetricObservationRequest().value(42.0), caller());

        assertThat(response.getName()).isEqualTo("clicks");
        assertThat(response.getUnit()).isEqualTo("count");
        assertThat(response.getDirection()).isEqualTo("higher_better");
        assertThat(response.getObservations()).hasSize(1);
    }
}
