package com.conductor.service.publish;

import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.service.MediaTargetValidator;
import com.conductor.service.PostScheduleValidator;
import com.conductor.service.PublishOptionsValidator;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishGateEvaluatorTest {

    private PostScheduleValidator schedule;
    private MediaTargetValidator media;
    private PublishOptionsValidator options;
    private PublishGateEvaluator evaluator;
    private Statechart marketing;
    private Statechart engineering;
    private final WorkItem post = new WorkItem();

    @BeforeEach
    void setUp() throws Exception {
        schedule = mock(PostScheduleValidator.class);
        media = mock(MediaTargetValidator.class);
        options = mock(PublishOptionsValidator.class);
        PublishPlatformRegistry registry = new PublishPlatformRegistry();
        evaluator = new PublishGateEvaluator(new PublishingWorkflow(registry, null), schedule, media, options);
        ObjectMapper mapper = new ObjectMapper();
        marketing = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")));
        engineering = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")));
        post.setId("post-1");
        post.setCurrentStatus("IN_REVIEW");
    }

    @Test
    void enforceRunsTheValidatorsInTheOrderTheGateAlwaysHas() {
        evaluator.enforce(post, marketing, "APPROVED");

        InOrder order = inOrder(schedule, media, options);
        order.verify(schedule).validateForTransition(post, marketing, "APPROVED");
        order.verify(media).validateForTransition(post, marketing, "APPROVED");
        order.verify(options).validateForTransition(post, marketing, "APPROVED");
    }

    @Test
    void theFirstRefusingValidatorStopsTheRest() {
        doThrow(new UnprocessableEntityException("Cannot move Post to APPROVED: no fire time is set"))
                .when(schedule).validateForTransition(any(), any(), any());

        assertThatThrownBy(() -> evaluator.enforce(post, marketing, "APPROVED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no fire time is set");
        verify(media, never()).validateForTransition(any(), any(), any());
        verify(options, never()).validateForTransition(any(), any(), any());
    }

    @Test
    void evaluateCollectsEveryFindingAndSplitsBlockersFromWarnings() {
        when(schedule.inspect(post)).thenReturn(List.of(
                PublishFinding.blocker(PostScheduleValidator.NO_FIRE_TIME, "no fire time is set")));
        when(media.inspect(post)).thenReturn(List.of(
                PublishFinding.warning(MediaTargetValidator.MEDIA_ADVISORY, "YouTube will treat this as a Short", "t-1"),
                PublishFinding.blocker(MediaTargetValidator.MEDIA_COMPOSITION, "YouTube takes exactly one video", "t-1")));
        when(options.inspect(post)).thenReturn(List.of(
                PublishFinding.blocker(PublishOptionsValidator.CONSENT_NEVER_GIVEN, "consent first")));

        PublishGateEvaluator.Evaluation evaluation = evaluator.evaluate(post);

        assertThat(evaluation.ready()).isFalse();
        assertThat(evaluation.blockers()).extracting(PublishFinding::code)
                .containsExactly("NO_FIRE_TIME", "MEDIA_COMPOSITION", "CONSENT_NEVER_GIVEN");
        assertThat(evaluation.warnings()).extracting(PublishFinding::message)
                .containsExactly("YouTube will treat this as a Short");
    }

    @Test
    void aCleanPostIsReady() {
        when(schedule.inspect(post)).thenReturn(List.of());
        when(media.inspect(post)).thenReturn(List.of());
        when(options.inspect(post)).thenReturn(List.of());

        PublishGateEvaluator.Evaluation evaluation = evaluator.evaluate(post);

        assertThat(evaluation.ready()).isTrue();
        assertThat(evaluation.blockers()).isEmpty();
        assertThat(evaluation.warnings()).isEmpty();
    }

    @Test
    void appliesToTheReviewGateAndScheduleEntryOfAPublishingChartOnly() {
        assertThat(evaluator.appliesTo(marketing, "IN_REVIEW", "APPROVED")).isTrue();
        assertThat(evaluator.appliesTo(marketing, "APPROVED", "SCHEDULED")).isTrue();
        assertThat(evaluator.appliesTo(marketing, "SCHEDULED", "APPROVED")).isFalse();
        assertThat(evaluator.appliesTo(marketing, "DRAFT", "IN_REVIEW")).isFalse();
        assertThat(evaluator.appliesTo(engineering, "CODE_REVIEW", "DONE")).as("ENGINEERING does not publish").isFalse();
    }
}
