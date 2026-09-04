package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        service = new PublishConsentService(consentRepository, targetRepository, targetAssetRepository,
                assetRepository, workItemRepository, projectSecurityService);

        creator = user("user-1", "Ada Creator");
        when(projectSecurityService.isProjectMember(PROJECT, "user-1")).thenReturn(true);

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

    // --- helpers ---

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
