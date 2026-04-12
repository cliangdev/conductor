package com.conductor.security;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.User;
import com.conductor.entity.UserApiKey;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    @Mock
    private ProjectApiKeyRepository projectApiKeyRepository;

    @Mock
    private UserApiKeyRepository userApiKeyRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private ApiKeyAuthenticationFilter filter;

    private Project testProject;
    private ProjectApiKey validApiKey;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new ApiKeyAuthenticationFilter(projectApiKeyRepository, userApiKeyRepository);

        testProject = new Project();
        testProject.setId("proj-1");

        validApiKey = new ProjectApiKey();
        validApiKey.setId("key-1");
        validApiKey.setProject(testProject);
        validApiKey.setName("Test Key");
        validApiKey.setKeyValue("my-secret-key");
        validApiKey.setCreatedAt(OffsetDateTime.now());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validApiKeySetsProjectContextAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer my-secret-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("my-secret-key"))
                .thenReturn(Optional.of(validApiKey));
        when(projectApiKeyRepository.save(any())).thenReturn(validApiKey);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(ApiKeyAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo("proj-1");
    }

    @Test
    void validApiKeyUpdatesLastUsedAt() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer my-secret-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("my-secret-key"))
                .thenReturn(Optional.of(validApiKey));
        when(projectApiKeyRepository.save(any(ProjectApiKey.class))).thenReturn(validApiKey);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(validApiKey.getLastUsedAt()).isNotNull();
        verify(projectApiKeyRepository).save(validApiKey);
    }

    @Test
    void revokedProjectApiKeyDoesNotAuthenticate() throws Exception {
        validApiKey.setRevokedAt(OffsetDateTime.now().minusDays(1));
        when(request.getHeader("Authorization")).thenReturn("Bearer my-secret-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("my-secret-key"))
                .thenReturn(Optional.of(validApiKey));
        when(userApiKeyRepository.findByKeyValueWithUser("my-secret-key")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknownApiKeyDoesNotAuthenticate() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer unknown-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("unknown-key")).thenReturn(Optional.empty());
        when(userApiKeyRepository.findByKeyValueWithUser("unknown-key")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validUserApiKeySetsUserAuthentication() throws Exception {
        User testUser = new User();
        testUser.setId("user-1");
        testUser.setEmail("user@example.com");

        UserApiKey userApiKey = new UserApiKey();
        userApiKey.setId("uk-1");
        userApiKey.setUser(testUser);
        userApiKey.setLabel("CLI Key");
        userApiKey.setKeyValue("uk_user-secret-key");
        userApiKey.setCreatedAt(OffsetDateTime.now());

        when(request.getHeader("Authorization")).thenReturn("Bearer uk_user-secret-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("uk_user-secret-key")).thenReturn(Optional.empty());
        when(userApiKeyRepository.findByKeyValueWithUser("uk_user-secret-key"))
                .thenReturn(Optional.of(userApiKey));
        when(userApiKeyRepository.save(any())).thenReturn(userApiKey);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(UserApiKeyAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(testUser);
    }

    @Test
    void noAuthorizationHeaderContinuesChainWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(projectApiKeyRepository, never()).findByKeyValueWithProject(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

}
