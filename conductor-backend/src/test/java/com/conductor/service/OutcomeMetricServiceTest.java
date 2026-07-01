package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.service.view.OutcomeMetricView;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        // Resolution is DB-only: back the resolver with the seeded ENGINEERING snapshot (which opts out of
        // outcome metrics). Tests for other workflows stub their own snapshot.
        when(versionRepository.findLatestPublished(any(), eq("ENGINEERING")))
                .thenReturn(Optional.of(engineeringSnapshot()));
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository);
        service = new OutcomeMetricService(workItemRepository, projectSecurityService, resolver, mapper);
        when(workItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private WorkflowDefinitionVersion engineeringSnapshot() {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            WorkflowDefinitionVersion v = new WorkflowDefinitionVersion();
            v.setVersion(1);
            v.setDefinition(mapper.readTree(in));
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkItem workItem(String workflow) {
        WorkItem workItem = new WorkItem();
        workItem.setId(ISSUE_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        workItem.setProject(project);
        workItem.setWorkflow(workflow);
        return workItem;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void recordsAndAccumulatesObservations() {
        WorkItem workItem = workItem("ENGINEERING");
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem));

        service.record(PROJECT_ID, ISSUE_ID, 10.0, null, null, caller());
        OutcomeMetricView response =
                service.record(PROJECT_ID, ISSUE_ID, 12.5, null, null, caller());

        assertThat(response.observations()).hasSize(2);
        assertThat(response.observations()).extracting(o -> o.value()).containsExactly(10.0, 12.5);
        // ENGINEERING opts out of metrics -> no metadata.
        assertThat(response.name()).isNull();
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
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem("GROWTH")));

        OutcomeMetricView response =
                service.record(PROJECT_ID, ISSUE_ID, 42.0, null, null, caller());

        assertThat(response.name()).isEqualTo("clicks");
        assertThat(response.unit()).isEqualTo("count");
        assertThat(response.direction()).isEqualTo("higher_better");
        assertThat(response.observations()).hasSize(1);
    }
}
