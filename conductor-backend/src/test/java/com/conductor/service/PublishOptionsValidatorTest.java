package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishLane;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.ConnectionRepository;
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
    private PublishOptionsValidator validator;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        connectionRepository = Mockito.mock(ConnectionRepository.class);
        publishConsentService = Mockito.mock(PublishConsentService.class);
        // The consent rule (MKT-1) is its own block of tests below; every options test runs with consent
        // standing so that a rejection there can only be about the options.
        when(publishConsentService.verdict(any())).thenReturn(PublishConsentService.Verdict.VALID);
        validator = new PublishOptionsValidator(postPublishTargetRepository, connectionRepository,
                publishConsentService, new ObjectMapper());
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
                workItem("MARKETING", "APPROVED"), marketing, "SCHEDULED")).doesNotThrowAnyException();
        verifyNoInteractions(postPublishTargetRepository, connectionRepository, publishConsentService);
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

}
