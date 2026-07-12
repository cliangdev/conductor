package com.conductor.service;

import com.conductor.entity.WorkItemStepRun;
import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.repository.WorkItemStepRunRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.StepRunInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WorkItemStepRunServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private WorkItemStepRunRepository stepRunRepository;
    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private WorkItemStepRunService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        stepRunRepository = Mockito.mock(WorkItemStepRunRepository.class);
        workItemRepository = Mockito.mock(WorkItemRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        service = new WorkItemStepRunService(stepRunRepository, workItemRepository, projectSecurityService);
        when(stepRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private WorkItem workItem() {
        WorkItem workItem = new WorkItem();
        workItem.setId(ISSUE_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        workItem.setProject(project);
        return workItem;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void createsStepRunPersistingScalarsAndPassingNestedJsonThrough() {
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem()));
        // The controller owns the typed↔JSON translation; the service stores/returns the produced[] JSON as-is.
        JsonNode produced = mapper.valueToTree(List.of(
                Map.of("kind", "asset", "ref", "https://x/pr/1", "assetType", "github_pr")));
        StepRunInput input = new StepRunInput(
                null, "CODE_REVIEW", "DONE", "skill", "conductor:implement", "AWAITING_REVIEW",
                "Implement the assets table per tasks.json", "czl0909", null, null,
                produced, null, null);

        WorkItemStepRun response = service.createStepRun(PROJECT_ID, ISSUE_ID, input, caller());

        assertThat(response.getWorkItem().getId()).isEqualTo(ISSUE_ID);
        assertThat(response.getStatus()).isEqualTo("AWAITING_REVIEW");
        assertThat(response.getStepKind()).isEqualTo("skill");
        assertThat(response.getSkill()).isEqualTo("conductor:implement");
        assertThat(response.getInputBrief()).contains("assets table");
        assertThat(response.getProduced()).hasSize(1);
        assertThat(response.getProduced().get(0).get("ref").asText()).isEqualTo("https://x/pr/1");
        assertThat(response.getProduced().get(0).get("assetType").asText()).isEqualTo("github_pr");
    }
}
