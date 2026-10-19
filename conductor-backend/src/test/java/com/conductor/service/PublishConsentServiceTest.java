package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishConsentEvent;
import com.conductor.entity.PublishLane;
import com.conductor.entity.TikTokConsentRequest;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GoneException;
import com.conductor.exception.PreconditionFailedException;
import com.conductor.exception.PreconditionRequiredException;
import com.conductor.integration.connector.tiktok.TikTokCreatorInfoService;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.TikTokConsentRequestRepository;
import com.conductor.entity.Project;
import com.conductor.entity.PublishConsent;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.PublishConsentRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.conductor.repository.PostPublishTargetAssetRepository;

/**
 * MKT-1 — the posting consent TikTok's audit turns on, as a persisted record of <em>what</em> was
 * consented to rather than a boolean living in a browser tab.
 *
 * <p>The interesting assertions are the ones about consent going away on its own: a creator who agreed to
 * a post going to one account under one privacy level has not agreed to it going somewhere else, and the
 * whole value of storing the subject rather than a flag is that nobody has to remember to clear it.
 *
 * <p>Pure unit test per {@code docs/testing-guidelines.md}: every decision here is made from the target
 * rows, the asset rows and the stored consent, so a Spring context would only slow it down. The consent
 * repository is stubbed as a tiny in-memory store so "record it, then read it back" is a real round trip.
 */
class PublishConsentServiceTest {

    private static final String PROJECT = "project-1";
    private static final String WORK_ITEM = "post-1";

    private PostPublishTargetAssetRepository targetAssetRepository;
    private PublishConsentRepository consentRepository;
    private PostPublishTargetRepository targetRepository;
    private AssetRepository assetRepository;
    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private PublishConsentService service;
    private ConnectionRepository connectionRepository;
    private TikTokConsentRequestRepository requestRepository;
    private TikTokCreatorInfoService creatorInfoService;
    private PublishBundleGuard publishBundleGuard;
    private PublishConsentAudit audit;

    private User creator;
    private WorkItem post;

    /** The single stored consent row, as the unique constraint on (work_item_id) guarantees. */
    private PublishConsent stored;

