package com.conductor.service;

import com.conductor.generated.model.WorkflowTransitionView;
import com.conductor.generated.model.WorkflowView;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The view a page loads must carry everything the review bar needs; a missing field there is a missing button. */
class WorkflowViewServiceTest {

    @Test
    void theViewCarriesEachGatedEdgesReviewOutcomes() throws Exception {
        WorkflowDefinitionResolver resolver = mock(WorkflowDefinitionResolver.class);
        ProjectSecurityService security = mock(ProjectSecurityService.class);
        when(security.isProjectMember(eq("p1"), eq("u1"))).thenReturn(true);
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")) {
            when(resolver.resolveRequired(eq("p1"), eq("MARKETING"), any()))
                    .thenReturn(Statechart.parse(new ObjectMapper().readTree(in)));
        }
        WorkflowViewService service = new WorkflowViewService(resolver, security,
                mock(WorkflowDefinitionRepository.class), mock(WorkflowDefinitionVersionRepository.class),
                mock(WorkItemRepository.class));

        WorkflowView view = service.getView("p1", "MARKETING", null, "u1");

        WorkflowTransitionView approve = view.getTransitions().stream()
                .filter(t -> "IN_REVIEW".equals(t.getFrom()) && "APPROVED".equals(t.getTo()))
                .findFirst().orElseThrow();
        assertThat(approve.getRequiresReview()).isTrue();
        // Without these the review bar offers no verdict at all and renders nothing.
        assertThat(approve.getReviewOutcomes()).containsExactly("approve", "request_changes");
        WorkflowTransitionView submit = view.getTransitions().stream()
                .filter(t -> "DRAFT".equals(t.getFrom())).findFirst().orElseThrow();
        assertThat(submit.getReviewOutcomes()).isNullOrEmpty();
    }
}
