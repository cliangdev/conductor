package com.conductor.service;

import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.OAuth2Connector;
import com.conductor.service.ConnectorAppCredentialService.CredentialSource;
import com.conductor.service.ConnectorAppCredentialService.ResolvedAppCredentials;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The probe contract: a green report means the provider itself accepted the app id/secret, a red one
 * means the provider rejected them, and anything the probe could not interpret is neither.
 */
class ConnectorAppCredentialVerificationServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String SECRET = "s3cr3t-value-9999";

    private ConnectorAppCredentialService appCredentialService;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ConnectorAppCredentialVerificationService service;

    @BeforeEach
    void setUp() {
        appCredentialService = mock(ConnectorAppCredentialService.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new ConnectorAppCredentialVerificationService(
                appCredentialService, new ObjectMapper(), restTemplate);
    }

    // --- Meta: a real app-token grant, which only a valid app id/secret pair can produce ---

    @Test
    void reportsVerifiedWhenMetaIssuesAnAppAccessToken() {
        StubConnector meta = metaConnector();
        resolves(meta, CredentialSource.DEPLOYMENT, "app-id", SECRET);
        server.expect(requestTo("https://graph.facebook.com/v21.0/oauth/access_token"
                        + "?grant_type=client_credentials&client_id=app-id&client_secret=" + SECRET))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"access_token\":\"app-id|abc\",\"token_type\":\"bearer\"}",
                        MediaType.APPLICATION_JSON));

        var report = service.verify(PROJECT_ID, meta);

        server.verify();
        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.VERIFIED);
        assertThat(report.connectorId()).isEqualTo("meta");
        assertThat(report.checks()).singleElement()
                .satisfies(check -> assertThat(check.status()).isEqualTo(CheckStatus.PASS));
        assertThat(messages(report)).contains("app access token");
    }

    @Test
    void reportsErrorNamingMetasReasonWhenTheAppSecretIsWrong() {
        StubConnector meta = metaConnector();
        resolves(meta, CredentialSource.PROJECT, "app-id", SECRET);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://graph.facebook.com")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Error validating client secret.\","
                                + "\"type\":\"OAuthException\",\"code\":1}}"));

        var report = service.verify(PROJECT_ID, meta);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.ERROR);
        assertThat(report.checks()).allSatisfy(c -> assertThat(c.status()).isEqualTo(CheckStatus.FAIL));
        assertThat(messages(report)).contains("Error validating client secret.");
    }

    @Test
    void reportsCouldNotDetermineRatherThanSuccessWhenTheProviderIsUnreachable() {
        StubConnector meta = metaConnector();
        resolves(meta, CredentialSource.DEPLOYMENT, "app-id", SECRET);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://graph.facebook.com")))
                .andRespond(withException(new IOException("connection reset")));

        var report = service.verify(PROJECT_ID, meta);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.UNKNOWN);
        assertThat(report.checks()).allSatisfy(c -> assertThat(c.status()).isEqualTo(CheckStatus.WARN));
        assertThat(messages(report)).contains("could not");
    }

    @Test
    void reportsCouldNotDetermineWhenMetaReturnsAnUnrecognisedBody() {
        StubConnector meta = metaConnector();
        resolves(meta, CredentialSource.DEPLOYMENT, "app-id", SECRET);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://graph.facebook.com")))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        var report = service.verify(PROJECT_ID, meta);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.UNKNOWN);
    }

    @Test
    void reportsCouldNotDetermineWhenMetaReturnsAServerError() {
        StubConnector meta = metaConnector();
        resolves(meta, CredentialSource.DEPLOYMENT, "app-id", SECRET);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://graph.facebook.com")))
                .andRespond(withServerError());

        var report = service.verify(PROJECT_ID, meta);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.UNKNOWN);
    }

    // --- Google / TikTok: a token request expected to fail, read for WHICH failure came back ---

    @Test
    void reportsVerifiedWhenGoogleRejectsOnlyTheDeliberatelyInvalidGrant() {
        StubConnector google = googleConnector();
        resolves(google, CredentialSource.DEPLOYMENT, "google-client-id", SECRET);
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=google-client-id")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}"));

        var report = service.verify(PROJECT_ID, google);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.VERIFIED);
        // A human reading the green check must be told what was actually proven.
        assertThat(messages(report))
                .contains("invalid_grant")
                .contains("deliberately invalid");
    }

    @Test
    void reportsErrorWhenGoogleRejectsTheClientCredentialsThemselves() {
        StubConnector google = googleConnector();
        resolves(google, CredentialSource.PROJECT, "google-client-id", SECRET);
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized\"}"));

        var report = service.verify(PROJECT_ID, google);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.ERROR);
        assertThat(messages(report)).contains("invalid_client");
    }

    @Test
    void reportsCouldNotDetermineForAnErrorCodeThatProvesNeither() {
        StubConnector google = googleConnector();
        resolves(google, CredentialSource.DEPLOYMENT, "google-client-id", SECRET);
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unsupported_grant_type\"}"));

        var report = service.verify(PROJECT_ID, google);

        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.UNKNOWN);
        assertThat(messages(report)).contains("unsupported_grant_type");
    }

    @Test
    void sendsTikTokTheClientIdUnderTheNameTikTokExpects() {
        StubConnector tiktok = tiktokConnector();
        resolves(tiktok, CredentialSource.PROJECT, "tiktok-key", SECRET);
        server.expect(requestTo("https://open.tiktokapis.com/v2/oauth/token/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_key=tiktok-key")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client_id="))))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token is invalid\","
                                + "\"log_id\":\"x\"}"));

        var report = service.verify(PROJECT_ID, tiktok);

        server.verify();
        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.VERIFIED);
    }

    // --- Nothing configured at all, and the never-echo-the-secret guarantee ---

    @Test
    void reportsErrorNamingTheMissingEnvVarsWhenNothingIsConfigured() {
        StubConnector meta = metaConnector();
        when(appCredentialService.resolve(eq(PROJECT_ID), any())).thenReturn(
                new ResolvedAppCredentials("meta", CredentialSource.NONE, null, null,
                        List.of("META_APP_ID", "META_APP_SECRET")));

        var report = service.verify(PROJECT_ID, meta);

        server.verify();
        assertThat(report.status()).isEqualTo(ConnectorAppCredentialVerificationService.ReportStatus.ERROR);
        assertThat(messages(report)).contains("META_APP_ID").contains("META_APP_SECRET");
    }

    @Test
    void neverEchoesTheClientSecretEvenWhenTheProviderDoes() {
        StubConnector google = googleConnector();
        resolves(google, CredentialSource.PROJECT, "google-client-id", SECRET);
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"secret " + SECRET
                                + " is not valid\"}"));

        var report = service.verify(PROJECT_ID, google);

        assertThat(messages(report)).doesNotContain(SECRET);
    }

    private void resolves(StubConnector connector, CredentialSource source, String clientId, String secret) {
        when(appCredentialService.resolve(eq(PROJECT_ID), any())).thenReturn(
                new ResolvedAppCredentials(connector.getId(), source, clientId, secret, List.of()));
    }

    private String messages(ConnectorAppCredentialVerificationService.VerificationReport report) {
        return report.checks().stream().map(Check::message).reduce("", (a, b) -> a + "\n" + b);
    }

    private StubConnector metaConnector() {
        return new StubConnector("meta", "Meta", "https://graph.facebook.com/v21.0/oauth/access_token", "client_id");
    }

    private StubConnector googleConnector() {
        return new StubConnector("youtube", "YouTube", "https://oauth2.googleapis.com/token", "client_id");
    }

    private StubConnector tiktokConnector() {
        return new StubConnector("tiktok", "TikTok", "https://open.tiktokapis.com/v2/oauth/token/", "client_key");
    }

    /** Only the OAuth2Connector hooks the probe actually reads. */
    private record StubConnector(String id, String name, String tokenUrl, String clientIdParamName)
            implements OAuth2Connector {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata(id, name, ConnectorCategory.MARKETING, name + " connector", name);
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }

        @Override
        public List<String> oauthScopes() {
            return List.of();
        }

        @Override
        public String tokenUrl() {
            return tokenUrl;
        }

        @Override
        public String clientIdParamName() {
            return clientIdParamName;
        }
    }
}
