package com.conductor.repository;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.service.AssetService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed coverage for {@code V138__post_publish_target_asset.sql}: the things only real Postgres can
 * prove about a per-target media selection — that its order is unique, that it disappears with the target
 * it belongs to, and that deleting an Asset leaves the target explicit-and-empty rather than quietly
 * inheriting the Post's whole set again.
 *
 * <p>Per {@code docs/testing-guidelines.md} this rides the shared singleton Postgres and asserts on rows
 * it created rather than on global counts.
 */
@Transactional
class PostPublishTargetAssetRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PostPublishTargetAssetRepository targetAssetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    @PersistenceContext private EntityManager entityManager;

    private Project project;
    private User creator;
    private String connectionId;
    private WorkItem post;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Target Asset Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Per-target media test project");
        project.setKey("PT" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(connection).getId();

        post = newWorkItem();
    }

    @Test
    void theMigrationCreatesThePositionGuaranteeAndTheAssetLookupIndex() {
        @SuppressWarnings("unchecked")
        List<String> indexNames = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'post_publish_target_asset'")
                .getResultList();

        assertThat(indexNames).contains(
                "uq_post_publish_target_asset_position",
                "idx_post_publish_target_asset_asset");
    }

    @Test
    void aSelectionReadsBackInItsStoredOrderRatherThanInsertionOrder() {
        PostPublishTarget target = saveTarget("instagram");
        Asset first = saveAsset("a.jpg");
        Asset second = saveAsset("b.jpg");
        Asset third = saveAsset("c.jpg");
        // Inserted out of order deliberately: position is what the platform sees, not insertion sequence.
        targetAssetRepository.saveAll(List.of(
                new PostPublishTargetAsset(target.getId(), third.getId(), 2),
                new PostPublishTargetAsset(target.getId(), first.getId(), 0),
                new PostPublishTargetAsset(target.getId(), second.getId(), 1)));
        targetAssetRepository.flush();

        assertThat(targetAssetRepository.findAllByTargetId(target.getId()))
                .extracting(PostPublishTargetAsset::getAssetId)
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void twoAssetsCannotShareOnePositionInTheSameTarget() {
        PostPublishTarget target = saveTarget("instagram");
        Asset first = saveAsset("a.jpg");
        Asset second = saveAsset("b.jpg");
        targetAssetRepository.saveAndFlush(new PostPublishTargetAsset(target.getId(), first.getId(), 0));

        assertThatThrownBy(() -> targetAssetRepository.saveAndFlush(
                new PostPublishTargetAsset(target.getId(), second.getId(), 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thesamePositionInTwoDifferentTargetsIsFine() {
        PostPublishTarget instagram = saveTarget("instagram");
        PostPublishTarget facebook = saveTarget("facebook");
        Asset asset = saveAsset("shared.jpg");

        targetAssetRepository.saveAndFlush(new PostPublishTargetAsset(instagram.getId(), asset.getId(), 0));
        targetAssetRepository.saveAndFlush(new PostPublishTargetAsset(facebook.getId(), asset.getId(), 0));

        assertThat(targetAssetRepository.findAllByTargetIdIn(List.of(instagram.getId(), facebook.getId())))
                .hasSize(2);
    }

    @Test
    void deletingATargetTakesItsSelectionWithIt() {
        PostPublishTarget target = saveTarget("instagram");
        Asset asset = saveAsset("a.jpg");
        targetAssetRepository.saveAndFlush(new PostPublishTargetAsset(target.getId(), asset.getId(), 0));

        targetRepository.delete(target);
        targetRepository.flush();
        entityManager.clear();

        assertThat(targetAssetRepository.findAllByTargetId(target.getId())).isEmpty();
        // The Asset itself is untouched: it belongs to the Post, not to the destination that referenced it.
        assertThat(assetRepository.findById(asset.getId())).isPresent();
    }

    @Test
    void deletingAnAssetLeavesTheTargetExplicitAndEmptyRatherThanInheriting() {
        PostPublishTarget target = saveTarget("instagram");
        target.setCustomMedia(true);
        targetRepository.saveAndFlush(target);
        Asset asset = saveAsset("only.jpg");
        targetAssetRepository.saveAndFlush(new PostPublishTargetAsset(target.getId(), asset.getId(), 0));

        assetRepository.delete(asset);
        assetRepository.flush();
        entityManager.clear();

        assertThat(targetAssetRepository.findAllByTargetId(target.getId())).isEmpty();
        // custom_media survives the cascade, which is the whole point: "chose files that are now gone" has
        // to stay distinguishable from "never chose any", or the approval gate would wave it through with
        // the Post's entire media set.
        assertThat(targetRepository.findById(target.getId()))
                .get()
                .extracting(PostPublishTarget::isCustomMedia)
                .isEqualTo(true);
    }

    @Test
    void clearingOneTargetsSelectionLeavesTheOthersAlone() {
        PostPublishTarget instagram = saveTarget("instagram");
        PostPublishTarget facebook = saveTarget("facebook");
        Asset asset = saveAsset("a.jpg");
        targetAssetRepository.saveAll(List.of(
                new PostPublishTargetAsset(instagram.getId(), asset.getId(), 0),
                new PostPublishTargetAsset(facebook.getId(), asset.getId(), 0)));
        targetAssetRepository.flush();

        targetAssetRepository.deleteAllByTargetId(instagram.getId());
        targetAssetRepository.flush();

        assertThat(targetAssetRepository.findAllByTargetId(instagram.getId())).isEmpty();
        assertThat(targetAssetRepository.findAllByTargetId(facebook.getId())).hasSize(1);
    }

    private WorkItem newWorkItem() {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle("Per-target media");
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(nextSequenceNumber++);
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget saveTarget(String platform) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(post);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PENDING);
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private Asset saveAsset(String label) {
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType("instagram_post");
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef("marketing-assets/" + UUID.randomUUID());
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(1024L);
        asset.setGcsPath("marketing-assets/" + UUID.randomUUID());
        return assetRepository.saveAndFlush(asset);
    }
}
