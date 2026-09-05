package com.conductor.repository;

import com.conductor.entity.Asset;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed coverage for the file-upload storage columns added to {@code assets} by
 * {@code V127__asset_file_upload.sql} (COND-23). Three things can only be proven against real
 * Postgres with Flyway + {@code ddl-auto=validate}: that the migration applies and the four columns
 * are nullable, that a pre-existing {@code kind='link'} row still saves and reads back untouched, and
 * that the CHECK constraint actually rejects an UPLOADED row missing its storage location.
 *
 * <p>Per {@code docs/testing-guidelines.md} this rides the shared singleton Postgres and isolates
 * itself with random ids rather than assuming an empty database.
 */
class AssetFileUploadTest extends AbstractNoneWebIntegrationTest {

    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private WorkItem workItem;

    @BeforeEach
    void setUp() {
        User creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Asset Creator");
        creator = userRepository.save(creator);

        Project project = new Project();
        project.setName("Asset Upload Test Project");
        project.setKey("AU" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("TASK");
        item.setTitle("Publish the thing");
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(1);
        workItem = workItemRepository.save(item);
    }

    private Asset newAsset(String kind, String ref) {
        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType("published_url");
        asset.setKind(kind);
        asset.setRef(ref);
        return asset;
    }

    @Test
    void linkAssetSavesAndReadsBackWithEveryUploadColumnNull() {
        Asset saved = assetRepository.saveAndFlush(newAsset("link", "https://example.com/post"));

        Asset reloaded = assetRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getKind()).isEqualTo("link");
        assertThat(reloaded.getRef()).isEqualTo("https://example.com/post");
        assertThat(reloaded.getUploadStatus()).isNull();
        assertThat(reloaded.getContentType()).isNull();
        assertThat(reloaded.getSizeBytes()).isNull();
        assertThat(reloaded.getGcsPath()).isNull();
    }

    @Test
    void fileAssetRoundTripsAllFourUploadColumns() {
        Asset asset = newAsset("file", "hero.png");
        asset.setUploadStatus("UPLOADED");
        asset.setContentType("image/png");
        asset.setSizeBytes(204_800L);
        asset.setGcsPath("projects/p1/assets/" + UUID.randomUUID() + "/hero.png");
        Asset saved = assetRepository.saveAndFlush(asset);

        Asset reloaded = assetRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUploadStatus()).isEqualTo("UPLOADED");
        assertThat(reloaded.getContentType()).isEqualTo("image/png");
        assertThat(reloaded.getSizeBytes()).isEqualTo(204_800L);
        assertThat(reloaded.getGcsPath()).isEqualTo(asset.getGcsPath());
    }

    @Test
    void pendingFileAssetIsAllowedWithoutStorageLocation() {
        Asset asset = newAsset("file", "hero.png");
        asset.setUploadStatus("PENDING");
        Asset saved = assetRepository.saveAndFlush(asset);

        Asset reloaded = assetRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUploadStatus()).isEqualTo("PENDING");
        assertThat(reloaded.getGcsPath()).isNull();
        assertThat(reloaded.getContentType()).isNull();
    }

    @Test
    void uploadedFileAssetWithoutGcsPathIsRejected() {
        Asset asset = newAsset("file", "hero.png");
        asset.setUploadStatus("UPLOADED");
        asset.setContentType("image/png");

        assertThatThrownBy(() -> assetRepository.saveAndFlush(asset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uploadedFileAssetWithoutContentTypeIsRejected() {
        Asset asset = newAsset("file", "hero.png");
        asset.setUploadStatus("UPLOADED");
        asset.setGcsPath("projects/p1/assets/" + UUID.randomUUID() + "/hero.png");

        assertThatThrownBy(() -> assetRepository.saveAndFlush(asset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
