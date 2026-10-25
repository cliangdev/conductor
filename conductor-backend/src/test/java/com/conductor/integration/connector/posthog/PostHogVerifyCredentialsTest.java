package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PostHogVerifyCredentialsTest {

    private static final String REFUSED =
            "PostHog refused the key for project 42: use a Personal API key (phx_…) with query:read access to that project";

    private MockRestServiceServer server;
    private PostHogConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new PostHogConnector(restTemplate);
    }

    private static ConnectionContext ctx(String key, Map<String, Object> config) {
        return new ConnectionContext("proj", "posthog", "verify", key, null, null, new HashMap<>(config), null);
    }

    @Test
    void probesTheQueryEndpointOfTheConfiguredProject() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer phx_key"))
                .andExpect(jsonPath("$.query.kind").value("HogQLQuery"))
                .andRespond(withSuccess("{\"results\":[[1]]}", MediaType.APPLICATION_JSON));

        assertThat(connector.verifyCredentials(ctx("phx_key", Map.of("projectId", "42")))).isEmpty();
        server.verify();
    }

    @Test
    void aKeyThatCannotQueryIsRefusedWithAnActionableMessage() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(connector.verifyCredentials(ctx("phx_key", Map.of("projectId", "42")))).contains(REFUSED);
    }

    @Test
    void anUnknownProjectIsReportedAsSuch() {
        server.expect(requestTo("https://eu.posthog.com/api/projects/42/query/"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(connector.verifyCredentials(ctx("k", Map.of("projectId", "42", "host", "eu"))))
                .hasValueSatisfying(m -> assertThat(m).contains("no project 42").contains("region"));
    }

    @Test
    void missingProjectIdOrKeyIsRefusedWithoutACall() {
        assertThat(connector.verifyCredentials(ctx("k", Map.of()))).contains("PostHog Project ID is required");
        assertThat(connector.verifyCredentials(ctx(null, Map.of("projectId", "42"))))
                .contains("PostHog API key is required");
        server.verify();
    }

    @Test
    void aBadHostIsRefusedWithoutACall() {
        assertThat(connector.verifyCredentials(ctx("k", Map.of("projectId", "42", "host", "http://x.com"))))
                .hasValueSatisfying(m -> assertThat(m).contains("PostHog host must be"));
    }

    @Test
    void feedPullExplainsARefusedKey() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> connector.pullDaily(ctx("k", Map.of("projectId", "42")),
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 2)))
                .isInstanceOf(ChannelMetricsSourceException.class)
                .hasMessage(REFUSED);
    }

    @Test
    void hostDefaultsToUsCloudAndAcceptsBareHostnames() {
        assertThat(PostHogSettings.resolveHost(null)).isEqualTo("https://app.posthog.com");
        assertThat(PostHogSettings.resolveHost("  ")).isEqualTo("https://app.posthog.com");
        assertThat(PostHogSettings.resolveHost("US")).isEqualTo("https://us.posthog.com");
        assertThat(PostHogSettings.resolveHost("eu")).isEqualTo("https://eu.posthog.com");
        assertThat(PostHogSettings.resolveHost("app.posthog.com")).isEqualTo("https://app.posthog.com");
        assertThat(PostHogSettings.resolveHost("https://ph.example.com/")).isEqualTo("https://ph.example.com");
        assertThatThrownBy(() -> PostHogSettings.resolveHost("http://ph.example.com"))
                .isInstanceOf(ChannelMetricsSourceException.class);
        assertThatThrownBy(() -> PostHogSettings.resolveHost("https://ph.example.com/x"))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    @Test
    void ipLiteralsAndLocalhostAreRejectedAsHosts() {
        for (String h : new String[] {"https://169.254.169.254", "10.0.0.5", "https://[::1]", "localhost",
                "https://foo.localhost", "https://127.0.0.1:8080", "https://2130706433"}) {
            assertThatThrownBy(() -> PostHogSettings.resolveHost(h))
                    .as(h).isInstanceOf(ChannelMetricsSourceException.class);
        }
    }

    @Test
    void internalSelfHostedHostIsRefusedWithoutARequest() {
        assertThat(connector.verifyCredentials(ctx("k", Map.of("projectId", "42", "host", "https://127.0.0.1"))))
                .hasValueSatisfying(m -> assertThat(m).contains("public hostname"));
        server.verify();
    }

    @Test
    void unreachableHostMessageLeaksNoExceptionText() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(request -> { throw new java.io.IOException("Connection refused: internal-secret:5432"); });

        assertThat(connector.verifyCredentials(ctx("k", Map.of("projectId", "42"))))
                .hasValueSatisfying(m -> assertThat(m)
                        .isEqualTo("Could not reach PostHog at https://app.posthog.com. Check the region or self-hosted URL.")
                        .doesNotContain("internal-secret"));
    }
}
