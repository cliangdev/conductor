package com.conductor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit test: the post-processor only reads and writes an {@code Environment}, so it needs no
 * Spring context (see docs/testing-guidelines.md).
 */
class BaseUrlNormalizingEnvironmentPostProcessorTest {

    private static StandardEnvironment environmentWith(Map<String, Object> values) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("test", new HashMap<>(values)));
        new BaseUrlNormalizingEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return env;
    }

    @Test
    @DisplayName("the 2026-09-26 regression: a trailing slash on FRONTEND_URL no longer yields //app links")
    void stripsTheTrailingSlashThatProducedDoubleSlashLinks() {
        StandardEnvironment env = environmentWith(Map.of("FRONTEND_URL", "https://conductor.rexipe.io/"));

        String frontendUrl = env.getProperty("FRONTEND_URL");
        assertThat(frontendUrl).isEqualTo("https://conductor.rexipe.io");
        assertThat(frontendUrl + "/app/projects/p1").isEqualTo("https://conductor.rexipe.io/app/projects/p1");
    }

    @Test
    @DisplayName("a placeholder-backed key sees the cleaned value too")
    void normalizesThePlaceholderSpelling() {
        StandardEnvironment env = environmentWith(Map.of(
                "FRONTEND_URL", "https://conductor.rexipe.io/",
                "frontend.url", "${FRONTEND_URL:http://localhost:3000}"));

        assertThat(env.getProperty("frontend.url")).isEqualTo("https://conductor.rexipe.io");
    }

    @Test
    @DisplayName("backend URLs are covered under both spellings")
    void normalizesBackendUrls() {
        StandardEnvironment env = environmentWith(Map.of(
                "BACKEND_URL", "https://api.example.com/",
                "conductor.backend.url", "https://api.example.com//"));

        assertThat(env.getProperty("BACKEND_URL")).isEqualTo("https://api.example.com");
        assertThat(env.getProperty("conductor.backend.url")).isEqualTo("https://api.example.com");
    }

    @Test
    @DisplayName("whitespace and repeated slashes go, a base path stays")
    void keepsAPathButNotItsTrailingSlash() {
        assertThat(BaseUrlNormalizingEnvironmentPostProcessor.normalize("  https://host/conductor///  "))
                .isEqualTo("https://host/conductor");
        assertThat(BaseUrlNormalizingEnvironmentPostProcessor.normalize("")).isEmpty();
        assertThat(BaseUrlNormalizingEnvironmentPostProcessor.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("clean values are left alone and no property source is added")
    void aCleanEnvironmentIsUntouched() {
        StandardEnvironment env = environmentWith(Map.of(
                "FRONTEND_URL", "https://conductor.rexipe.io",
                "BACKEND_URL", ""));

        assertThat(env.getPropertySources().contains(BaseUrlNormalizingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(env.getProperty("BACKEND_URL")).isEmpty();
    }

    @Test
    @DisplayName("it is registered, so Spring Boot actually runs it at startup")
    void isRegisteredInSpringFactories() throws Exception {
        // Read the file rather than SpringFactoriesLoader.load(): load() instantiates every registered
        // post-processor, and some of Boot's own need constructor arguments this test cannot supply.
        var resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        boolean registered = false;
        while (resources.hasMoreElements()) {
            Properties props = new Properties();
            try (var in = resources.nextElement().openStream()) {
                props.load(in);
            }
            String names = props.getProperty(EnvironmentPostProcessor.class.getName(), "");
            if (names.contains(BaseUrlNormalizingEnvironmentPostProcessor.class.getName())) {
                registered = true;
            }
        }
        assertThat(registered).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {}

    @Test
    @DisplayName("a real SpringApplication startup applies it, including through application.properties")
    void aRealStartupAppliesIt() {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // The test classpath's application.properties shadows main's and has no frontend.url, so pass
        // the same placeholder main declares. It still has to resolve through the assembled environment.
        try (ConfigurableApplicationContext ctx = app.run(
                "--FRONTEND_URL=https://conductor.rexipe.io/",
                "--frontend.url=${FRONTEND_URL:http://localhost:3000}",
                "--spring.main.banner-mode=off")) {
            assertThat(ctx.getEnvironment().getProperty("frontend.url")).isEqualTo("https://conductor.rexipe.io");
            assertThat(ctx.getEnvironment().getProperty("FRONTEND_URL")).isEqualTo("https://conductor.rexipe.io");
        }
    }
}
