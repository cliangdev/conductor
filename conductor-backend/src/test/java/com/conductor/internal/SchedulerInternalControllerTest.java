package com.conductor.internal;

import com.conductor.config.SecurityConfig;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.scheduling.ScheduledJobRunner;
import com.conductor.scheduling.ScheduledJobRunner.RunResult;
import com.conductor.scheduling.SchedulerTokenVerifier;
import com.conductor.scheduling.TickOutcome;
import com.conductor.service.JwtService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SchedulerInternalController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SchedulerInternalControllerTest {

    private static final String URL = "/internal/v1/scheduler/jobs/workflow-schedule-poll";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SchedulerTokenVerifier verifier;
    @MockitoBean private ScheduledJobRunner runner;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @Test
    void missingToken_is401_andNothingRuns() throws Exception {
        when(verifier.verify(null)).thenReturn(Optional.empty());
        mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
        verify(runner, never()).run(any());
    }

    @Test
    void invalidToken_is401_andNothingRuns() throws Exception {
        when(verifier.verify("bad")).thenReturn(Optional.empty());
        mockMvc.perform(post(URL).header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
        verify(runner, never()).run(any());
    }

    @Test
    void unknownJob_is404() throws Exception {
        when(verifier.verify("good")).thenReturn(Optional.of("s@x.com"));
        when(runner.run("workflow-schedule-poll")).thenReturn(new RunResult(TickOutcome.UNKNOWN, 0));
        mockMvc.perform(post(URL).header("Authorization", "Bearer good")).andExpect(status().isNotFound());
    }

    @Test
    void ran_is200WithOutcome() throws Exception {
        when(verifier.verify("good")).thenReturn(Optional.of("s@x.com"));
        when(runner.run("workflow-schedule-poll")).thenReturn(new RunResult(TickOutcome.RAN, 12));
        mockMvc.perform(post(URL).header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").value("workflow-schedule-poll"))
                .andExpect(jsonPath("$.outcome").value("RAN"))
                .andExpect(jsonPath("$.durationMs").value(12));
    }

    @Test
    void skippedLeased_is200() throws Exception {
        when(verifier.verify("good")).thenReturn(Optional.of("s@x.com"));
        when(runner.run("workflow-schedule-poll")).thenReturn(new RunResult(TickOutcome.SKIPPED_LEASED, 1));
        mockMvc.perform(post(URL).header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SKIPPED_LEASED"));
    }

    @Test
    void jobThrows_is500() throws Exception {
        when(verifier.verify("good")).thenReturn(Optional.of("s@x.com"));
        when(runner.run("workflow-schedule-poll")).thenThrow(new IllegalStateException("boom"));
        mockMvc.perform(post(URL).header("Authorization", "Bearer good")).andExpect(status().isInternalServerError());
    }
}
