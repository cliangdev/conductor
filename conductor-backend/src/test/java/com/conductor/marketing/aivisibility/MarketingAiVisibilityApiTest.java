package com.conductor.marketing.aivisibility;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.AiBrandTerms;
import com.conductor.generated.v2.model.AiQuestionList;
import com.conductor.generated.v2.model.AiVisibility;
import com.conductor.generated.v2.model.AiVisibilityRunResult;
import com.conductor.generated.v2.model.SetAiBrandTermsRequest;
import com.conductor.generated.v2.model.SetAiQuestionsRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingAiVisibilityController;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AI-visibility endpoints through the controller on the real wiring (no provider credential is stored, so no
 * assistant is ever called): who may read, who may write, who may run, and the response shapes.
 */
class MarketingAiVisibilityApiTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingAiVisibilityController controller;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private MarketingAiVisibilityCheckRepository checkRepository;

    private User admin;
    private User editor;
    private User reviewer;
    private User outsider;
    private Project project;

    @BeforeEach
    void setUp() {
        AiVisibilityFixture fixture = new AiVisibilityFixture(userRepository, projectRepository, memberRepository);
        admin = fixture.user();
        editor = fixture.user();
        reviewer = fixture.user();
        outsider = fixture.user();
        project = fixture.project(admin, "Rexipe");
        fixture.member(project, admin, MemberRole.ADMIN);
        fixture.member(project, editor, MemberRole.CREATOR);
        fixture.member(project, reviewer, MemberRole.REVIEWER);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @Test
    void anEditorSetsQuestionsAndEveryMemberReadsThemInTheView() {
        actAs(editor);
        AiQuestionList stored = controller.setAiVisibilityQuestions(project.getId(),
                new SetAiQuestionsRequest(List.of("Best app to save recipes from TikTok?", "Best meal planner app?")))
                .getBody();

        assertThat(stored.getQuestions()).extracting(q -> q.getQuestion())
                .containsExactly("Best app to save recipes from TikTok?", "Best meal planner app?");
        assertThat(stored.getQuestions()).allMatch(q -> q.getActive());

        actAs(reviewer);
        AiVisibility view = controller.getAiVisibility(project.getId(), 8).getBody();
        assertThat(view.getBrandTerms()).containsExactly("Rexipe");
        assertThat(view.getQuestions()).hasSize(2);
        assertThat(view.getQuestions().get(0).getAssistants()).isEmpty();
    }

    @Test
    void theViewReportsEveryProviderWithoutACredentialAsSkippedNeverAsNotNamed() {
        actAs(reviewer);

        AiVisibility view = controller.getAiVisibility(project.getId(), null).getBody();

        assertThat(view.getSkipped()).extracting(s -> s.getAssistant() + ":" + s.getReason())
                .contains("claude:no credential", "openai:no credential");
        assertThat(view.getQuestions()).isEmpty();
    }

    @Test
    void aNonMemberCannotReadAndAReviewerCannotWriteQuestionsOrBrandTerms() {
        actAs(outsider);
        assertThatThrownBy(() -> controller.getAiVisibility(project.getId(), 8)).isInstanceOf(ForbiddenException.class);

        actAs(reviewer);
        assertThatThrownBy(() -> controller.setAiVisibilityQuestions(project.getId(),
                new SetAiQuestionsRequest(List.of("Q?")))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.setAiVisibilityBrandTerms(project.getId(),
                new SetAiBrandTermsRequest(List.of("Rexipe")))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anEditorSetsTheBrandTermsAndTheViewShowsThem() {
        actAs(editor);

        AiBrandTerms stored = controller.setAiVisibilityBrandTerms(project.getId(),
                new SetAiBrandTermsRequest(List.of("Rexipe", "Rexipe app"))).getBody();

        assertThat(stored.getBrandTerms()).containsExactly("Rexipe", "Rexipe app");
        assertThat(controller.getAiVisibility(project.getId(), 8).getBody().getBrandTerms())
                .containsExactly("Rexipe", "Rexipe app");
    }

    @Test
    void invalidInputIsARefusalNotAStoredValue() {
        actAs(editor);

        assertThatThrownBy(() -> controller.setAiVisibilityQuestions(project.getId(),
                new SetAiQuestionsRequest(List.of(" ")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getAiVisibility(project.getId(), 0))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> controller.getAiVisibility(project.getId(), 53))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void onlyAnAdminMayRunTheChecksNow() {
        actAs(editor);
        assertThatThrownBy(() -> controller.runAiVisibilityCheck(project.getId())).isInstanceOf(ForbiddenException.class);
        actAs(reviewer);
        assertThatThrownBy(() -> controller.runAiVisibilityCheck(project.getId())).isInstanceOf(ForbiddenException.class);
        actAs(outsider);
        assertThatThrownBy(() -> controller.runAiVisibilityCheck(project.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRunWithoutCredentialsRecordsNothingAndReportsEveryAssistantAsSkipped() {
        actAs(editor);
        controller.setAiVisibilityQuestions(project.getId(), new SetAiQuestionsRequest(List.of("Best recipe app?")));
        actAs(admin);

        var response = controller.runAiVisibilityCheck(project.getId());
        AiVisibilityRunResult first = response.getBody();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getRecorded()).isZero();
        assertThat(first.getErrors()).isEmpty();
        assertThat(first.getSkipped()).extracting(s -> s.getAssistant() + ":" + s.getReason())
                .contains("claude:no credential", "openai:no credential");
        assertThat(checkRepository.findByProjectIdAndWeekStart(project.getId(), first.getWeekStart())).isEmpty();

        AiVisibilityRunResult second = controller.runAiVisibilityCheck(project.getId()).getBody();
        assertThat(second.getRecorded()).isZero();
        assertThat(second.getAlreadyRecorded()).isZero();
    }
}
