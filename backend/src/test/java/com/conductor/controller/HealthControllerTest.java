package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.GcpStorageService;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GcpStorageService gcpStorageService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @Test
    void healthEndpointReturns200WithStatusOk() throws Exception {
        when(gcpStorageService.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void healthEndpointIsPublicNoAuthRequired() throws Exception {
        when(gcpStorageService.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk());
    }

    @Test
    void healthEndpointReturnsStorageOkWhenGcsHealthy() throws Exception {
        when(gcpStorageService.isHealthy()).thenReturn(true);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.storage").value("ok"));
    }

    @Test
    void healthEndpointReturnsStorageErrorWhenGcsUnhealthy() throws Exception {
        when(gcpStorageService.isHealthy()).thenReturn(false);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.storage").value("error"));
    }
}
