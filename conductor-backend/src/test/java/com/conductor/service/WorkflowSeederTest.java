package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.ProjectSkillRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.lifecycle.SkillRegistry;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.SystemTriggerRegistry;
import com.conductor.workflow.lifecycle.WorkflowDefinitionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSeederTest {

    @Mock private WorkflowDefinitionRepository workflowRepository;
    @Mock private WorkflowDefinitionVersionRepository versionRepository;

    private WorkflowSeeder seeder;
    private Project project;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        seeder = new WorkflowSeeder(workflowRepository, versionRepository, new ObjectMapper());
        project = new Project();
        project.setId("proj-1");
    }

    private JsonNode resource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return mapper.readTree(in);
        }
    }

    private Statechart marketingStatechart() throws Exception {
        return Statechart.parse(resource("/schema/examples/marketing.workflow.json"));
    }

    @Test
    void seedsEngineeringHeaderAndVersionSnapshot() {
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "ENGINEERING")).thenReturn(false);
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
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "ENGINEERING")).thenReturn(true);

        seeder.seedEngineering(project);

        verify(workflowRepository, never()).save(any());
        verifyNoInteractions(versionRepository);
    }

    @Test
    void marketingDefinitionPassesLifecycleValidation() throws Exception {
        WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator(
                new SkillRegistry(mapper, mock(ProjectSkillRepository.class)),
                new SystemTriggerRegistry(mapper));

        WorkflowValidationResult result = validator.validate("proj-1", resource("/schema/examples/marketing.workflow.json"));

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void marketingDefinitionDeclaresPostNounInMarketingAreaOnACalendarView() throws Exception {
        Statechart chart = marketingStatechart();

        assertThat(chart.slug()).isEqualTo("MARKETING");
        assertThat(chart.area()).isEqualTo("MARKETING");
        assertThat(chart.noun()).isEqualTo("Post");
        assertThat(chart.defaultView()).isEqualTo("calendar");
        assertThat(chart.version()).isEqualTo(1);
        assertThat(chart.state()).isEqualTo("PUBLISHED");
        assertThat(chart.schemaVersion()).isEqualTo(1);
        assertThat(chart.types()).containsExactly("POST");
        assertThat(chart.assetTypes())
                .containsExactly("facebook_post", "instagram_post", "youtube_video", "tiktok_post");
    }

    @Test
    void marketingApprovalTransitionIsReviewGatedForTheReviewerRole() throws Exception {
        StatechartTransition approve = marketingStatechart().transition("IN_REVIEW", "APPROVED").orElseThrow();

        assertThat(approve.label()).isEqualTo("Approve");
        assertThat(approve.requiresReview()).isTrue();
        assertThat(approve.reviewOutcomes()).containsExactly("approve", "request_changes");
        assertThat(approve.reviewerRole()).isEqualTo("REVIEWER");
    }

    @Test
    void marketingStatechartCoversTheDraftToPublishedPipeline() throws Exception {
        Statechart chart = marketingStatechart();

        assertThat(chart.initialStatus().orElseThrow().id()).isEqualTo("DRAFT");
        assertThat(chart.statuses().stream().map(s -> s.id()).toList())
                .containsExactly("DRAFT", "IN_REVIEW", "CHANGES_REQUESTED", "APPROVED", "SCHEDULED", "PUBLISHED", "FAILED");
        assertThat(chart.isTerminal("PUBLISHED")).isTrue();
        assertThat(chart.isTerminal("FAILED")).isFalse();
        assertThat(chart.transition("SCHEDULED", "PUBLISHED")).isPresent();
        assertThat(chart.transition("SCHEDULED", "FAILED")).isPresent();
        assertThat(chart.transition("FAILED", "SCHEDULED")).isPresent();
        assertThat(chart.transition("CHANGES_REQUESTED", "IN_REVIEW")).isPresent();
    }

    @Test
    void seedsMarketingHeaderAndVersionSnapshot() {
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "MARKETING")).thenReturn(false);
        when(workflowRepository.save(any(WorkflowDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedMarketing(project);

        ArgumentCaptor<WorkflowDefinition> headerCaptor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(workflowRepository).save(headerCaptor.capture());
        WorkflowDefinition header = headerCaptor.getValue();
        assertThat(header.getName()).isEqualTo("MARKETING");
        assertThat(header.getState()).isEqualTo("PUBLISHED");
        assertThat(header.getArea()).isEqualTo("MARKETING");
        assertThat(header.getVersion()).isEqualTo(1);
        assertThat(header.getSchemaVersion()).isEqualTo(1);
        assertThat(header.isSidebarEnabled()).isTrue();
        assertThat(header.isLifecycle()).isTrue();
        assertThat(header.getDefinition().get("id").asText()).isEqualTo("MARKETING");
        assertThat(header.getDefinition().get("noun").asText()).isEqualTo("Post");

        ArgumentCaptor<WorkflowDefinitionVersion> versionCaptor = ArgumentCaptor.forClass(WorkflowDefinitionVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        WorkflowDefinitionVersion snapshot = versionCaptor.getValue();
        assertThat(snapshot.getVersion()).isEqualTo(1);
        assertThat(snapshot.getSchemaVersion()).isEqualTo(1);
        assertThat(snapshot.getDefinition().get("id").asText()).isEqualTo("MARKETING");
        assertThat(snapshot.getWorkflowDefinition()).isSameAs(header);
    }

    @Test
    void isIdempotentWhenMarketingAlreadyExists() {
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "MARKETING")).thenReturn(true);

        seeder.seedMarketing(project);

        verify(workflowRepository, never()).save(any());
        verifyNoInteractions(versionRepository);
    }

    @Test
    void seedingMarketingLeavesTheEngineeringDefinitionUntouched() {
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "ENGINEERING")).thenReturn(false);
        when(workflowRepository.existsByProjectIdAndDefinitionSlug("proj-1", "MARKETING")).thenReturn(false);
        when(workflowRepository.save(any(WorkflowDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.seedEngineering(project);
        seeder.seedMarketing(project);

        ArgumentCaptor<WorkflowDefinition> headerCaptor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(workflowRepository, times(2)).save(headerCaptor.capture());
        List<WorkflowDefinition> headers = headerCaptor.getAllValues();
        assertThat(headers).extracting(WorkflowDefinition::getName)
                .containsExactly("ENGINEERING", "MARKETING");
        assertThat(headers.get(0).getDefinition().get("noun").asText()).isEqualTo("Issue");
        assertThat(headers.get(1).getDefinition().get("noun").asText()).isEqualTo("Post");
        verify(versionRepository, times(2)).save(any(WorkflowDefinitionVersion.class));
    }
}
