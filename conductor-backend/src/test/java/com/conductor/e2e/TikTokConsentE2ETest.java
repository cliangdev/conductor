package com.conductor.e2e;

import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishConsentEvent;
import com.conductor.entity.PublishLane;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.PublishConsentEventRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The TikTok consent write path, end to end over HTTP on the {@code local} profile: who may consent, how a
 * stale page is caught, and the request flow an agent uses to hand a person a link.
 *
 * <p>The principle under test is that consent is something a <em>person</em> does in their own signed-in
 * browser session. A JWT sent as the {@code Authorization} header is that session; a user API key (an
 * agent), a project key, and a JWT that only arrived as a cookie are not, and each is refused with a 403 —
 * never a 500. The creator's options come from {@code LocalTikTokCreatorInfoSource}, which reads the
 * connection's cached config, so no TikTok is needed.
 */
class TikTokConsentE2ETest extends AbstractE2ETest {

    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired ConnectionRepository connectionRepository;
    @Autowired PostPublishTargetRepository targetRepository;
    @Autowired WorkItemRepository workItemRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PublishConsentEventRepository eventRepository;

    String ownerJwt;
    String otherJwt;
    HttpHeaders owner;
    HttpHeaders other;
    HttpHeaders userApiKey;
    HttpHeaders projectKey;
    String projectId;
    String itemId;
    String connectionId;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var ownerLogin = login("owner-" + suffix + "@consent.test");
        ownerJwt = (String) ownerLogin.get("accessToken");
        owner = bearer(ownerJwt);
        var otherLogin = login("other-" + suffix + "@consent.test");
        otherJwt = (String) otherLogin.get("accessToken");
        other = bearer(otherJwt);

        projectId = createProject(owner, "Consent " + suffix);
        ProjectMember member = new ProjectMember();
        member.setProject(projectRepository.findById(projectId).orElseThrow());
        member.setUser(userRepository.findById((String) ((Map<?, ?>) otherLogin.get("user")).get("id")).orElseThrow());
        member.setRole(MemberRole.CREATOR);
        projectMemberRepository.save(member);

        userApiKey = bearer(createKey("/api/v1/api-keys", owner));
        projectKey = bearer(createKey("/api/v1/projects/" + projectId + "/api-keys", owner));

        var item = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Launch teaser", "type", "PRD", "workflow", "ENGINEERING"), owner),
                Map.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        itemId = (String) item.getBody().get("id");

