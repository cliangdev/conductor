package com.conductor.marketing.aivisibility;

import com.conductor.marketing.aivisibility.AiVisibilityJob.Summary;
import com.conductor.marketing.aivisibility.AiVisibilityService.RunResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The weekly job: which projects it runs for, that one failing project does not stop the rest, and the off switch. */
class AiVisibilityJobTest {

    private final AiVisibilityService service = mock(AiVisibilityService.class);
    private final MarketingAiQuestionRepository questions = mock(MarketingAiQuestionRepository.class);

    private static RunResult result(int recorded, int skipped, int errors) {
        return new RunResult(LocalDate.of(2026, 11, 2), recorded, 0,
                java.util.Collections.nCopies(skipped, new AiVisibilityService.Skipped("claude", "no credential")),
                java.util.Collections.nCopies(errors, new AiVisibilityService.Failure("openai", "q", "boom")));
    }

    @Test
    void itRunsTheChecksForEveryProjectWithAnActiveQuestion() {
        when(questions.findProjectIdsWithActiveQuestions()).thenReturn(List.of("p1", "p2"));
        when(service.runChecks("p1")).thenReturn(result(4, 1, 0));
        when(service.runChecks("p2")).thenReturn(result(2, 0, 1));

        Summary summary = new AiVisibilityJob(service, questions, true).checkAll();

        assertThat(summary).isEqualTo(new Summary(2, 6, 1, 1, 0));
        verify(service).runChecks("p1");
        verify(service).runChecks("p2");
    }

    @Test
    void oneProjectThatThrowsDoesNotStopTheOthers() {
        when(questions.findProjectIdsWithActiveQuestions()).thenReturn(List.of("p1", "p2"));
        when(service.runChecks("p1")).thenThrow(new IllegalStateException("db down"));
        when(service.runChecks("p2")).thenReturn(result(3, 0, 0));

        Summary summary = new AiVisibilityJob(service, questions, true).checkAll();

        assertThat(summary.failedProjects()).isEqualTo(1);
        assertThat(summary.recorded()).isEqualTo(3);
    }

    @Test
    void whenDisabledItTouchesNothing() {
        new AiVisibilityJob(service, questions, false).run();

        verifyNoInteractions(service, questions);
    }

    @Test
    void whenEnabledRunDoesTheWork() {
        when(questions.findProjectIdsWithActiveQuestions()).thenReturn(List.of("p1"));
        when(service.runChecks("p1")).thenReturn(result(1, 0, 0));

        new AiVisibilityJob(service, questions, true).run();

        verify(service).runChecks("p1");
        verify(service, never()).runChecks("p2");
    }
}
