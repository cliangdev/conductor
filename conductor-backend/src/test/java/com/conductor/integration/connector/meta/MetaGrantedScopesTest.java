package com.conductor.integration.connector.meta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MetaConnector#grantedScopes} reads {@code /me/permissions} with the user token. */
class MetaGrantedScopesTest {

    private static final String URL = "https://graph.facebook.com/v21.0/me/permissions";

    private MockRestServiceServer server;
    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new MetaConnector(new MetaGraphClient(restTemplate));
    }

    @Test
    void keepsGrantedPermissionsAndDropsDeclinedOnes() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"permission":"pages_show_list","status":"granted"},
                          {"permission":"read_insights","status":"declined"},
                          {"permission":"instagram_basic","status":"granted"},
                          {"permission":"business_management","status":"expired"}]}""",
                        MediaType.APPLICATION_JSON));

        Optional<List<String>> granted = connector.grantedScopes("user-token");

        assertThat(granted).contains(List.of("pages_show_list", "instagram_basic"));
        server.verify();
    }

    @Test
    void anErrorFromGraphYieldsEmptyRatherThanThrowing() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(connector.grantedScopes("user-token")).isEmpty();
    }

    @Test
    void aMissingTokenYieldsEmptyWithoutCallingGraph() {
        assertThat(connector.grantedScopes(null)).isEmpty();
        assertThat(connector.grantedScopes(" ")).isEmpty();
        server.verify();
    }
}
