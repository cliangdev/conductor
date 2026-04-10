package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.ApiKeyResponse;
import com.conductor.generated.model.CreateApiKeyResponse;
import com.conductor.generated.model.CreateUserApiKeyResponse;
import com.conductor.generated.model.UserApiKeyResponse;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ApiKeyService;
import com.conductor.service.JwtService;
import com.conductor.service.UserApiKeyService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private UserApiKeyService userApiKeyService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("admin@example.com");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    @Test
    void createApiKeyReturns201WithRawKey() throws Exception {
        CreateApiKeyResponse response = new CreateApiKeyResponse(
                "key-1", "My Key", "ck_abc123secret", OffsetDateTime.now());

        when(apiKeyService.generateApiKey(eq("proj-1"), eq("My Key"), eq(testUser))).thenReturn(response);

        mockMvc.perform(post("/api/v1/projects/proj-1/api-keys")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("key-1"))
                .andExpect(jsonPath("$.name").value("My Key"))
                .andExpect(jsonPath("$.key").value("ck_abc123secret"));
    }

    @Test
    void createApiKeyReturns403ForNonAdmin() throws Exception {
        when(apiKeyService.generateApiKey(eq("proj-1"), any(), eq(testUser)))
                .thenThrow(new ForbiddenException("Caller is not a project admin"));

        mockMvc.perform(post("/api/v1/projects/proj-1/api-keys")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Key\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listApiKeysReturnsKeysWithoutRawKeyValues() throws Exception {
        ApiKeyResponse keyResponse = new ApiKeyResponse("key-1", "My Key", OffsetDateTime.now());

        when(apiKeyService.listApiKeys(eq("proj-1"), eq(testUser))).thenReturn(List.of(keyResponse));

        mockMvc.perform(get("/api/v1/projects/proj-1/api-keys")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("key-1"))
                .andExpect(jsonPath("$[0].name").value("My Key"))
                .andExpect(jsonPath("$[0].key").doesNotExist());
    }

    @Test
    void deleteApiKeyReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/proj-1/api-keys/key-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());

        verify(apiKeyService).revokeApiKey("proj-1", "key-1", testUser);
    }

    @Test
    void deleteApiKeyReturns403ForNonAdmin() throws Exception {
        doThrow(new ForbiddenException("Caller is not a project admin"))
                .when(apiKeyService).revokeApiKey(eq("proj-1"), eq("key-1"), eq(testUser));

        mockMvc.perform(delete("/api/v1/projects/proj-1/api-keys/key-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteApiKeyReturns404WhenNotFound() throws Exception {
        doThrow(new EntityNotFoundException("API key not found"))
                .when(apiKeyService).revokeApiKey(eq("proj-1"), eq("key-1"), eq(testUser));

        mockMvc.perform(delete("/api/v1/projects/proj-1/api-keys/key-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createApiKeyRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/projects/proj-1/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"My Key\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUserApiKeysReturnsMaskedKeys() throws Exception {
        UserApiKeyResponse keyResponse = new UserApiKeyResponse("key-1", "****abcd", OffsetDateTime.now())
                .label("CLI Key");

        when(userApiKeyService.listUserApiKeys(eq(testUser))).thenReturn(List.of(keyResponse));

        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].maskedKey").value("****abcd"))
                .andExpect(jsonPath("$[0].key").doesNotExist());
    }

    @Test
    void createUserApiKeyReturns201WithFullKey() throws Exception {
        CreateUserApiKeyResponse createResponse = new CreateUserApiKeyResponse(
                "key-1", "uk_abc123fullsecret", "****cret", OffsetDateTime.now())
                .label("CLI Key");

        when(userApiKeyService.createUserApiKey(any(), eq(testUser))).thenReturn(createResponse);

        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\": \"CLI Key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("uk_abc123fullsecret"))
                .andExpect(jsonPath("$.maskedKey").value("****cret"));
    }

    @Test
    void deleteUserApiKeyReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/api-keys/key-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());

        verify(userApiKeyService).deleteUserApiKey("key-1", testUser);
    }

    @Test
    void deleteUserApiKeyReturns404WhenNotFound() throws Exception {
        doThrow(new jakarta.persistence.EntityNotFoundException("API key not found"))
                .when(userApiKeyService).deleteUserApiKey(eq("key-1"), eq(testUser));

        mockMvc.perform(delete("/api/v1/api-keys/key-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }
}
