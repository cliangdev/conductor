package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CopyRule;
import com.conductor.generated.v2.model.CopyRuleField;
import com.conductor.generated.v2.model.CreateBrandKitRequest;
import com.conductor.generated.v2.model.PatchBrandKitRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BrandKitService} against a real database (COND-24 T2): the lazily-created default kit, the
 * default-switch transaction, delete refusals, and 422 on a copy rule that fails to compile.
 */
class BrandKitServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private BrandKitService brandKitService;
    @Autowired private BrandKitRepository brandKitRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Brand Kit Test");
        project.setKey("BK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
    }

    @Test
    void listingLazilyCreatesExactlyOneDefaultKit() {
        List<BrandKit> kits = brandKitService.listKits(project.getId(), admin);
        assertThat(kits).hasSize(1);
        assertThat(kits.get(0).getSlug()).isEqualTo(BrandKitService.DEFAULT_SLUG);
        assertThat(kits.get(0).isDefault()).isTrue();
        // Brand-free: nothing Rexipe-specific in the default tokens.
        assertThat(kits.get(0).getTokens().toString()).doesNotContainIgnoringCase("rexipe")
                .doesNotContain("#FF5A5F");

        // Calling again is idempotent -- no second row.
        List<BrandKit> again = brandKitService.listKits(project.getId(), admin);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).getId()).isEqualTo(kits.get(0).getId());
    }

    @Test
    void concurrentFirstRequestsCreateExactlyOneDefaultKit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<BrandKit>> tasks = List.of(
                    () -> brandKitService.resolveDefault(project.getId()),
                    () -> brandKitService.resolveDefault(project.getId()),
                    () -> brandKitService.resolveDefault(project.getId()),
                    () -> brandKitService.resolveDefault(project.getId()),
                    () -> brandKitService.resolveDefault(project.getId()),
                    () -> brandKitService.resolveDefault(project.getId()));
            for (Future<BrandKit> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(brandKitRepository.findAllByProjectIdOrderByCreatedAtAsc(project.getId())).hasSize(1);
    }

    @Test
    void switchingDefaultUnsetsTheOldOneInOneTransaction() {
        brandKitService.resolveDefault(project.getId());
        BrandKit second = brandKitService.createKit(project.getId(),
                new CreateBrandKitRequest("second", "Second Brand"), admin);
        assertThat(second.isDefault()).isFalse();

        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setIsDefault(true);
        BrandKit updated = brandKitService.patchKit(project.getId(), second.getId(), patch, admin);

        assertThat(updated.isDefault()).isTrue();
        List<BrandKit> kits = brandKitRepository.findAllByProjectIdOrderByCreatedAtAsc(project.getId());
        assertThat(kits).filteredOn(BrandKit::isDefault).extracting(BrandKit::getId).containsExactly(second.getId());
    }

    @Test
    void deletingTheDefaultKitIsRefused() {
        BrandKit defaultKit = brandKitService.resolveDefault(project.getId());
        assertThatThrownBy(() -> brandKitService.deleteKit(project.getId(), defaultKit.getId(), admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aCopyRuleWithAnInvalidPatternIsRefusedWith422() {
        CopyRule badRule = new CopyRule("bad", "[unterminated", "message", List.of(CopyRuleField.HEADLINE));
        CreateBrandKitRequest request = new CreateBrandKitRequest("broken", "Broken Kit");
        request.setCopyRules(List.of(badRule));

        assertThatThrownBy(() -> brandKitService.createKit(project.getId(), request, admin))
                .isInstanceOf(BrandKitValidationException.class)
                .satisfies(e -> {
                    BrandKitValidationException bkve = (BrandKitValidationException) e;
                    assertThat(bkve.fieldErrors()).isNotEmpty();
                    assertThat(bkve.fieldErrors().get(0).field()).isEqualTo("copyRules[0].pattern");
                });
    }

    @Test
    void aSecondKitWithTheSameSlugConflicts() {
        brandKitService.createKit(project.getId(), new CreateBrandKitRequest("second-brand", "Second"), admin);
        assertThatThrownBy(() -> brandKitService.createKit(project.getId(),
                new CreateBrandKitRequest("second-brand", "Another"), admin))
                .isInstanceOf(ConflictException.class);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Brand Admin");
        return userRepository.save(user);
    }
}
