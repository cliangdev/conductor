package com.conductor.integration.connector.local;

import com.conductor.integration.ActionResult;
import com.conductor.integration.Capability;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.OAuth2Connector;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local stand-ins for the three social publishing connectors.
 *
 * <p>Every real social connector is {@code @Profile("!local")}, so on a developer machine the connector
 * catalog held none of them and {@code /integrations/meta/oauth/authorize} answered "Connector not
 * found: meta" — the whole connect → pick account → select targets → approve → schedule walk was
 * unreachable until Meta App Review and the TikTok/YouTube audits clear. These tests pin the three
 * properties that make the stubs a usable stand-in: they register under the <b>same</b> connector ids,
 * they hand back the <b>same</b> non-secret config keys the real connectors write (read reflectively
 * from the real connectors themselves, so a rename there fails here), and they never touch the network.
 */
class LocalSocialConnectorsTest {

    private static final String REAL_META = "com.conductor.integration.connector.meta.MetaConnector";
    private static final String REAL_YOUTUBE = "com.conductor.integration.connector.youtube.YouTubeConnector";
    private static final String REAL_TIKTOK = "com.conductor.integration.connector.tiktok.TikTokConnector";

    private final LocalMetaConnector meta = new LocalMetaConnector();
    private final LocalYouTubeConnector youtube = new LocalYouTubeConnector();
    private final LocalTikTokConnector tiktok = new LocalTikTokConnector();

    // ---- registration ------------------------------------------------------------------------------

