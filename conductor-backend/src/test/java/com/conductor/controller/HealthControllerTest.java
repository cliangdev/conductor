package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean
    private RunTokenService runTokenService;

    @Test
    void healthEndpointReturns200WithStatusOkAndIsPublic() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.database").value("ok"));
    }

    @Test
    void healthEndpointStaysUpButReportsDatabaseErrorWhenUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.database").value("error"));
    }
}
