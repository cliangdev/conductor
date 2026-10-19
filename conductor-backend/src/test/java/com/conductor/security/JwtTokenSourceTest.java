package com.conductor.security;

import com.conductor.entity.User;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter records whether a JWT arrived in the {@code Authorization} header or only as the
 * {@code access_token} cookie, because consent writes accept only the former (a browser attaches the
 * cookie to cross-site requests; a script has to choose to send the header).
 */
class JwtTokenSourceTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aHeaderTokenIsMarkedAsFromTheHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good");

        assertThat(authenticate(request)).isTrue();
    }

    @Test
    void aCookieOnlyTokenIsNot() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", "good"));

        assertThat(authenticate(request)).isFalse();
    }

    @Test
    void whenBothArePresentTheHeaderWinsAndIsMarked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good");
        request.setCookies(new Cookie("access_token", "other"));

        assertThat(authenticate(request)).isTrue();
    }

    private boolean authenticate(MockHttpServletRequest request) throws Exception {
        JwtService jwtService = mock(JwtService.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(jwtService.validateToken("good")).thenReturn(true);
        when(jwtService.getUserIdFromToken("good")).thenReturn("user-1");
        User user = new User();
        user.setId("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        new JwtAuthenticationFilter(jwtService, userRepository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        return ((JwtAuthenticationDetails) auth.getDetails()).isFromAuthorizationHeader();
    }
}