    @Test
    void localSocialConnectorsRegisterUnderTheRealConnectorIds() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(meta, youtube, tiktok));
        registry.init();

        assertThat(registry.getAll()).extracting(Connector::getId)
                .containsExactlyInAnyOrder("meta", "youtube", "tiktok");

        for (String id : List.of("meta", "youtube", "tiktok")) {
            assertThat(registry.findOAuth2(id)).as("%s is an OAuth2 connector", id).isPresent();
            assertThat(registry.findAction(id)).as("%s is an action connector", id).isPresent();
            assertThat(registry.capabilitiesOf(registry.getById(id).orElseThrow()))
                    .contains(Capability.ACTION);
        }
    }

    @Test
    void localConnectorsAreOnTheLocalProfileAndTheRealOnesAreOnItsComplement() {
        assertThat(profileOf(LocalMetaConnector.class)).containsExactly("local");
        assertThat(profileOf(LocalYouTubeConnector.class)).containsExactly("local");
        assertThat(profileOf(LocalTikTokConnector.class)).containsExactly("local");

        assertThat(profileOf(realClass(REAL_META))).containsExactly("!local");
        assertThat(profileOf(realClass(REAL_YOUTUBE))).containsExactly("!local");
        assertThat(profileOf(realClass(REAL_TIKTOK))).containsExactly("!local");
    }

    @Test
    void localConnectorsMirrorTheRealConnectorsCatalogueEntry() {
        assertThat(meta.getMetadata().id()).isEqualTo("meta");
        assertThat(youtube.getMetadata().id()).isEqualTo("youtube");
        assertThat(tiktok.getMetadata().id()).isEqualTo("tiktok");

        // Not singleInstance: a project holds one connection per Page / channel / creator.
        assertThat(meta.getSpec().singleInstance()).isFalse();
        assertThat(youtube.getSpec().singleInstance()).isFalse();
        assertThat(tiktok.getSpec().singleInstance()).isFalse();

        // The tool spec is loaded by connector id, so the stubs expose the real actions unchanged.
        assertThat(meta.getActions()).extracting(a -> a.id())
                .contains("publish_facebook_post", "delete_facebook_post", "get_facebook_post",
                        "publish_instagram_media");
        assertThat(youtube.getActions()).extracting(a -> a.id())
                .contains("publish_video", "unpublish_video", "get_video_status");
        assertThat(tiktok.getActions()).extracting(a -> a.id()).contains("publish_video");
    }

    // ---- Meta account selection --------------------------------------------------------------------

    @Test
    void localMetaRequiresAccountSelectionAndOffersAPageWithAndWithoutInstagram() {
        assertThat(meta.requiresAccountSelection()).isTrue();

        List<OAuth2Connector.OAuthAccount> accounts = meta.listAuthorizableAccounts("any-local-token");
        assertThat(accounts).hasSizeGreaterThanOrEqualTo(2);

        List<Map<String, String>> pages = meta.listAvailablePages("any-local-token");
        String igKey = realConstant(REAL_META, "CONFIG_IG_ACCOUNT_ID");
        assertThat(pages).anyMatch(page -> page.containsKey(igKey))
                .anyMatch(page -> !page.containsKey(igKey));

        // Every offered Page is pickable, and no Page access token leaks into the picker.
        assertThat(accounts).extracting(OAuth2Connector.OAuthAccount::id)
                .containsExactlyInAnyOrderElementsOf(
                        pages.stream().map(p -> p.get(realConstant(REAL_META, "CONFIG_PAGE_ID"))).toList());
        assertThat(pages).allSatisfy(page ->
                assertThat(page.keySet()).doesNotContain("accessToken", "pageAccessToken"));
    }

    @Test
    void localMetaCompletionCarriesTheConfigKeysTheRealMetaConnectorWrites() {
        String pageIdKey = realConstant(REAL_META, "CONFIG_PAGE_ID");
        String pageNameKey = realConstant(REAL_META, "CONFIG_PAGE_NAME");
        String igIdKey = realConstant(REAL_META, "CONFIG_IG_ACCOUNT_ID");
        String igUsernameKey = realConstant(REAL_META, "CONFIG_IG_USERNAME");

        String linkedPageId = meta.listAvailablePages("t").stream()
                .filter(p -> p.containsKey(igIdKey))
                .map(p -> p.get(pageIdKey))
                .findFirst().orElseThrow();

        OAuth2Connector.OAuthCompletion completion = meta.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-user-token", null, linkedPageId));

        assertThat(completion.config())
                .containsKeys(pageIdKey, pageNameKey, igIdKey, igUsernameKey)
                .containsEntry(pageIdKey, linkedPageId);
        assertThat(completion.label()).isEqualTo(completion.config().get(pageNameKey));
        // The credential the connection publishes with is the Page token, not the user token.
        assertThat(completion.accessToken()).isNotBlank().isNotEqualTo("local-user-token");
    }

    @Test
    void localMetaCompletionOmitsInstagramForAPageWithNoLinkedAccount() {
        String pageIdKey = realConstant(REAL_META, "CONFIG_PAGE_ID");
        String igIdKey = realConstant(REAL_META, "CONFIG_IG_ACCOUNT_ID");

        String unlinkedPageId = meta.listAvailablePages("t").stream()
                .filter(p -> !p.containsKey(igIdKey))
                .map(p -> p.get(pageIdKey))
                .findFirst().orElseThrow();

        OAuth2Connector.OAuthCompletion completion = meta.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-user-token", null, unlinkedPageId));

        assertThat(completion.config()).containsKey(pageIdKey)
                .doesNotContainKey(igIdKey)
                .doesNotContainKey(realConstant(REAL_META, "CONFIG_IG_USERNAME"));
    }

    @Test
    void localMetaRejectsAPageTheLocalAccountDoesNotAdminister() {
        assertThatThrownBy(() -> meta.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-user-token", null, "not-a-local-page")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-a-local-page");
    }

    // ---- YouTube / TikTok completion ---------------------------------------------------------------

    @Test
    void localYouTubeCompletionCarriesTheChannelKeysTheRealConnectorWrites() {
        OAuth2Connector.OAuthCompletion completion = youtube.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-google-token", "local-refresh", null));

        String channelIdKey = realConstant(REAL_YOUTUBE, "CONFIG_CHANNEL_ID");
        String channelTitleKey = realConstant(REAL_YOUTUBE, "CONFIG_CHANNEL_TITLE");
        assertThat(completion.config()).containsKeys(channelIdKey, channelTitleKey);
        assertThat(completion.config().get(channelIdKey)).asString().isNotBlank();
        assertThat(completion.label()).isEqualTo(completion.config().get(channelTitleKey));
        // The refresh token an upload weeks later depends on is passed straight back through.
        assertThat(completion.refreshToken()).isEqualTo("local-refresh");
        assertThat(youtube.requiresAccountSelection()).isFalse();
    }

    @Test
    void localTikTokCompletionCarriesTheCreatorKeysTheRealConnectorWrites() {
        OAuth2Connector.OAuthCompletion completion = tiktok.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-tiktok-token", null, null));

        String nicknameKey = realConstant(REAL_TIKTOK, "CONFIG_CREATOR_NICKNAME");
        String usernameKey = realConstant(REAL_TIKTOK, "CONFIG_CREATOR_USERNAME");
        String privacyKey = realConstant(REAL_TIKTOK, "CONFIG_PRIVACY_LEVEL_OPTIONS");

        assertThat(completion.config()).containsKeys(nicknameKey, usernameKey, privacyKey);
        assertThat(completion.config().get(privacyKey)).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).isNotEmpty();
        assertThat(completion.label()).isEqualTo(completion.config().get(nicknameKey));
        assertThat(tiktok.requiresAccountSelection()).isFalse();
        // TikTok's two RFC 6749 deviations are part of the seam being exercised locally.
        assertThat(tiktok.clientIdParamName()).isEqualTo("client_key");
        assertThat(tiktok.scopeDelimiter()).isEqualTo(",");
    }

    @Test
    void localTikTokExposesAMaxVideoDurationTheMediaValidatorCanActOn() {
        String durationKey = realConstant(REAL_TIKTOK, "CONFIG_MAX_VIDEO_DURATION_SEC");
        // The validator reads the very same key off the connection's config_json.
        assertThat(durationKey).isEqualTo(
                realConstant("com.conductor.service.MediaTargetValidator", "CONFIG_MAX_VIDEO_DURATION_SEC"));

        Object cap = tiktok.completeAuthorization(
                new OAuth2Connector.OAuthCompletionRequest("local-tiktok-token", null, null))
                .config().get(durationKey);

        assertThat(cap).isInstanceOf(Integer.class);
        // Low enough that an ordinary test clip passes and a longer one trips the duration branch.
        assertThat((Integer) cap).isEqualTo(180);
    }

    // ---- publishing --------------------------------------------------------------------------------

    @Test
    void localFacebookPublishReturnsACannedIdAndAClearlyLocalPermalink() {
        ActionResult result = meta.invoke("publish_facebook_post",
                Map.of("message", "hello from the laptop"), metaContext());

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("post_id")).asString().isNotBlank();
        assertThat(result.output().get("permalink")).asString()
                .startsWith(LocalMetaConnector.LOCAL_PERMALINK_BASE);
        assertThat(result.output()).containsEntry("scheduled", false);

        // ...and the confirmation poller's read-back sees it live, so the walk reaches Published.
        ActionResult readBack = meta.invoke("get_facebook_post",
                Map.of("post_id", result.output().get("post_id")), metaContext());
        assertThat(readBack.success()).isTrue();
        assertThat(readBack.output()).containsEntry("is_published", true);
    }

    @Test
    void localFacebookHoldsAScheduledPostUntilItsFireTime() {
        String future = Instant.now().plusSeconds(3600).toString();
        ActionResult scheduled = meta.invoke("publish_facebook_post",
                Map.of("message", "later", "scheduled_publish_time", future), metaContext());

        assertThat(scheduled.output()).containsEntry("scheduled", true);
        ActionResult readBack = meta.invoke("get_facebook_post",
                Map.of("post_id", scheduled.output().get("post_id")), metaContext());
        assertThat(readBack.output()).containsEntry("is_published", false);
    }

    @Test
    void localInstagramPublishReturnsACannedMediaIdAndPermalink() {
        ActionResult result = meta.invoke("publish_instagram_media",
                Map.of("caption", "local reel"), metaContext());

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("media_id")).asString().isNotBlank();
        assertThat(result.output().get("creation_id")).asString().isNotBlank();
        assertThat(result.output().get("permalink")).asString()
                .startsWith(LocalMetaConnector.LOCAL_PERMALINK_BASE);
    }

    @Test
    void localYouTubePublishReturnsACannedVideoIdAndGoesPublicAtItsPublishAt() {
        ActionResult published = youtube.invoke("publish_video",
                Map.of("title", "local upload", "privacy_status", "private",
                        "publish_at", Instant.now().minusSeconds(60).toString()),
                youtubeContext());

        assertThat(published.success()).isTrue();
        String videoId = (String) published.output().get("video_id");
        assertThat(videoId).isNotBlank();
        assertThat(published.output().get("permalink")).asString()
                .startsWith(LocalYouTubeConnector.LOCAL_PERMALINK_BASE);

        ActionResult status = youtube.invoke("get_video_status", Map.of("video_id", videoId), youtubeContext());
        assertThat(status.output()).containsEntry("privacy_status", "public")
                .containsEntry("is_public", true);

        ActionResult revoked = youtube.invoke("unpublish_video", Map.of("video_id", videoId), youtubeContext());
        assertThat(revoked.output()).containsEntry("privacy_status", "private")
                .containsEntry("is_public", false);
        assertThat(youtube.invoke("get_video_status", Map.of("video_id", videoId), youtubeContext())
                .output()).containsEntry("is_public", false);
    }

    @Test
    void localTikTokPublishReturnsACannedPostIdAndPermalink() {
        ActionResult result = tiktok.invoke("publish_video",
                Map.of("title", "local clip", "work_item_id", "wi-1"), tiktokContext());

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("publish_id")).asString().isNotBlank();
        assertThat(result.output().get("post_id")).asString().isNotBlank();
        assertThat(result.output().get("permalink")).asString()
                .startsWith(LocalTikTokConnector.LOCAL_PERMALINK_BASE);
    }

    @Test
    void anUnknownActionIsStillAPermanentError() {
        assertThat(meta.invoke("nope", Map.of(), metaContext()).success()).isFalse();
        assertThat(youtube.invoke("nope", Map.of(), youtubeContext()).success()).isFalse();
        assertThat(tiktok.invoke("nope", Map.of(), tiktokContext()).success()).isFalse();
    }

    @Test
    void aLocalPublishSucceedsEvenWithoutAStoredToken() {
        ConnectionContext tokenless = new ConnectionContext("p", "youtube", "c", null, null, null,
                Map.of(), null);
        assertThat(youtube.invoke("publish_video", Map.of("title", "t"), tokenless).success()).isTrue();
    }

    // ---- no network --------------------------------------------------------------------------------

    /**
     * Structural, not behavioural: a stub that quietly grew an HTTP call would still pass every test
     * above on a machine with a network. The compiled class's constant pool names every type it
     * references, so an absent HTTP client there means the connector cannot make a call at all.
     */
    @Test
    void noLocalSocialConnectorReferencesAnHttpClient() {
        for (Class<?> type : List.of(LocalMetaConnector.class, LocalYouTubeConnector.class,
                LocalTikTokConnector.class)) {
            String constantPool = new String(classBytes(type), StandardCharsets.ISO_8859_1);
            assertThat(constantPool).as("%s references no HTTP client", type.getSimpleName())
                    .doesNotContain("org/springframework/web/client")
                    .doesNotContain("RestTemplate")
                    .doesNotContain("java/net/")
                    .doesNotContain("okhttp")
                    .doesNotContain("org/apache/http")
                    .doesNotContain("HttpURLConnection")
                    .doesNotContain("MetaGraphClient")
                    .doesNotContain("YouTubeDataClient")
                    .doesNotContain("TikTokClient");
        }
    }

    @Test
    void noLocalSocialConnectorHoldsACollaboratorThatCouldCall() {
        for (Object connector : List.of(meta, youtube, tiktok)) {
            for (Field field : connector.getClass().getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("%s.%s", connector.getClass().getSimpleName(), field.getName())
                        .doesNotStartWith("org.springframework.web")
                        .doesNotStartWith("java.net");
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private ConnectionContext metaContext() {
        return new ConnectionContext("p", "meta", "conn-meta", "local-page-token", null, null,
                Map.of("pageId", "local-page-roasters", "pageName", "Local Coffee Roasters",
                        "instagramBusinessAccountId", "local-ig-roasters",
                        "instagramUsername", "localcoffeeroasters"),
                null);
    }

    private ConnectionContext youtubeContext() {
        return new ConnectionContext("p", "youtube", "conn-yt", "local-token", null, null,
                Map.of("channelId", "UC-local-conductor-dev", "channelTitle", "Local Dev Channel"), null);
    }

    private ConnectionContext tiktokContext() {
        return new ConnectionContext("p", "tiktok", "conn-tt", "local-token", null, null,
                Map.of("creatorNickname", "Local Creator", "creatorUsername", "local.creator"), null);
    }

    private static String[] profileOf(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertThat(profile).as("%s carries @Profile", type.getName()).isNotNull();
        return profile.value();
    }

    private static Class<?> realClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Real connector " + name + " is missing", e);
        }
    }

    /**
     * Reads a real connector's own {@code CONFIG_*} constant. The constants are package-private, so the
     * assertions below cannot import them — but reading them here means a rename in the real connector
     * fails this test instead of silently breaking target derivation on the local profile.
     */
    private static String realConstant(String className, String fieldName) {
        try {
            Field field = realClass(className).getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Missing constant " + className + "." + fieldName, e);
        }
    }

    private static byte[] classBytes(Class<?> type) {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(path)) {
            assertThat(in).as("class file for %s", type.getName()).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("Could not read " + path, e);
        }
    }
}
