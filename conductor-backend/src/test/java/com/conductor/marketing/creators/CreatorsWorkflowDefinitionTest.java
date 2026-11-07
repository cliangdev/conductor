package com.conductor.marketing.creators;

import com.conductor.repository.ProjectSkillRepository;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.lifecycle.SkillRegistry;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartField;
import com.conductor.workflow.lifecycle.SystemTriggerRegistry;
import com.conductor.workflow.lifecycle.WorkflowDefinitionValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The CREATORS lifecycle definition: it passes the validator a published Workflow passes, and says what the PRD asks. */
class CreatorsWorkflowDefinitionTest {

    private static final String RESOURCE = "/schema/examples/creators.workflow.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode definition() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            return mapper.readTree(in);
        }
    }

    @Test
    void definitionPassesLifecycleValidation() throws Exception {
        WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator(
                new SkillRegistry(mapper, mock(ProjectSkillRepository.class)),
                new SystemTriggerRegistry(mapper), new PublishPlatformRegistry());

        WorkflowValidationResult result = validator.validate("proj-1", definition());

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void creatorsLiveInTheMarketingAreaUnderTheirOwnNoun() throws Exception {
        Statechart chart = Statechart.parse(definition());

        assertThat(chart.slug()).isEqualTo("CREATORS");
        assertThat(chart.area()).isEqualTo("MARKETING");
        assertThat(chart.noun()).isEqualTo("Creator");
        assertThat(chart.types()).containsExactly("CREATOR");
        assertThat(chart.version()).isEqualTo(1);
        assertThat(chart.state()).isEqualTo("PUBLISHED");
    }

    @Test
    void aCreatorMovesFromProspectToBriefedToPostedToPaidOut() throws Exception {
        Statechart chart = Statechart.parse(definition());

        assertThat(chart.initialStatus().orElseThrow().id()).isEqualTo("PROSPECT");
        assertThat(chart.transition("PROSPECT", "BRIEFED")).isPresent();
        assertThat(chart.transition("BRIEFED", "POSTED")).isPresent();
        assertThat(chart.transition("POSTED", "PAID_OUT")).isPresent();
        assertThat(chart.transition("PROSPECT", "POSTED")).isEmpty();
        assertThat(chart.transition("BRIEFED", "PAID_OUT")).isEmpty();
        assertThat(chart.isTerminal("PAID_OUT")).isTrue();
    }

    @Test
    void aCreatorCanBeDroppedFromAnyOpenStatusAndNothingLeavesATerminalOne() throws Exception {
        Statechart chart = Statechart.parse(definition());

        assertThat(chart.isTerminal("DROPPED")).isTrue();
        for (String open : new String[] {"PROSPECT", "BRIEFED", "POSTED"}) {
            assertThat(chart.transition(open, "DROPPED")).as("drop from " + open).isPresent();
        }
        assertThat(chart.transitions()).noneMatch(t -> t.from().equals("PAID_OUT") || t.from().equals("DROPPED"));
    }

    @Test
    void noTransitionNeedsAReview() throws Exception {
        assertThat(Statechart.parse(definition()).transitions()).noneMatch(t -> t.requiresReview());
    }

    @Test
    void declaresTheCreatorFieldsWithTheHandleRequiredFromBriefed() throws Exception {
        Statechart chart = Statechart.parse(definition());

        assertThat(chart.fields()).extracting(StatechartField::key)
                .containsExactly("handle", "platform", "offerCode", "fee", "linkToken", "briefUrl", "notes");
        StatechartField handle = chart.fields().get(0);
        assertThat(handle.type()).isEqualTo("text");
        assertThat(handle.requiredIn()).containsExactly("BRIEFED", "POSTED", "PAID_OUT");
        assertThat(chart.fields().get(1).options()).containsExactly("tiktok", "instagram", "youtube", "other");
        assertThat(chart.fields().get(3).type()).isEqualTo("number");
        assertThat(chart.fields().get(5).type()).isEqualTo("url");
        assertThat(chart.dueLabel()).isEqualTo("Post by");
    }
}
