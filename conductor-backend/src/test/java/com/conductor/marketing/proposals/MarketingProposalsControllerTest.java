package com.conductor.marketing.proposals;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.marketing.proposals.MarketingProposalService.Proposal;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.v2.controller.MarketingProposalsController;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingProposalsController}: who may propose (an editor, not a reviewer), what
 * reaches the service, how a refusal is answered (409 for a full week, 422 for unverified evidence, 400 for a bad
 * proposal), and who may install the workflow (an admin, 201 once and 200 after). The rules themselves are in
 * {@code MarketingProposalServiceTest}.
 */
@WebMvcTest(MarketingProposalsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingProposalsControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing";
    private static final String BODY = """
            {"title":"Raise the Search bid","type":"ADS","hypothesis":"A higher bid wins more brand searches.",
             "reviewOn":"2026-11-26","runId":"run-0007",
             "signals":[
               {"metricRef":"ADS.installs","window":"2026-09-29..2026-10-26","value":23,"note":"tap installs"},
               {"metricRef":"STORE.downloads","window":"2026-09-29..2026-10-26","value":30}]}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MarketingProposalService proposals;
    @MockitoBean private WeeklyGrowthWorkflowService installer;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private void memberWithRole(MemberRole role) {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));
    }

    // ---- propose ---------------------------------------------------------------------------------------

    @Test
    void propose_byAnEditor_returns201WithTheItemAndHowManyTheWeekHasHad() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(proposals.propose(eq(PROJECT_ID), any(Proposal.class), any(ProjectActor.class)))
                .thenReturn(new MarketingProposalService.Result("wi-1", "RE-12", 2, 3));

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workItemId").value("wi-1"))
                .andExpect(jsonPath("$.displayId").value("RE-12"))
                .andExpect(jsonPath("$.proposalsThisWeek").value(2))
                .andExpect(jsonPath("$.weeklyLimit").value(3));

        ArgumentCaptor<Proposal> sent = ArgumentCaptor.forClass(Proposal.class);
        ArgumentCaptor<ProjectActor> actor = ArgumentCaptor.forClass(ProjectActor.class);
        verify(proposals).propose(eq(PROJECT_ID), sent.capture(), actor.capture());
        assertThat(actor.getValue().userId()).isEqualTo("user-1");
        Proposal proposal = sent.getValue();
        assertThat(proposal.title()).isEqualTo("Raise the Search bid");
        assertThat(proposal.type()).isEqualTo("ADS");
        assertThat(proposal.reviewOn()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(proposal.runId()).isEqualTo("run-0007");
        assertThat(proposal.signals()).hasSize(2);
        assertThat(proposal.signals().get(0).metricRef()).isEqualTo("ADS.installs");
        assertThat(proposal.signals().get(0).window()).isEqualTo("2026-09-29..2026-10-26");
        assertThat(proposal.signals().get(0).value()).isEqualTo(23.0);
        assertThat(proposal.signals().get(0).note()).isEqualTo("tap installs");
        assertThat(proposal.signals().get(1).note()).isNull();
    }

    @Test
    void propose_byAReviewer_isForbiddenAndNothingIsProposed() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(proposals, never()).propose(anyString(), any(), any());
    }

    @Test
    void propose_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void propose_withoutAValueOnASignal_returns400BeforeTheServiceIsCalled() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace(",\"value\":23", ""))
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
        verify(proposals, never()).propose(anyString(), any(), any());
    }

    @Test
    void propose_withoutSignals_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"type\":\"ADS\",\"hypothesis\":\"h\",\"reviewOn\":\"2026-11-26\"}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void propose_aFullWeek_returns409WithTheReason() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(proposals.propose(anyString(), any(), any()))
                .thenThrow(new ConflictException("3 proposals already made this week"));

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("3 proposals already made this week"));
    }

    @Test
    void propose_unverifiedEvidence_returns422NamingTheMetricAndItsVerdict() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(proposals.propose(anyString(), any(), any()))
                .thenThrow(new UnprocessableEntityException("ADS.installs is UNCHECKED: no second source"));

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("ADS.installs is UNCHECKED: no second source"));
    }

    @Test
    void propose_aProposalTheServiceRejects_returns400WithItsMessage() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(proposals.propose(anyString(), any(), any()))
                .thenThrow(new BusinessException("A proposal needs exactly 2 signals on different metrics; got 1"));

        mockMvc.perform(post(BASE + "/proposals").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A proposal needs exactly 2 signals on different metrics; got 1"));
    }

    // ---- install ---------------------------------------------------------------------------------------

    @Test
    void install_byAnAdmin_returns201WithTheWorkflowDisabled() throws Exception {
        memberWithRole(MemberRole.ADMIN);
        when(installer.install(PROJECT_ID, "user-1"))
                .thenReturn(new WeeklyGrowthWorkflowService.Installed("wf-1", "Weekly growth review", false, true));

        mockMvc.perform(post(BASE + "/weekly-growth/install").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.name").value("Weekly growth review"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void install_aSecondTime_returns200WithTheSameWorkflow() throws Exception {
        memberWithRole(MemberRole.ADMIN);
        when(installer.install(PROJECT_ID, "user-1"))
                .thenReturn(new WeeklyGrowthWorkflowService.Installed("wf-1", "Weekly growth review", true, false));

        mockMvc.perform(post(BASE + "/weekly-growth/install").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void install_byAnEditorOrAReviewer_isForbiddenAndNothingIsInstalled() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        mockMvc.perform(post(BASE + "/weekly-growth/install").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());

        memberWithRole(MemberRole.REVIEWER);
        mockMvc.perform(post(BASE + "/weekly-growth/install").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(installer, never()).install(anyString(), anyString());
    }

    @Test
    void install_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(post(BASE + "/weekly-growth/install").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(installer, never()).install(anyString(), anyString());
    }
}