        Connection connection = new Connection();
        connection.setProjectId(projectId);
        connection.setConnectorId("tiktok");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{\"creatorNickname\":\"Ada\",\"creatorUsername\":\"ada\","
                + "\"privacyLevelOptions\":[\"PUBLIC_TO_EVERYONE\",\"SELF_ONLY\"],\"maxVideoPostDurationSec\":600}");
        connectionId = connectionRepository.save(connection).getId();

        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(workItemRepository.findById(itemId).orElseThrow());
        target.setPlatform("tiktok");
        target.setConnectorId("tiktok");
        target.setConnectionId(connectionId);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PENDING);
        target.setIdempotencyKey("publish:" + itemId + ":tiktok:" + connectionId);
        targetRepository.save(target);
    }

    // --- the request flow ---

    @Test
    void anAgentRequestsConsentAThePersonConfirmsItAndThePollSeesItComplete() {
        var created = rest.exchange(url(requests()), HttpMethod.POST,
                new HttpEntity<>(Map.of(), userApiKey), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = (String) created.getBody().get("id");
        assertThat((String) created.getBody().get("consentUrl"))
                .endsWith("/app/projects/" + projectId + "/marketing/consent?request=" + requestId);
        assertThat((List<?>) created.getBody().get("items")).hasSize(1);
        assertThat(created.getBody().get("complete")).isEqualTo(false);

        assertThat(poll(requestId).get("complete")).isEqualTo(false);
        assertThat(poll(requestId).get("consentUrl")).isEqualTo(created.getBody().get("consentUrl"));

        var page = rest.exchange(url(page(requestId)), HttpMethod.GET, new HttpEntity<>(owner), Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> post = (Map<?, ?>) ((List<?>) page.getBody().get("posts")).get(0);
        assertThat(post.get("verdict")).isEqualTo("NEVER_GIVEN");
        Map<?, ?> creator = (Map<?, ?>) ((List<?>) page.getBody().get("creators")).get(0);
        assertThat(creator.get("nickname")).isEqualTo("Ada");
        assertThat(creator.get("canPost")).isEqualTo(true);

        var read = rest.exchange(url(legacy()), HttpMethod.GET, new HttpEntity<>(owner), Map.class);
        assertThat(read.getBody().get("etag")).isEqualTo(post.get("etag"));

        var consented = put(owner, (String) post.get("etag"), body(requestId, "BULK_PAGE"));
        assertThat(consented.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consented.getBody().get("valid")).isEqualTo(true);
        assertThat(consented.getBody().get("etag")).isNotNull();

        assertThat(poll(requestId).get("complete")).isEqualTo(true);
        assertThat(eventRepository.findAllByWorkItemIdOrderByCreatedAtDesc(itemId))
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(PublishConsentEvent.CONSENTED);
                    assertThat(event.getChannel()).isEqualTo("BULK_PAGE");
                    assertThat(event.getRequestId()).isEqualTo(requestId);
                    assertThat(event.getCredentialType()).isEqualTo("JWT_HEADER");
                    assertThat(event.getDeclarationVersion()).isEqualTo("v1");
                });

        // Once consented, the page shows what was chosen.
        var after = rest.exchange(url(page(requestId)), HttpMethod.GET, new HttpEntity<>(owner), Map.class);
        Map<?, ?> target = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) after.getBody().get("posts")).get(0))
                .get("targets")).get(0);
        assertThat(((Map<?, ?>) target.get("consented")).get("privacyLevel")).isEqualTo("PUBLIC_TO_EVERYONE");
    }

    @Test
    void anotherMemberCannotOpenSomeoneElsesLink() {
        String requestId = createRequest();

        var resp = rest.exchange(url(page(requestId)), HttpMethod.GET, new HttpEntity<>(other), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) resp.getBody().get("detail")).contains("This link was created for");
    }

    @Test
    void anotherMemberCannotCompleteSomeoneElsesRequestEither() {
        String requestId = createRequest();
        String etag = (String) ((Map<?, ?>) ((List<?>) rest.exchange(url(page(null)), HttpMethod.GET,
                new HttpEntity<>(other), Map.class).getBody().get("posts")).get(0)).get("etag");

        var resp = put(other, etag, body(requestId, "BULK_PAGE"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anExpiredRequestIsGoneOnThePageAndOnTheWrite() {
        String requestId = createRequest();
        // expires_at is not updatable through the entity, which is the point: go around it.
        jdbc.update("UPDATE tiktok_consent_request SET expires_at = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1), requestId);

        assertThat(rest.exchange(url(page(requestId)), HttpMethod.GET, new HttpEntity<>(owner), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(put(owner, "\"whatever\"", body(requestId, "BULK_PAGE")).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        // The poll still answers so an agent can tell the person it expired.
        assertThat(poll(requestId).get("expired")).isEqualTo(true);
    }

    @Test
    void aProjectKeyCannotOpenARequest() {
        var resp = rest.exchange(url(requests()), HttpMethod.POST, new HttpEntity<>(Map.of(), projectKey), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- only a person in their own session may write consent ---

    @Test
    void aHeaderJwtConsentsAndAUserApiKeyDoesNot() {
        String etag = currentEtag();

        assertThat(put(userApiKey, etag, body(null, "POST_PAGE")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(legacyPut(userApiKey, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The agent's own key may still read consent state and withdraw it.
        assertThat(rest.exchange(url(legacy()), HttpMethod.GET, new HttpEntity<>(userApiKey), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legacyPut(userApiKey, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(put(owner, etag, body(null, "POST_PAGE")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventRepository.findAllByWorkItemIdOrderByCreatedAtDesc(itemId))
                .extracting(PublishConsentEvent::getAction)
                .contains(PublishConsentEvent.REFUSED_MACHINE_CREDENTIAL, PublishConsentEvent.WITHDRAWN,
                        PublishConsentEvent.CONSENTED);
        assertThat(eventRepository.findAllByWorkItemIdOrderByCreatedAtDesc(itemId))
                .filteredOn(event -> PublishConsentEvent.REFUSED_MACHINE_CREDENTIAL.equals(event.getAction()))
                .extracting(PublishConsentEvent::getCredentialType).containsOnly("USER_API_KEY");
    }

    @Test
    void aCookieOnlyJwtCannotConsentOrOpenThePage() {
        HttpHeaders cookieOnly = new HttpHeaders();
        cookieOnly.setContentType(MediaType.APPLICATION_JSON);
        cookieOnly.add(HttpHeaders.COOKIE, "access_token=" + ownerJwt);

        assertThat(put(cookieOnly, currentEtag(), body(null, "POST_PAGE")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(legacyPut(cookieOnly, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(url(page(null)), HttpMethod.GET, new HttpEntity<>(cookieOnly), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aProjectKeyGetsA403NotA500OnEveryConsentEndpoint() {
        assertThat(put(projectKey, "\"x\"", body(null, "POST_PAGE")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(legacyPut(projectKey, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(url(legacy()), HttpMethod.GET, new HttpEntity<>(projectKey), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(url(page(null)), HttpMethod.GET, new HttpEntity<>(projectKey), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(eventRepository.findAllByWorkItemIdOrderByCreatedAtDesc(itemId))
                .extracting(PublishConsentEvent::getCredentialType).contains("PROJECT_API_KEY");
    }

    // --- stale pages ---

    @Test
    void theWriteNeedsIfMatchAndRefusesAStaleOne() {
        ResponseEntity<Map> missing = put(owner, null, body(null, "POST_PAGE"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<Map> stale = put(owner, "\"0000\"", body(null, "POST_PAGE"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat((String) stale.getBody().get("detail")).contains("changed");
        assertThat(eventRepository.findAllByWorkItemIdOrderByCreatedAtDesc(itemId))
                .extracting(PublishConsentEvent::getAction).contains(PublishConsentEvent.REFUSED_STALE);
    }

    @Test
    void aChangeMadeAfterThePageLoadedMakesTheNextConfirmStale() {
        String etag = currentEtag();
        assertThat(put(owner, etag, body(null, "POST_PAGE")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The first ETag no longer describes the Post: the consent itself changed its options.
        assertThat(put(owner, etag, body(null, "POST_PAGE")).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void aChoiceTheCreatorCannotMakeIsRefused() {
        Map<String, Object> bad = body(null, "POST_PAGE");
        ((Map<String, Object>) ((List<?>) bad.get("targets")).get(0)).put("privacyLevel", "FOLLOWER_OF_CREATOR");

        var resp = put(owner, currentEtag(), bad);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) resp.getBody().get("detail")).contains("not one of this creator's options");
    }

    // --- helpers ---

    private String requests() {
        return "/api/v2/projects/" + projectId + "/tiktok-consent-requests";
    }

    private String page(String requestId) {
        return "/api/v2/projects/" + projectId + "/tiktok-consent" + (requestId == null ? "" : "?request=" + requestId);
    }

    private String legacy() {
        return "/api/v2/projects/" + projectId + "/work-items/" + itemId + "/publish-consent";
    }

    private String createRequest() {
        var created = rest.exchange(url(requests()), HttpMethod.POST, new HttpEntity<>(Map.of(), userApiKey), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private Map<?, ?> poll(String requestId) {
        var resp = rest.exchange(url(requests() + "/" + requestId), HttpMethod.GET, new HttpEntity<>(userApiKey),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String currentEtag() {
        var page = rest.exchange(url(page(null)), HttpMethod.GET, new HttpEntity<>(owner), Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) ((List<?>) page.getBody().get("posts")).get(0)).get("etag");
    }

    private Map<String, Object> body(String requestId, String channel) {
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("connectionId", connectionId);
        choice.put("privacyLevel", "PUBLIC_TO_EVERYONE");
        choice.put("disableComment", true);
        choice.put("disableDuet", true);
        choice.put("disableStitch", true);
        choice.put("brandContentToggle", false);
        choice.put("brandOrganicToggle", false);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("targets", List.of(choice));
        body.put("declarationVersion", "v1");
        body.put("channel", channel);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    private ResponseEntity<Map> put(HttpHeaders headers, String ifMatch, Map<String, Object> body) {
        HttpHeaders withMatch = new HttpHeaders();
        withMatch.putAll(headers);
        if (ifMatch != null) {
            withMatch.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + itemId + "/tiktok-consent"),
                HttpMethod.PUT, new HttpEntity<>(body, withMatch), Map.class);
    }

    private ResponseEntity<String> legacyPut(HttpHeaders headers, boolean consented) {
        return rest.exchange(url(legacy()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("consented", consented), headers), String.class);
    }

    private Map<?, ?> login(String email) {
        var resp = rest.postForEntity(url("/api/v1/auth/local"), Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String createProject(HttpHeaders headers, String name) {
        var resp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "test"), headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private String createKey(String path, HttpHeaders headers) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(Map.of("name", "consent-test"), headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("key");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
