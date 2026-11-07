package com.conductor.marketing.promises;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.promises.MessageTagService.TagInput;
import com.conductor.marketing.promises.PromiseLedgerService.IntentShare;
import com.conductor.marketing.promises.PromiseLedgerService.Ledger;
import com.conductor.marketing.promises.PromiseLedgerService.Promise;
import com.conductor.marketing.promises.PromiseLedgerService.Rate;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.v2.controller.MarketingPromisesController;
import com.conductor.workflow.RunTokenService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingPromisesController}: prefixed paths, membership gating (any member reads,
 * ADMIN or CREATOR writes), status codes and the response mapping, in particular that an absent figure is left out
 * of the JSON (never 0). The numbers are proven in {@link PromiseLedgerServiceIntegrationTest}.
 */
@WebMvcTest(MarketingPromisesController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingPromisesControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String PROMISES = "/api/v2/projects/" + PROJECT_ID + "/marketing/promises";
    private static final String TAGS = "/api/v2/projects/" + PROJECT_ID + "/marketing/message-tags";
    private static final String AUTH = "Bearer valid-token";
    private static final String BODY = """
            {"message":"save-from-tiktok","targetKind":"KEYWORD","targetRef":"42","label":"meal planner"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PromiseLedgerService ledger;
    @MockitoBean private MessageTagService tags;
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

    private static MarketingMessageTag tag() {
        MarketingMessageTag t = new MarketingMessageTag();
        t.setId("tag-1");
        t.setProjectId(PROJECT_ID);
        t.setMessage("save-from-tiktok");
        t.setTargetKind("KEYWORD");
        t.setTargetRef("42");
        t.setLabel("meal planner");
        t.setCreatedAt(OffsetDateTime.parse("2026-11-05T10:00:00Z"));
        return t;
    }

    // ---- promises ----

    @Test
    void promises_mapsTheLedgerAndLeavesAbsentFiguresOut() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        Map<String, Integer> targets = new LinkedHashMap<>();
        targets.put("KEYWORD", 2);
        Promise full = new Promise("save-from-tiktok", targets, 12L, "note", List.of(new IntentShare("plan_meals", 5, 1.0)),
                5L, new Rate(1.0 / 6, 6, 0.03, 0.564, true), new Rate(0.2, 100, 0.13, 0.29, false), true, "outcomes note");
        Promise bare = new Promise("bare", Map.of("POST", 1), null, null, null, null, null, null, false, null);
        when(ledger.promises(PROJECT_ID, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26)))
                .thenReturn(new Ledger(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), List.of(full, bare)));

        mockMvc.perform(get(PROMISES).param("from", "2026-09-29").param("to", "2026-10-26")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-29"))
                .andExpect(jsonPath("$.to").value("2026-10-26"))
                .andExpect(jsonPath("$.promises[0].message").value("save-from-tiktok"))
                .andExpect(jsonPath("$.promises[0].targets.KEYWORD").value(2))
                .andExpect(jsonPath("$.promises[0].installs").value(12))
                .andExpect(jsonPath("$.promises[0].installsNote").value("note"))
                .andExpect(jsonPath("$.promises[0].intentMix[0].pick").value("plan_meals"))
                .andExpect(jsonPath("$.promises[0].intentMix[0].count").value(5))
                .andExpect(jsonPath("$.promises[0].intentMix[0].share").value(1.0))
                .andExpect(jsonPath("$.promises[0].intentN").value(5))
                .andExpect(jsonPath("$.promises[0].trialRate.n").value(6))
                .andExpect(jsonPath("$.promises[0].trialRate.low").value(0.03))
                .andExpect(jsonPath("$.promises[0].trialRate.high").value(0.564))
                .andExpect(jsonPath("$.promises[0].trialRate.smallN").value(true))
                .andExpect(jsonPath("$.promises[0].d7.smallN").value(false))
                .andExpect(jsonPath("$.promises[0].smallN").value(true))
                .andExpect(jsonPath("$.promises[0].outcomesNote").value("outcomes note"))
                .andExpect(jsonPath("$.promises[1].installs").doesNotExist())
                .andExpect(jsonPath("$.promises[1].intentMix").doesNotExist())
                .andExpect(jsonPath("$.promises[1].trialRate").doesNotExist())
                .andExpect(jsonPath("$.promises[1].d7").doesNotExist())
                .andExpect(jsonPath("$.promises[1].smallN").value(false));
    }

    @Test
    void promises_withoutBoundsAsksForTheDefaultWindow() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledger.promises(eq(PROJECT_ID), isNull(), isNull())).thenReturn(
                new Ledger(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 1), List.of()));

        mockMvc.perform(get(PROMISES).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promises").isEmpty());
    }

    @Test
    void promises_withABackwardsWindow_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledger.promises(eq(PROJECT_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new BusinessException("from must be on or before to"));

        mockMvc.perform(get(PROMISES).param("from", "2026-10-26").param("to", "2026-09-29")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void promises_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(PROMISES).header("Authorization", AUTH)).andExpect(status().isForbidden());
        verify(ledger, never()).promises(anyString(), any(), any());
    }

    @Test
    void promises_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get(PROMISES)).andExpect(status().is4xxClientError());
    }

    // ---- tags ----

    @Test
    void listTags_returnsTheTagsToAnyMember() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(tags.list(PROJECT_ID)).thenReturn(List.of(tag()));

        mockMvc.perform(get(TAGS).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].id").value("tag-1"))
                .andExpect(jsonPath("$.tags[0].message").value("save-from-tiktok"))
                .andExpect(jsonPath("$.tags[0].targetKind").value("KEYWORD"))
                .andExpect(jsonPath("$.tags[0].targetRef").value("42"))
                .andExpect(jsonPath("$.tags[0].label").value("meal planner"));
    }

    @Test
    void putTag_byACreator_upsertsAndReturnsTheTag() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(tags.tag(eq(PROJECT_ID), any(TagInput.class), eq("user-1"))).thenReturn(tag());

        mockMvc.perform(put(TAGS).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("tag-1"))
                .andExpect(jsonPath("$.message").value("save-from-tiktok"));

        verify(tags).tag(PROJECT_ID, new TagInput("save-from-tiktok", "KEYWORD", "42", "meal planner"), "user-1");
    }

    @Test
    void putTag_byAReviewer_isForbidden() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(put(TAGS).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(tags, never()).tag(anyString(), any(), any());
    }

    @Test
    void putTag_withoutAMessage_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(put(TAGS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetKind\":\"KEYWORD\",\"targetRef\":\"42\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putTag_withAnUnknownKind_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(tags.tag(eq(PROJECT_ID), any(TagInput.class), any()))
                .thenThrow(new BusinessException("targetKind must be one of CREATIVE, PRODUCT_PAGE, KEYWORD, PAYWALL_HEADLINE, POST"));

        mockMvc.perform(put(TAGS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"targetKind\":\"BILLBOARD\",\"targetRef\":\"1\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putTag_forAPostOfAnotherProject_returns404() throws Exception {
        memberWithRole(MemberRole.ADMIN);
        when(tags.tag(eq(PROJECT_ID), any(TagInput.class), any()))
                .thenThrow(new EntityNotFoundException("Post not found"));

        mockMvc.perform(put(TAGS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"targetKind\":\"POST\",\"targetRef\":\"x\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTag_byACreator_returns204EvenWhenNothingWasTagged() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(tags.untag(PROJECT_ID, "KEYWORD", "42")).thenReturn(false);

        mockMvc.perform(delete(TAGS).param("targetKind", "KEYWORD").param("targetRef", "42")
                        .header("Authorization", AUTH))
                .andExpect(status().isNoContent());
        verify(tags).untag(PROJECT_ID, "KEYWORD", "42");
    }

    @Test
    void deleteTag_byAReviewer_isForbidden() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(delete(TAGS).param("targetKind", "KEYWORD").param("targetRef", "42")
                        .header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(tags, never()).untag(anyString(), anyString(), anyString());
    }
}
