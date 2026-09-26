package com.conductor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CORS origin list. Plain unit test — {@code allowedOrigins()} reads two fields and nothing
 * else, so it needs no Spring context (see docs/testing-guidelines.md).
 */
class SecurityConfigCorsTest {

    private static SecurityConfig configWith(String frontendUrl, String additional) {
        SecurityConfig config = new SecurityConfig(null, null, null, null, null);
        ReflectionTestUtils.setField(config, "frontendUrl", frontendUrl);
        ReflectionTestUtils.setField(config, "additionalCorsOrigins", additional);
        return config;
    }

    @Test
    @DisplayName("with no extra origins configured, only frontend.url is allowed")
    void onlyFrontendUrlByDefault() {
        assertThat(configWith("https://conductor.rexipe.io", "").allowedOrigins())
                .containsExactly("https://conductor.rexipe.io");
    }

    @Test
    @DisplayName("a cutover keeps the old hostname working alongside the new one")
    void unionsAdditionalOrigins() {
        SecurityConfig config = configWith(
                "https://conductor.rexipe.io",
                "https://conductor-frontend-abc123.us-central1.run.app");

        assertThat(config.allowedOrigins()).containsExactly(
                "https://conductor.rexipe.io",
                "https://conductor-frontend-abc123.us-central1.run.app");
    }

    @Test
    @DisplayName("blank entries and stray whitespace in the list are dropped")
    void ignoresBlanksAndWhitespace() {
        SecurityConfig config = configWith(
                " https://conductor.rexipe.io ",
                " https://a.example.com , , https://b.example.com ,");

        assertThat(config.allowedOrigins()).containsExactly(
                "https://conductor.rexipe.io",
                "https://a.example.com",
                "https://b.example.com");
    }

    @Test
    @DisplayName("an extra origin equal to frontend.url is not listed twice")
    void deduplicates() {
        SecurityConfig config = configWith("https://conductor.rexipe.io", "https://conductor.rexipe.io");

        assertThat(config.allowedOrigins()).containsExactly("https://conductor.rexipe.io");
    }

    @Test
    @DisplayName("the allowed list is immutable — nothing can widen CORS after startup")
    void isImmutable() {
        List<String> origins = configWith("https://conductor.rexipe.io", "").allowedOrigins();

        assertThat(origins).isUnmodifiable();
    }
}
