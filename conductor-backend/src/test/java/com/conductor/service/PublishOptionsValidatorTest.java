package com.conductor.service;

import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PublishLane;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TIK-1 — the per-target publish-option gate, against the REAL seeded statecharts.
 *
 * <p>The bug under test is a publish that <em>succeeds</em>: with no privacy level ever supplied, every
 * TikTok post went out {@code SELF_ONLY} and nobody could see it. So the interesting assertions here are
 * about approval being refused, and about the refusal saying enough for a human to fix it in one pass.
 *
 * <p>Pure unit test per {@code docs/testing-guidelines.md}: every decision is made from the target rows
 * plus the connection's cached config, so a Spring context would only slow it down.
 */
class PublishOptionsValidatorTest {

    private static final String WORK_ITEM_ID = "post-1";
    private static final String TIKTOK_CONNECTION = "conn-tiktok";

    private PostPublishTargetRepository postPublishTargetRepository;
    private ConnectionRepository connectionRepository;
    private PublishConsentService publishConsentService;
    private AssetRepository assetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
    private PublishOptionsValidator validator;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        connectionRepository = Mockito.mock(ConnectionRepository.class);
        publishConsentService = Mockito.mock(PublishConsentService.class);
        assetRepository = Mockito.mock(AssetRepository.class);
        targetAssetRepository = Mockito.mock(PostPublishTargetAssetRepository.class);
        // The consent rule (MKT-1) is its own block of tests below; every options test runs with consent
        // standing so that a rejection there can only be about the options.
        when(publishConsentService.verdict(any())).thenReturn(PublishConsentService.Verdict.VALID);
        validator = new PublishOptionsValidator(new PublishPlatformRegistry(), postPublishTargetRepository, connectionRepository,
                publishConsentService, new ObjectMapper(),
                new PublishTargetMediaResolver(assetRepository, targetAssetRepository), assetRepository);
        marketing = statechart("/schema/examples/marketing.workflow.json");
        engineering = statechart("/schema/examples/engineering.workflow.json");
    }

    // --- [auto] Approval is blocked when a TikTok target has no privacy level chosen ---

    @Test
    void blocksATikTokTargetWithNoPrivacyLevelChosenNamingTheCreatorsOwnOptions() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");
        givenTargets(tiktokTarget(null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("@acme")
                .hasMessageContaining("no privacy level chosen")
                .hasMessageContaining("PUBLIC_TO_EVERYONE")
                .hasMessageContaining("MUTUAL_FOLLOW_FRIENDS")
                .hasMessageContaining("SELF_ONLY");
    }

    @Test
    void blocksATikTokTargetWhoseOptionsBagCarriesEverythingButAPrivacyLevel() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenTargets(tiktokTarget("{\"disableComment\":true,\"disableDuet\":true}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no privacy level chosen");
    }

    @Test
    void blocksATikTokTargetWithABlankPrivacyLevel() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"   \"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no privacy level chosen");
    }

    /**
     * The options are cached per creator at connect time, so with none cached there is nothing to offer and
     * nothing to check against. That is still a block, not a pass: the alternative is approving a post whose
     * visibility nobody has established.
     */
    @Test
    void saysHowToRecoverWhenTheCreatorsPrivacyLevelsAreNotCached() {
        givenConnection(TIKTOK_CONNECTION, "{\"creatorNickname\":\"Acme\"}");
        givenTargets(tiktokTarget(null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no privacy level chosen")
                .hasMessageContaining("reconnect the account");
    }

    // --- [auto] Approval is blocked when the chosen level isn't one the creator allows ---

    @Test
    void blocksAPrivacyLevelThisCreatorsAccountDoesNotOffer() {
        givenCreatorPrivacyLevels("FOLLOWER_OF_CREATOR", "SELF_ONLY");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("PUBLIC_TO_EVERYONE")
                .hasMessageContaining("not one this creator's account allows")
                .hasMessageContaining("FOLLOWER_OF_CREATOR");
    }

    @Test
    void acceptsAnAllowedPrivacyLevelWhateverItsCase() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"public_to_everyone\"}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] Approval is blocked when branded content is combined with a private level ---

    @Test
    void blocksBrandedContentOnASelfOnlyPost() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"SELF_ONLY\",\"brandContentToggle\":true}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("branded content")
                .hasMessageContaining("SELF_ONLY")
                .hasMessageContaining("TikTok rejects that combination");
    }

    @Test
    void allowsBrandedContentOnAVisiblePost() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenTargets(tiktokTarget(
                "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\",\"brandContentToggle\":true}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    /** Only the paid-partnership disclosure clashes with a private post; the own-brand one does not. */
    @Test
    void allowsAnOwnBrandDisclosureOnASelfOnlyPost() {
        givenCreatorPrivacyLevels("SELF_ONLY");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"SELF_ONLY\",\"brandOrganicToggle\":true}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] Approval is allowed for a valid combination ---

    @Test
    void allowsAFullyChosenTikTokTarget() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\",\"disableComment\":false,"
                + "\"disableDuet\":false,\"disableStitch\":true,\"brandContentToggle\":false,"
                + "\"brandOrganicToggle\":true}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] MKT-1: a TikTok Post cannot enter a review-gated status without valid consent ---

    @Test
    void blocksATikTokPostTheCreatorHasNeverConsentedTo() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        givenConsent(PublishConsentService.Verdict.NEVER_GIVEN);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("creator's consent")
                .hasMessageContaining("review the preview and the destination account");
    }

    /**
     * The two failure modes need different things from a human: one has never ticked the box, the other is
     * looking at a ticked box that no longer covers the post in front of them.
     */
    @Test
    void blocksATikTokPostWhoseConsentWasWithdrawnByAnEditAndSaysSo() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        givenConsent(PublishConsentService.Verdict.SUPERSEDED);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("given for a different version")
                .hasMessageContaining("consent again");
    }

    @Test
    void allowsATikTokPostOnceConsentHasBeenRecorded() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        givenConsent(PublishConsentService.Verdict.VALID);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    /** Consent is to the post going out, not to each account, so it is one question per Post. */
    @Test
    void asksAboutConsentOncePerPostHoweverManyTikTokAccountsItPostsTo() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenConnection("conn-tiktok-2", "{\"privacyLevelOptions\":[\"PUBLIC_TO_EVERYONE\"]}");
        PostPublishTarget second = tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
        second.setConnectionId("conn-tiktok-2");
        givenTargets(tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"), second);
        givenConsent(PublishConsentService.Verdict.NEVER_GIVEN);

        assertThatThrownBy(this::approve).isInstanceOf(UnprocessableEntityException.class);
        verify(publishConsentService, Mockito.times(1)).verdict(any());
    }

    @Test
    void neverAsksAboutConsentForAPostWithNoTikTokTarget() {
        givenTargets(target("facebook", "conn-meta", "Acme Page", null),
                target("youtube", "conn-yt", "Acme Channel", null));

        assertThatCode(this::approve).doesNotThrowAnyException();
        verifyNoInteractions(publishConsentService);
    }

    @Test
    void reportsAMissingConsentAlongsideAnOptionsProblemInOneMessage() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget(null));
        givenConsent(PublishConsentService.Verdict.NEVER_GIVEN);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no privacy level chosen")
                .hasMessageContaining("creator's consent");
    }

    // --- every problem in one message ---

    @Test
    void reportsEveryProblemAcrossEveryTargetInOneMessage() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE", "SELF_ONLY");
        givenConnection("conn-tiktok-2",
                "{\"privacyLevelOptions\":[\"SELF_ONLY\"],\"creatorNickname\":\"Acme UK\"}");
        PostPublishTarget second = tiktokTarget("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
        second.setConnectionId("conn-tiktok-2");
        second.setPlatformAccountLabel("@acme_uk");
        givenTargets(tiktokTarget(null), second);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("@acme")
                .hasMessageContaining("no privacy level chosen")
                .hasMessageContaining("@acme_uk")
                .hasMessageContaining("not one this creator's account allows");
    }

    @Test
    void namesTheWorkflowNounAndTargetStatusInTheRejection() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget(null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Post")
                .hasMessageContaining("APPROVED");
    }

    /** Reading a corrupt bag as "no options" would silently put the SELF_ONLY default back. */
    @Test
    void blocksAnUnreadableOptionsBagRatherThanTreatingItAsEmpty() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("not json at all"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("cannot be read");
    }

    // --- [auto] A non-TikTok target is unaffected by all of these rules ---

    @Test
    void leavesEveryNonTiktokTargetAlone() {
        givenTargets(target("facebook", "conn-meta", "Acme Page", null),
                target("instagram", "conn-meta", "@acme", null),
                target("youtube", "conn-yt", "Acme Channel", "{\"privacyLevel\":\"nonsense\"}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
        verifyNoInteractions(connectionRepository, publishConsentService);
    }

    @Test
    void checksTheTiktokTargetWithoutBeingDistractedByItsNeighbours() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(target("facebook", "conn-meta", "Acme Page", null), tiktokTarget(null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageNotContaining("Facebook");
    }

    // --- scope ---

    @Test
    void doesNotEvaluateAPostWithNoPublishTargets() {
        givenTargets();

        assertThatCode(this::approve).doesNotThrowAnyException();
        verifyNoInteractions(connectionRepository, publishConsentService);
    }

    // --- [auto] An ENGINEERING work item is untouched — the validator does not even query ---

    @Test
    void leavesTheEngineeringReviewGatedTransitionCompletelyUnaffected() {
        WorkItem issue = workItem("ENGINEERING", "CODE_REVIEW");

        assertThatCode(() -> validator.validateForTransition(issue, engineering, "DONE"))
                .doesNotThrowAnyException();
        verifyNoInteractions(postPublishTargetRepository, connectionRepository, publishConsentService);
    }

    @Test
    void ignoresMarketingTransitionsThatAreNotTheApprovalGate() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "DRAFT"), marketing, "IN_REVIEW")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "IN_REVIEW"), marketing, "CHANGES_REQUESTED")).doesNotThrowAnyException();
        verifyNoInteractions(postPublishTargetRepository, connectionRepository, publishConsentService);
    }

    @Test
    void schedulingIsAGateEdgeToo() {
        WorkItem post = workItem("MARKETING", "APPROVED");
        org.mockito.Mockito.when(postPublishTargetRepository.findAllByWorkItemId(post.getId())).thenReturn(List.of());

        assertThatCode(() -> validator.validateForTransition(post, marketing, "SCHEDULED")).doesNotThrowAnyException();
        org.mockito.Mockito.verify(postPublishTargetRepository).findAllByWorkItemId(post.getId());
    }

    @Test
    void letsAnUnscheduledPostReturnToApprovedWithoutRevalidatingItsOptions() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "SCHEDULED"), marketing, "APPROVED")).doesNotThrowAnyException();
        verifyNoInteractions(postPublishTargetRepository, connectionRepository, publishConsentService);
    }

    @Test
    void persistsNothingWhenValidationFails() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget(null));

        assertThatThrownBy(this::approve).isInstanceOf(UnprocessableEntityException.class);

        verify(postPublishTargetRepository, never()).save(any());
    }

    // --- helpers ---

    private void approve() {
        validator.validateForTransition(workItem("MARKETING", "IN_REVIEW"), marketing, "APPROVED");
    }

    private WorkItem workItem(String workflow, String status) {
        WorkItem item = new WorkItem();
        item.setId(WORK_ITEM_ID);
        Project project = new Project();
        project.setId("proj-1");
        item.setProject(project);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        return item;
    }

    private void givenTargets(PostPublishTarget... targets) {
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(new ArrayList<>(List.of(targets)));
    }

    private PostPublishTarget tiktokTarget(String publishOptions) {
        return target("tiktok", TIKTOK_CONNECTION, "@acme", publishOptions);
    }

    private PostPublishTarget target(String platform, String connectionId, String accountLabel,
                                     String publishOptions) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-" + platform + "-" + connectionId);
        target.setPlatform(platform);
        target.setConnectionId(connectionId);
        target.setPlatformAccountLabel(accountLabel);
        target.setPublishOptions(publishOptions);
        lastTargetRef = target;
        return target;
    }

    private void givenConsent(PublishConsentService.Verdict verdict) {
        when(publishConsentService.verdict(any())).thenReturn(verdict);
    }

    private void givenCreatorPrivacyLevels(String... levels) {
        String json = "{\"privacyLevelOptions\":[\""
                + String.join("\",\"", levels) + "\"],\"creatorNickname\":\"Acme\"}";
        givenConnection(TIKTOK_CONNECTION, json);
    }

    private void givenConnection(String connectionId, String configJson) {
        Connection connection = new Connection();
        connection.setId(connectionId);
        connection.setConfigJson(configJson);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
    }

    private Statechart statechart(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- [auto] The MANUAL lane is exempt, and only the MANUAL lane (MKT-2) ---------------------

    /** A TikTok destination a human posts by hand: no account, no options, no API call. */
    private PostPublishTarget manualTikTokTarget() {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-tiktok-manual");
        target.setPlatform("tiktok");
        target.setPlatformAccountLabel("TikTok (manual)");
        target.setLane(PublishLane.MANUAL);
        return target;
    }

    @Test
    void aManualTikTokDestinationNeedsNoPrivacyLevel() {
        // Every TikTok rule here is about what we would send to the Content Posting API. On this lane we
        // send nothing: the creator picks the privacy level in TikTok's own composer. Demanding one here
        // would make a manual TikTok post impossible to approve for want of an answer nobody uses.
        givenTargets(manualTikTokTarget());

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void aManualTikTokDestinationNeedsNoRecordedConsent() {
        // The consent requirement exists because TikTok wants the creator to see a preview and the
        // destination account before we post on their behalf. On this lane they are inside TikTok, seeing
        // TikTok's own preview, posting as themselves — the outcome the rule exists to produce.
        givenConsent(PublishConsentService.Verdict.NEVER_GIVEN);
        givenTargets(manualTikTokTarget());

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void anApiTikTokTargetAlongsideAManualOneStillNeedsConsentAndAPrivacyLevel() {
        // The exemption must not become a bypass. If a manual target could suppress the rules for the
        // whole Post, adding one would be a way to publish through the API with neither.
        givenConsent(PublishConsentService.Verdict.NEVER_GIVEN);
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(manualTikTokTarget(), tiktokTarget(null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("privacy level")
                .hasMessageContaining("consent");
    }

    @Test
    void aManualTargetIsNotEvenLookedUpAgainstAConnection() {
        // It has none. A lookup here would mean the exemption ran too late to matter.
        givenTargets(manualTikTokTarget());

        approve();

        verifyNoInteractions(connectionRepository, publishConsentService);
    }

    // --- Per-target option types: a declared key with the wrong shape blocks (OPTION_INVALID) ---

    @Test
    void blocksABooleanOptionThatIsNeitherABooleanNorABooleanishString() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel", "{\"notifySubscribers\":\"maybe\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("notifySubscribers")
                .hasMessageContaining("boolean");
    }

    @Test
    void acceptsABooleanOptionAsAStringTrueOrFalse() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel",
                "{\"notifySubscribers\":\"true\",\"madeForKids\":\"FALSE\"}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAnAltTextOverOneThousandCharacters() {
        givenTargets(target("instagram", "conn-meta", "@acme",
                "{\"altText\":\"" + "x".repeat(1001) + "\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("altText")
                .hasMessageContaining("1000");
    }

    @Test
    void allowsAltTextAtExactlyOneThousandCharacters() {
        givenTargets(target("instagram", "conn-meta", "@acme",
                "{\"altText\":\"" + "x".repeat(1000) + "\"}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksCollaboratorsWithMoreThanThreeUsernames() {
        givenTargets(target("instagram", "conn-meta", "@acme",
                "{\"collaborators\":[\"a\",\"b\",\"c\",\"d\"]}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("collaborators")
                .hasMessageContaining("1 to 3");
    }

    @Test
    void blocksAnEmptyCollaboratorsList() {
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"collaborators\":[]}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("collaborators");
    }

    @Test
    void allowsOneToThreeCollaborators() {
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"collaborators\":[\"a\",\"b\",\"c\"]}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAnEmptyPlaylistIdsList() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel", "{\"playlistIds\":[]}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("playlistIds");
    }

    @Test
    void allowsANonEmptyPlaylistIdsList() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel", "{\"playlistIds\":[\"pl-1\",\"pl-2\"]}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksANegativeVideoCoverTimestamp() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"videoCoverTimestampMs\":-1,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("videoCoverTimestampMs")
                .hasMessageContaining("non-negative");
    }

    @Test
    void allowsANonNegativeVideoCoverTimestamp() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"videoCoverTimestampMs\":1500,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        Asset clip = video("clip.mp4");
        givenAssets(clip);
        givenSelection(lastTarget(), clip);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAPhotoCoverIndexAtOrBeyondTheImageCount() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"photoCoverIndex\":2,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        Asset a = image("a.jpg");
        Asset b = image("b.jpg");
        givenAssets(a, b);
        givenSelection(lastTarget(), a, b);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("photoCoverIndex")
                .hasMessageContaining("2 image(s)");
    }

    @Test
    void allowsAPhotoCoverIndexWithinTheImageCount() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"photoCoverIndex\":1,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        Asset a = image("a.jpg");
        Asset b = image("b.jpg");
        givenAssets(a, b);
        givenSelection(lastTarget(), a, b);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksACoverAssetIdThatIsNotAnAssetOnThePost() {
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"coverAssetId\":\"missing\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("coverAssetId")
                .hasMessageContaining("not an asset on this Post");
    }

    @Test
    void blocksAThumbnailAssetIdThatIsNotAnImage() {
        Asset clip = video("clip.mp4");
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(clip));
        when(assetRepository.findByIdAndWorkItemId(clip.getId(), WORK_ITEM_ID)).thenReturn(Optional.of(clip));
        givenTargets(target("youtube", "conn-yt", "Acme Channel", "{\"thumbnailAssetId\":\"" + clip.getId() + "\"}"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("thumbnailAssetId")
                .hasMessageContaining("not an image");
    }

    @Test
    void allowsACoverAssetIdThatIsAnImageOnThePost() {
        Asset cover = image("cover.jpg");
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(cover));
        when(assetRepository.findByIdAndWorkItemId(cover.getId(), WORK_ITEM_ID)).thenReturn(Optional.of(cover));
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"coverAssetId\":\"" + cover.getId() + "\"}"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- A key the platform does not declare is a warning, not a blocker (OPTION_UNKNOWN) ---

    @Test
    void warnsRatherThanBlocksOnAKeyThePlatformDoesNotDeclare() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel", "{\"nonsenseKey\":true}"));

        List<com.conductor.service.publish.PublishFinding> findings =
                validator.inspect(workItem("MARKETING", "IN_REVIEW"));

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("OPTION_UNKNOWN");
            assertThat(f.blocks()).isFalse();
            assertThat(f.message()).contains("nonsenseKey");
        });
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- A well-formed option the rest of the target's content makes moot (OPTION_IGNORED) ---

    @Test
    void warnsWhenCollaboratorsIsSetOnAnInstagramCarousel() {
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"collaborators\":[\"friend\"]}"));
        Asset a = image("a.jpg");
        Asset b = image("b.jpg");
        givenAssets(a, b);
        givenSelection(lastTarget(), a, b);

        List<com.conductor.service.publish.PublishFinding> findings =
                validator.inspect(workItem("MARKETING", "IN_REVIEW"));

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("OPTION_IGNORED");
            assertThat(f.blocks()).isFalse();
            assertThat(f.message()).contains("collaborators").contains("carousel");
        });
    }

    @Test
    void doesNotWarnWhenCollaboratorsIsSetOnASingleInstagramItem() {
        givenTargets(target("instagram", "conn-meta", "@acme", "{\"collaborators\":[\"friend\"]}"));
        Asset a = image("a.jpg");
        givenAssets(a);
        givenSelection(lastTarget(), a);

        List<com.conductor.service.publish.PublishFinding> findings =
                validator.inspect(workItem("MARKETING", "IN_REVIEW"));

        assertThat(findings).noneMatch(f -> "OPTION_IGNORED".equals(f.code()));
    }

    @Test
    void warnsWhenAutoAddMusicIsSetOnATikTokVideoPost() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget("{\"autoAddMusic\":true,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        Asset clip = video("clip.mp4");
        givenAssets(clip);
        givenSelection(lastTarget(), clip);

        List<com.conductor.service.publish.PublishFinding> findings =
                validator.inspect(workItem("MARKETING", "IN_REVIEW"));

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("OPTION_IGNORED");
            assertThat(f.message()).contains("autoAddMusic").contains("video post");
        });
    }

    @Test
    void warnsWhenVideoCoverTimestampIsSetOnATikTokPhotoPost() {
        givenCreatorPrivacyLevels("PUBLIC_TO_EVERYONE");
        givenTargets(tiktokTarget(
                "{\"videoCoverTimestampMs\":500,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        Asset a = image("a.jpg");
        givenAssets(a);
        givenSelection(lastTarget(), a);

        List<com.conductor.service.publish.PublishFinding> findings =
                validator.inspect(workItem("MARKETING", "IN_REVIEW"));

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("OPTION_IGNORED");
            assertThat(f.message()).contains("videoCoverTimestampMs").contains("photo post");
        });
    }

    // --- helpers for the option-type and ignored-option tests ---

    private PostPublishTarget lastTargetRef;

    private PostPublishTarget lastTarget() {
        return lastTargetRef;
    }

    private void givenAssets(Asset... assets) {
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(assets));
    }

    private void givenSelection(PostPublishTarget target, Asset... assets) {
        target.setCustomMedia(true);
        List<PostPublishTargetAsset> rows = new ArrayList<>();
        for (int position = 0; position < assets.length; position++) {
            rows.add(new PostPublishTargetAsset(target.getId(), assets[position].getId(), position));
        }
        when(targetAssetRepository.findAllByTargetIdIn(any())).thenReturn(rows);
    }

    private int assetCounter = 0;

    private Asset image(String label) {
        Asset asset = new Asset();
        asset.setId("asset-" + (++assetCounter));
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(1024L);
        return asset;
    }

    private Asset video(String label) {
        Asset asset = new Asset();
        asset.setId("asset-" + (++assetCounter));
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("video/mp4");
        asset.setSizeBytes(1024L * 1024);
        return asset;
    }
}
