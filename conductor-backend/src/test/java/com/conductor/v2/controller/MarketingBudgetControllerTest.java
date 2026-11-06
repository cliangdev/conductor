package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.budget.BudgetPacingService;
import com.conductor.marketing.budget.BudgetPacingService.StopRuleInput;
import com.conductor.marketing.budget.MarketingBudget;
import com.conductor.marketing.budget.MarketingStopRule;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingBudgetController}: the prefixed paths, membership gating (any member reads,
 * ADMIN or CREATOR writes), status codes, a bad month answering 400, and the response mapping. The pacing numbers
 * are proven in {@code BudgetPacingServiceIntegrationTest}.
 */
@WebMvcTest(MarketingBudgetController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingBudgetControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing";
    private static final String AUTH = "Bearer valid-token";
    private static final String RULE_BODY = """
            {"channel":"ADS","metric":"cost_per_install","comparator":"GT","threshold":10,"minSample":20,
             "scope":"keyword:7","note":"Pause it"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BudgetPacingService service;
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

    private static BudgetPacingService.Pacing pacing() {
        var rule = new BudgetPacingService.RuleEvaluation("r1", "ADS", "channel", "cost_per_install", "GT",
                BigDecimal.TEN, 20, 7, null, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15), 20.0, 25.0, true,
                false, null);
        var window = new BudgetPacingService.CpiWindow(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15), 70.0,
                0.0, null);
        var day = new BudgetPacingService.DayPoint(LocalDate.of(2026, 11, 1), 5.0, null, 5.0, 5.33);
        var channel = new BudgetPacingService.ChannelPacing("ADS", LocalDate.of(2026, 11, 15), 80.0, 14.0, null, null,
                false, "no stop rule", List.of(day), List.of(rule),
                new BudgetPacingService.MarginalCpi(window, window, window));
        return new BudgetPacingService.Pacing("2026-11", null, null, LocalDate.of(2026, 11, 15), 15, 30, 80.0, 14.0,
                null, null, 160.0, List.of(day), List.of(channel));
    }

    // ---- reads ----

    @Test
    void getBudget_passesTheMonthThroughAndMapsNullsAsNulls() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.pacing(eq(PROJECT_ID), eq(YearMonth.of(2026, 11)), any(LocalDate.class))).thenReturn(pacing());

        mockMvc.perform(get(BASE + "/budget").param("month", "2026-11").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.capAmount").doesNotExist())
                .andExpect(jsonPath("$.pacePct").doesNotExist())
                .andExpect(jsonPath("$.mtdSpend").value(80.0))
                .andExpect(jsonPath("$.projectedSpend").value(160.0))
                .andExpect(jsonPath("$.channels[0].channel").value("ADS"))
                .andExpect(jsonPath("$.channels[0].compliant").value(false))
                .andExpect(jsonPath("$.channels[0].complianceReason").value("no stop rule"))
                .andExpect(jsonPath("$.channels[0].rules[0].tripped").value(true))
                .andExpect(jsonPath("$.channels[0].marginalCpi.last7.installs").value(0.0))
                .andExpect(jsonPath("$.channels[0].marginalCpi.last7.cpi").doesNotExist())
                .andExpect(jsonPath("$.daily[0].cumulativeSpend").value(5.0))
                .andExpect(jsonPath("$.daily[0].provisionalSpend").doesNotExist());
    }

    @Test
    void getBudget_withoutAMonth_asksForTheCurrentMonth() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.pacing(eq(PROJECT_ID), any(YearMonth.class), any(LocalDate.class))).thenReturn(pacing());

        mockMvc.perform(get(BASE + "/budget").header("Authorization", AUTH)).andExpect(status().isOk());

        ArgumentCaptor<YearMonth> month = ArgumentCaptor.forClass(YearMonth.class);
        verify(service).pacing(eq(PROJECT_ID), month.capture(), any(LocalDate.class));
        assertThat(month.getValue()).isEqualTo(BudgetPacingService.parseMonth(null));
    }

    @Test
    void getBudget_withAMalformedMonth_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(get(BASE + "/budget").param("month", "2026-13").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(service, never()).pacing(anyString(), any(), any());
    }

    @Test
    void getBudget_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE + "/budget").header("Authorization", AUTH)).andExpect(status().isForbidden());
        verify(service, never()).pacing(anyString(), any(), any());
    }

    @Test
    void listStopRules_returnsTheActiveRules() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.listStopRules(PROJECT_ID)).thenReturn(List.of(rule("rule-1")));

        mockMvc.perform(get(BASE + "/stop-rules").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopRules[0].id").value("rule-1"))
                .andExpect(jsonPath("$.stopRules[0].scope").value("keyword:7"))
                .andExpect(jsonPath("$.stopRules[0].threshold").value(10.0));
    }

    // ---- writes ----

    @Test
    void putBudget_byAReviewer_isForbiddenAndNothingIsStored() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(put(BASE + "/budget/2026-11").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capAmount\":160}").header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(service, never()).setBudget(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void putBudget_byAnEditor_storesTheCapAndReturnsIt() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        MarketingBudget saved = new MarketingBudget();
        saved.setMonth("2026-11");
        saved.setCapAmount(new BigDecimal("160.00"));
        saved.setCurrency("USD");
        saved.setUpdatedAt(OffsetDateTime.parse("2026-11-05T10:00:00Z"));
        when(service.setBudget(eq(PROJECT_ID), eq("2026-11"), any(), any(), any())).thenReturn(saved);

        mockMvc.perform(put(BASE + "/budget/2026-11").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capAmount\":160}").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.capAmount").value(160.0))
                .andExpect(jsonPath("$.currency").value("USD"));
        verify(service).setBudget(eq(PROJECT_ID), eq("2026-11"), eq(BigDecimal.valueOf(160.0)), eq(null), eq("user-1"));
    }

    @Test
    void putBudget_withoutACapAmount_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(put(BASE + "/budget/2026-11").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(service, never()).setBudget(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void postStopRule_byAReviewer_isForbiddenAndNothingIsStored() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(post(BASE + "/stop-rules").contentType(MediaType.APPLICATION_JSON).content(RULE_BODY)
                        .header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(service, never()).addStopRule(anyString(), any(), any());
    }

    @Test
    void postStopRule_byAnEditor_returns201WithTheStoredRule() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(service.addStopRule(eq(PROJECT_ID), any(), eq("user-1"))).thenReturn(rule("rule-9"));

        mockMvc.perform(post(BASE + "/stop-rules").contentType(MediaType.APPLICATION_JSON).content(RULE_BODY)
                        .header("Authorization", AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("rule-9"))
                .andExpect(jsonPath("$.metric").value("cost_per_install"))
                .andExpect(jsonPath("$.note").value("Pause it"));

        ArgumentCaptor<StopRuleInput> input = ArgumentCaptor.forClass(StopRuleInput.class);
        verify(service).addStopRule(eq(PROJECT_ID), input.capture(), eq("user-1"));
        assertThat(input.getValue()).isEqualTo(new StopRuleInput("ADS", "keyword:7", "cost_per_install", "GT",
                BigDecimal.valueOf(10.0), 20, null, "Pause it"));
    }

    @Test
    void postStopRule_withoutAThreshold_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE + "/stop-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"ADS\",\"metric\":\"spend\",\"comparator\":\"GT\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(service, never()).addStopRule(anyString(), any(), any());
    }

    @Test
    void deleteStopRule_byAnEditor_returns204() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(delete(BASE + "/stop-rules/rule-1").header("Authorization", AUTH))
                .andExpect(status().isNoContent());
        verify(service).removeStopRule(PROJECT_ID, "rule-1");
    }

    @Test
    void deleteStopRule_byAReviewer_isForbidden() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(delete(BASE + "/stop-rules/rule-1").header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(service, never()).removeStopRule(anyString(), anyString());
    }

    @Test
    void deleteStopRule_ofAnUnknownRule_returns404() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        doThrow(new EntityNotFoundException("Stop rule not found")).when(service).removeStopRule(PROJECT_ID, "nope");

        mockMvc.perform(delete(BASE + "/stop-rules/nope").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    private static MarketingStopRule rule(String id) {
        MarketingStopRule r = new MarketingStopRule();
        r.setId(id);
        r.setProjectId(PROJECT_ID);
        r.setChannel("ADS");
        r.setScope("keyword:7");
        r.setMetric("cost_per_install");
        r.setComparator("GT");
        r.setThreshold(BigDecimal.TEN);
        r.setMinSample(20);
        r.setWindowDays(7);
        r.setNote("Pause it");
        r.setCreatedAt(OffsetDateTime.parse("2026-11-05T10:00:00Z"));
        return r;
    }
}
