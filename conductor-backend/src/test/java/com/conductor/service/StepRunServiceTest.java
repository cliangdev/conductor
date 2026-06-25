package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.generated.model.CreateStepRunRequest;
import com.conductor.generated.model.StepRunProduced;
import com.conductor.generated.model.StepRunResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.StepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class StepRunServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private StepRunRepository stepRunRepository;
    private IssueRepository issueRepository;
    private ProjectSecurityService projectSecurityService;
    private StepRunService service;

    @BeforeEach
    void setUp() {
        stepRunRepository = Mockito.mock(StepRunRepository.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        service = new StepRunService(stepRunRepository, issueRepository, projectSecurityService, new ObjectMapper());
        when(stepRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private Issue issue() {
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        issue.setProject(project);
        return issue;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void createsStepRunRoundTrippingNestedJson() {
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue()));
        CreateStepRunRequest request = new CreateStepRunRequest(
                CreateStepRunRequest.StepKindEnum.SKILL,
                CreateStepRunRequest.StatusEnum.AWAITING_REVIEW,
                "Implement the assets table per tasks.json",
                "czl0909");
        request.setFromStatus("CODE_REVIEW");
        request.setToStatus("DONE");
        request.setSkill("conductor:implement");
        StepRunProduced produced = new StepRunProduced(StepRunProduced.KindEnum.ASSET, "https://x/pr/1");
        produced.setAssetType("github_pr");
        request.setProduced(List.of(produced));

        StepRunResponse response = service.createStepRun(PROJECT_ID, ISSUE_ID, request, caller());

        assertThat(response.getIssueId()).isEqualTo(ISSUE_ID);
        assertThat(response.getStatus()).isEqualTo("AWAITING_REVIEW");
        assertThat(response.getStepKind()).isEqualTo("skill");
        assertThat(response.getSkill()).isEqualTo("conductor:implement");
        assertThat(response.getInputBrief()).contains("assets table");
        assertThat(response.getProduced()).hasSize(1);
        assertThat(response.getProduced().get(0).getRef()).isEqualTo("https://x/pr/1");
        assertThat(response.getProduced().get(0).getAssetType()).isEqualTo("github_pr");
    }
}