    @BeforeEach
    void setUp() {
        consentRepository = mock(PublishConsentRepository.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        assetRepository = mock(AssetRepository.class);
        workItemRepository = mock(WorkItemRepository.class);
        projectSecurityService = mock(ProjectSecurityService.class);
        targetAssetRepository = mock(PostPublishTargetAssetRepository.class);
        connectionRepository = mock(ConnectionRepository.class);
        requestRepository = mock(TikTokConsentRequestRepository.class);
        creatorInfoService = mock(TikTokCreatorInfoService.class);
        publishBundleGuard = mock(PublishBundleGuard.class);
        audit = mock(PublishConsentAudit.class);
        service = new PublishConsentService(consentRepository, targetRepository, targetAssetRepository,
                assetRepository, workItemRepository, projectSecurityService, connectionRepository,
                requestRepository, creatorInfoService, publishBundleGuard, audit,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));

        creator = user("user-1", "Ada Creator");
        when(projectSecurityService.isProjectMember(PROJECT, "user-1")).thenReturn(true);
        when(projectSecurityService.requireInteractiveUser(PROJECT)).thenReturn(creator);

        Project project = new Project();
        project.setId(PROJECT);
        post = new WorkItem();
        post.setId(WORK_ITEM);
        post.setProject(project);
        when(workItemRepository.findById(WORK_ITEM)).thenReturn(Optional.of(post));

        when(consentRepository.findByWorkItemId(WORK_ITEM))
                .thenAnswer(invocation -> Optional.ofNullable(stored));
        when(consentRepository.save(any(PublishConsent.class))).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return stored;
        });
        doDelete();

        givenTargets(tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
        givenUploadedAssets("asset-1");
    }

    // --- [auto] Consent is persisted with what was consented to, who and when ---

    @Test
    void recordingConsentPersistsItAndAReadReportsItValid() {
        PublishConsentService.ConsentState recorded = consent(true);

        assertThat(recorded.required()).isTrue();
        assertThat(recorded.valid()).isTrue();
        assertThat(recorded.verdict()).isEqualTo(PublishConsentService.Verdict.VALID);
        assertThat(recorded.consentedByUserId()).isEqualTo("user-1");
        assertThat(recorded.consentedByName()).isEqualTo("Ada Creator");
        assertThat(recorded.consentedAt()).isNotNull();

        assertThat(read().valid()).isTrue();
        assertThat(read().consentedByName()).isEqualTo("Ada Creator");
    }

    @Test
    void storesTheSubjectConsentedToRatherThanABareFlag() {
        consent(true);

        assertThat(stored.getConsentHash()).hasSize(64);
        assertThat(stored.getWorkItem()).isSameAs(post);
        assertThat(stored.getConsentedBy()).isSameAs(creator);
    }

    /**
     * A read has to say more than "no": a creator staring at a ticked box needs to know their consent was
     * withdrawn by an edit, not that they never gave it.
     */
    @Test
    void tellsNeverConsentedApartFromConsentedThenChanged() {
        assertThat(read().verdict()).isEqualTo(PublishConsentService.Verdict.NEVER_GIVEN);
        assertThat(read().consentedAt()).isNull();

        consent(true);
        givenTargets(tiktokTarget("conn-tiktok-2", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThat(read().verdict()).isEqualTo(PublishConsentService.Verdict.SUPERSEDED);
        assertThat(read().consentedAt()).isNotNull();
        assertThat(read().valid()).isFalse();
    }

    // --- [auto] Changing the targets invalidates existing consent ---

    @Test
    void swappingTheDestinationAccountWithdrawsConsent() {
        consent(true);
        assertThat(read().valid()).isTrue();

        givenTargets(tiktokTarget("conn-tiktok-2", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThat(read().valid()).isFalse();
    }

    @Test
    void addingASecondDestinationWithdrawsConsent() {
        consent(true);

        givenTargets(tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"),
                target("facebook", "conn-meta", null));

        assertThat(read().valid()).isFalse();
    }

    /** The review bundle hash does not cover publish options; consent must, because the creator saw them. */
    @Test
    void changingAPrivacyLevelWithdrawsConsent() {
        consent(true);

        givenTargets(tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"SELF_ONLY\"}"));

        assertThat(read().valid()).isFalse();
    }

    @Test
    void reConsentingAfterAChangeMakesItValidAgain() {
        consent(true);
        givenTargets(tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"SELF_ONLY\"}"));
        assertThat(read().valid()).isFalse();

        assertThat(consent(true).valid()).isTrue();
        assertThat(read().valid()).isTrue();
    }

    // --- [auto] Changing the media invalidates existing consent ---

    @Test
    void replacingTheUploadedMediaWithdrawsConsent() {
        consent(true);

        givenUploadedAssets("asset-2");

        assertThat(read().valid()).isFalse();
    }

    @Test
    void removingTheMediaAltogetherWithdrawsConsent() {
        consent(true);

        givenUploadedAssets();

        assertThat(read().valid()).isFalse();
    }

    /** Bytes that are not in the bucket yet were never in the preview the creator looked at. */
    @Test
    void anAssetStillUploadingDoesNotWithdrawConsent() {
        consent(true);

        Asset pending = asset("asset-2");
        pending.setUploadStatus("PENDING");
        when(assetRepository.findAllByWorkItemId(WORK_ITEM))
                .thenReturn(new ArrayList<>(List.of(asset("asset-1"), pending)));

        assertThat(read().valid()).isTrue();
    }

    // --- consent is stable under things that are not changes ---

    @Test
    void reorderingTargetsAndOptionKeysDoesNotWithdrawConsent() {
        givenTargets(tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\",\"disableDuet\":true}"),
                target("facebook", "conn-meta", null));
        consent(true);

        givenTargets(target("facebook", "conn-meta", null),
                tiktokTarget("conn-tiktok", "{\"disableDuet\":true,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThat(read().valid()).isTrue();
    }

    @Test
    void withdrawingConsentDeletesTheRowSoItReadsAsNeverGiven() {
        consent(true);

        PublishConsentService.ConsentState withdrawn = consent(false);

        verify(consentRepository).deleteByWorkItemId(WORK_ITEM);
        assertThat(withdrawn.valid()).isFalse();
        assertThat(withdrawn.verdict()).isEqualTo(PublishConsentService.Verdict.NEVER_GIVEN);
        assertThat(withdrawn.consentedAt()).isNull();
        assertThat(read().verdict()).isEqualTo(PublishConsentService.Verdict.NEVER_GIVEN);
    }

    // --- [auto] A Post with no TikTok target is unaffected ---

    @Test
    void aPostWithNoTikTokTargetNeedsNoConsentAtAll() {
        givenTargets(target("facebook", "conn-meta", null), target("youtube", "conn-yt", null));

        assertThat(read().required()).isFalse();
        assertThat(read().verdict()).isEqualTo(PublishConsentService.Verdict.NOT_REQUIRED);
        assertThat(service.verdict(post)).isEqualTo(PublishConsentService.Verdict.NOT_REQUIRED);
    }

    @Test
    void aPostWithNoPublishTargetsAtAllNeedsNoConsent() {
        givenTargets();

        assertThat(service.verdict(post)).isEqualTo(PublishConsentService.Verdict.NOT_REQUIRED);
    }

    @Test
    void aTikTokTargetAlongsideOthersStillRequiresConsent() {
        givenTargets(target("facebook", "conn-meta", null),
                tiktokTarget("conn-tiktok", "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThat(service.verdict(post)).isEqualTo(PublishConsentService.Verdict.NEVER_GIVEN);
    }

    // --- [auto] A non-member cannot record consent ---

    @Test
    void aNonMemberCannotRecordConsent() {
        User outsider = user("user-2", "Mallory");

        assertThatThrownBy(() -> service.recordConsent(PROJECT, WORK_ITEM, true, outsider))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Project not found");
        verify(consentRepository, never()).save(any());
    }

    @Test
    void aNonMemberCannotReadConsentEither() {
        assertThatThrownBy(() -> service.readConsent(PROJECT, WORK_ITEM, user("user-2", "Mallory")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void consentCannotBeRecordedOnAPostInAnotherProject() {
        when(projectSecurityService.isProjectMember("other-project", "user-1")).thenReturn(true);

        assertThatThrownBy(() -> service.recordConsent("other-project", WORK_ITEM, true, creator))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Work Item not found");
        verify(consentRepository, never()).save(any());
    }

    // --- machine credentials cannot record consent ---

    @Test
    void aMachineCredentialCannotRecordConsentAndTheAttemptIsAudited() {
        when(projectSecurityService.requireInteractiveUser(PROJECT)).thenThrow(new ForbiddenException("no"));

        assertThatThrownBy(() -> consent(true)).isInstanceOf(ForbiddenException.class);

        verify(consentRepository, never()).save(any());
        ArgumentCaptor<PublishConsentAudit.Entry> entry = ArgumentCaptor.forClass(PublishConsentAudit.Entry.class);
        verify(audit).appendRefusal(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(PublishConsentEvent.REFUSED_MACHINE_CREDENTIAL);
        assertThat(entry.getValue().workItemId()).isEqualTo(WORK_ITEM);
    }

    @Test
    void withdrawingConsentStaysOpenToAnyMember() {
        when(projectSecurityService.requireInteractiveUser(PROJECT)).thenThrow(new ForbiddenException("no"));
        consent(false);

        verify(consentRepository).deleteByWorkItemId(WORK_ITEM);
    }

    // --- the atomic consent write ---

    @Test
    void theWriteNeedsAnIfMatchHeader() {
        givenCreator(creatorWith());

        assertThatThrownBy(() -> write(null, choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(PreconditionRequiredException.class);
        verify(consentRepository, never()).save(any());
    }

    @Test
    void aStaleIfMatchIsRefusedAndAudited() {
        givenCreator(creatorWith());

        assertThatThrownBy(() -> write("\"stale\"", choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("changed");

        ArgumentCaptor<PublishConsentAudit.Entry> entry = ArgumentCaptor.forClass(PublishConsentAudit.Entry.class);
        verify(audit).appendRefusal(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(PublishConsentEvent.REFUSED_STALE);
        verify(consentRepository, never()).save(any());
    }

    @Test
    void aCurrentIfMatchRecordsTheChoicesConsentAndAnAuditEvent() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));

        PublishConsentService.RecordedConsent recorded = write(currentEtag(),
                choice("PUBLIC_TO_EVERYONE", true, true, true));

        assertThat(recorded.state().valid()).isTrue();
        assertThat(recorded.etag()).isEqualTo(currentEtag());
        PostPublishTarget target = targetRepository.findAllByWorkItemId(WORK_ITEM).get(0);
        assertThat(target.getPublishOptions()).contains("\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"")
                .contains("\"disableComment\":true").contains("\"brandContentToggle\":false");
        ArgumentCaptor<PublishConsentAudit.Entry> entry = ArgumentCaptor.forClass(PublishConsentAudit.Entry.class);
        verify(audit).append(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(PublishConsentEvent.CONSENTED);
        assertThat(entry.getValue().channel()).isEqualTo("BULK_PAGE");
        assertThat(entry.getValue().declarationVersion()).isEqualTo("v1");
        assertThat(entry.getValue().declarationJson()).contains("PUBLIC_TO_EVERYONE");
    }

    /**
     * Consent normally happens while the Post is under review, which freezes where it publishes. The write
     * must not trip that freeze (publish options are not in the review bundle), but it still takes the
     * existing revert path when options actually change.
     */
    @Test
    void consentIsAllowedWhileFrozenUnderReviewAndUsesTheRevertPath() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));

        write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true));

        verify(publishBundleGuard, never()).refuseTargetEditWhileFrozen(any(), any());
        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
    }

    @Test
    void reSendingTheSameChoicesChangesNothingAndRevertsNothing() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));
        write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true));

        write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true));

        verify(publishBundleGuard, times(1)).revertForBundleEdit(any(), any());
    }

    @Test
    void anInteractionTheCreatorHasDisabledCannotBeAllowed() {
        givenCreator(creatorWith(true, false, false, true, null));
        givenTargets(tiktokTarget("conn-tiktok", null));

        assertThatThrownBy(() -> write(currentEtag(), choice("PUBLIC_TO_EVERYONE", false, true, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("comments are switched off");
        verify(consentRepository, never()).save(any());
        verify(audit, never()).append(any());
    }

    @Test
    void aCreatorWhoCannotPostCannotBeConsentedFor() {
        givenCreator(creatorWith(false, false, false, false, "TikTok has banned this account from posting"));
        givenTargets(tiktokTarget("conn-tiktok", null));

        assertThatThrownBy(() -> write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("banned");
        verify(consentRepository, never()).save(any());
    }

    @Test
    void aPrivacyLevelTheAccountDoesNotOfferIsRefused() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));

        assertThatThrownBy(() -> write(currentEtag(), choice("FOLLOWER_OF_CREATOR", true, true, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not one of this creator's options");
    }

    @Test
    void brandedContentCannotBePrivate() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));
        PublishConsentService.ConsentChoice branded = new PublishConsentService.ConsentChoice(
                "conn-tiktok", "SELF_ONLY", true, true, true, true, false);

        assertThatThrownBy(() -> write(currentEtag(), branded))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("branded content cannot be SELF_ONLY");
    }

    @Test
    void thereMustBeExactlyOneChoicePerTikTokAccount() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null), tiktokTarget("conn-tiktok-2", null));

        assertThatThrownBy(() -> write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("missing for TikTok account 'conn-tiktok-2'");
    }

    @Test
    void aRequestCreatedForSomeoneElseIsRefused() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));
        givenRequest("other-user", OffsetDateTime.now().plusHours(1));

        assertThatThrownBy(() -> write(currentEtag(), "request-1", choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anExpiredRequestIsGone() {
        givenCreator(creatorWith());
        givenTargets(tiktokTarget("conn-tiktok", null));
        givenRequest("user-1", OffsetDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> write(currentEtag(), "request-1", choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(GoneException.class);
    }

    @Test
    void aMachineCredentialCannotUseTheAtomicWriteEither() {
        when(projectSecurityService.requireInteractiveUser(PROJECT)).thenThrow(new ForbiddenException("no"));

        assertThatThrownBy(() -> write(currentEtag(), choice("PUBLIC_TO_EVERYONE", true, true, true)))
                .isInstanceOf(ForbiddenException.class);
        verify(audit).appendRefusal(any());
    }

    // --- helpers ---

    private PublishConsentService.RecordedConsent write(String ifMatch, PublishConsentService.ConsentChoice... choices) {
        return write(ifMatch, null, choices);
    }

    private PublishConsentService.RecordedConsent write(String ifMatch, String requestId,
                                                        PublishConsentService.ConsentChoice... choices) {
        return service.recordInteractiveConsent(PROJECT, WORK_ITEM, new PublishConsentService.ConsentWrite(
                List.of(choices), "v1", "BULK_PAGE", requestId, ifMatch));
    }

    private String currentEtag() {
        return service.consentEtag(post);
    }

    private PublishConsentService.ConsentChoice choice(String level, boolean disableComment,
                                                       boolean disableDuet, boolean disableStitch) {
        return new PublishConsentService.ConsentChoice("conn-tiktok", level, disableComment, disableDuet,
                disableStitch, false, false);
    }

    private TikTokCreatorInfoService.Creator creatorWith() {
        return creatorWith(false, false, false, true, null);
    }

    private TikTokCreatorInfoService.Creator creatorWith(boolean commentDisabled, boolean duetDisabled,
                                                         boolean stitchDisabled, boolean canPost, String reason) {
        return new TikTokCreatorInfoService.Creator("conn-tiktok", "ada", "ada", null,
                List.of("PUBLIC_TO_EVERYONE", "SELF_ONLY"), commentDisabled, duetDisabled, stitchDisabled,
                600, canPost, reason, false);
    }

    private void givenCreator(TikTokCreatorInfoService.Creator creatorInfo) {
        Connection connection = new Connection();
        connection.setId("conn-tiktok");
        when(connectionRepository.findById("conn-tiktok")).thenReturn(Optional.of(connection));
        when(creatorInfoService.get(any(Connection.class))).thenReturn(creatorInfo);
    }

    private void givenRequest(String requestedBy, OffsetDateTime expiresAt) {
        TikTokConsentRequest request = new TikTokConsentRequest();
        request.setId("request-1");
        request.setProjectId(PROJECT);
        request.setRequestedBy(requestedBy);
        request.setWorkItemIds("[\"" + WORK_ITEM + "\"]");
        request.setExpiresAt(expiresAt);
        when(requestRepository.findByIdAndProjectId("request-1", PROJECT)).thenReturn(Optional.of(request));
    }

    private PublishConsentService.ConsentState consent(boolean consented) {
        return service.recordConsent(PROJECT, WORK_ITEM, consented, creator);
    }

    private PublishConsentService.ConsentState read() {
        return service.readConsent(PROJECT, WORK_ITEM, creator);
    }

    private void doDelete() {
        org.mockito.Mockito.doAnswer(invocation -> {
            stored = null;
            return null;
        }).when(consentRepository).deleteByWorkItemId(WORK_ITEM);
    }

    private void givenTargets(PostPublishTarget... targets) {
        when(targetRepository.findAllByWorkItemId(WORK_ITEM))
                .thenReturn(new ArrayList<>(List.of(targets)));
    }

    private void givenUploadedAssets(String... assetIds) {
        List<Asset> assets = new ArrayList<>();
        for (String assetId : assetIds) {
            assets.add(asset(assetId));
        }
        when(assetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(assets);
    }

    private Asset asset(String assetId) {
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setUploadStatus("UPLOADED");
        asset.setGcsPath("projects/" + PROJECT + "/" + assetId + ".mp4");
        return asset;
    }

    private PostPublishTarget tiktokTarget(String connectionId, String publishOptions) {
        return target("tiktok", connectionId, publishOptions);
    }

    private PostPublishTarget target(String platform, String connectionId, String publishOptions) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-" + platform + "-" + connectionId);
        target.setPlatform(platform);
        target.setConnectorId(platform.equals("tiktok") ? "tiktok" : "meta");
        target.setConnectionId(connectionId);
        target.setPublishOptions(publishOptions);
        return target;
    }

    private User user(String id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        return user;
    }
}
