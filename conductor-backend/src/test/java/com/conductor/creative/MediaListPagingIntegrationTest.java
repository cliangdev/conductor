package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Keyset paging of the Media list: no overlap or skips, stable ties, filters before the cut, cursor errors. */
class MediaListPagingIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativePhotoService photoService;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private User user;
    private Project project;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Pager");
        user = userRepository.save(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        project = new Project();
        project.setName("Paging");
        project.setKey("PG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);
        ProjectMember m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private CreativePhoto photo(String label, OffsetDateTime at, String kind, boolean checked, boolean ai, boolean blocked) {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".jpg");
        p.setContentType("image/jpeg");
        p.setSizeBytes(10L);
        p.setWidth(10);
        p.setHeight(10);
        p.setSource("own");
        p.setLicence("Own work");
        p.setFocal(objectMapper.createObjectNode());
        p.setLabel(label);
        p.setMediaKind(kind);
        p.setChecked(checked);
        p.setAiGenerated(ai);
        p.setBlocked(blocked);
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedAt(at);
        p.setCreatedBy(user.getId());
        return photoRepository.save(p);
    }

    private CreativePhoto photo(String label, OffsetDateTime at) {
        return photo(label, at, CreativePhoto.MEDIA_KIND_IMAGE, true, false, false);
    }

    private CreativePhotoService.PhotoPage page(Set<String> kinds, String contains, boolean unchecked, boolean ai,
                                                boolean blocked, Integer limit, String cursor) {
        return photoService.listPhotosPage(project.getId(), blocked, kinds, null, contains, unchecked, ai, limit, cursor, user);
    }

    private List<String> walk(int limit) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            var page = page(null, null, false, false, false, limit, cursor);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(limit);
            page.items().forEach(v -> ids.add(v.photo().getId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return ids;
    }

    @Test
    void pagesNeitherOverlapNorSkipAndMatchTheUnpagedOrderWithTiedTimestamps() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 11; i++) {
            // Groups of 3 share a created_at, so the id tiebreak is exercised across page boundaries.
            photo("p" + i, base.minusSeconds(i / 3));
        }
        List<String> unpaged = photoService.listPhotos(project.getId(), false, null, user).stream()
                .map(v -> v.photo().getId()).toList();
        assertThat(unpaged).hasSize(11);
        for (int limit : new int[] {1, 2, 3, 4, 5, 11, 50}) {
            assertThat(walk(limit)).as("limit " + limit).containsExactlyElementsOf(unpaged);
        }
    }

    @Test
    void lastPageHasNoCursorEvenWhenExactlyFull() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 4; i++) {
            photo("p" + i, base.minusSeconds(i));
        }
        var first = page(null, null, false, false, false, 2, null);
        assertThat(first.nextCursor()).isNotNull();
        var second = page(null, null, false, false, false, 2, first.nextCursor());
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        assertThat(page(null, null, false, false, false, 4, null).nextCursor()).isNull();
    }

    @Test
    void filtersApplyBeforeThePageIsCut() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // Newest 5 are videos; the 4 images underneath must still come back as a full page-1 of images.
        for (int i = 0; i < 5; i++) {
            photo("clip " + i, base.minusSeconds(i), CreativePhoto.MEDIA_KIND_VIDEO, true, false, false);
        }
        for (int i = 0; i < 4; i++) {
            photo("Beach " + i, base.minusSeconds(10 + i), CreativePhoto.MEDIA_KIND_IMAGE, i % 2 == 0, i < 2, false);
        }
        photo("beach hidden", base.minusSeconds(30), CreativePhoto.MEDIA_KIND_IMAGE, true, false, true);

        var images = page(Set.of(CreativePhoto.MEDIA_KIND_IMAGE), null, false, false, false, 3, null);
        assertThat(images.items()).hasSize(3);
        assertThat(images.nextCursor()).isNotNull();
        var rest = page(Set.of(CreativePhoto.MEDIA_KIND_IMAGE), null, false, false, false, 3, images.nextCursor());
        assertThat(rest.items()).hasSize(1);
        assertThat(rest.nextCursor()).isNull();

        // Substring, case-insensitive; blocked excluded unless asked for.
        assertThat(page(null, "EACH 1", false, false, false, 10, null).items()).hasSize(1);
        assertThat(page(null, "beach", false, false, false, 10, null).items()).hasSize(4);
        assertThat(page(null, "beach", false, false, true, 10, null).items()).hasSize(5);
        // LIKE wildcards in the search are literal.
        assertThat(page(null, "%", false, false, false, 10, null).items()).isEmpty();

        assertThat(page(null, null, true, false, false, 50, null).items()).hasSize(2);
        assertThat(page(null, null, false, true, false, 50, null).items()).hasSize(2);
        assertThat(page(null, null, true, true, false, 50, null).items()).hasSize(1);
    }

    @Test
    void invalidCursorIsABusinessException() {
        photo("a", OffsetDateTime.now());
        for (String bad : new String[] {"not a cursor", "!!!", "YWJj", "MToyOg"}) {
            assertThatThrownBy(() -> page(null, null, false, false, false, 5, bad))
                    .as(bad).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void noPagingParamsReturnsEverythingWithNoCursor() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 70; i++) {
            photo("p" + i, base.minusSeconds(i));
        }
        var all = page(null, null, false, false, false, null, null);
        assertThat(all.items()).hasSize(70);
        assertThat(all.nextCursor()).isNull();
    }

    @Test
    void usageCountsAreOnlyForTheReturnedPage() {
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        photo("a", base);
        photo("b", base.minusSeconds(1));
        var page = page(null, null, false, false, false, 1, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).postCount()).isZero();
        assertThat(page.items().get(0).creativeCount()).isZero();
    }
}
