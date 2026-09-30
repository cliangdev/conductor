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
import java.util.Map;
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

    @Test
    void freshDefaultKitIsNotConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        assertThat(brandKitService.isConfigured(kit)).isFalse();
    }

    @Test
    void aTokenChangeMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(Map.of("accent", "#000000"));
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void lowercaseDefaultHexTokensAreStillUnconfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        // Same default palette, just lower-case hex (and a little whitespace) -- not a customisation.
        Map<String, String> lowercaseDefaults = Map.of(
                "accent", " #3b82f6", "accent2", "#2563eb ", "darkBg", "#18181b", "darkInk", "#ffffff",
                "lightBg", "#f4f4f5", "lightCard", "#ffffff", "ink", "#18181b", "ink2", "#71717a");
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(lowercaseDefaults);
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        assertThat(brandKitService.isConfigured(updated)).isFalse();
    }

    @Test
    void aFontFamilyMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setFontFamily("Inter");
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void aLogoSlotMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        String gcsPath = "projects/" + project.getId() + "/brand/" + kit.getId() + "/mark-" + UUID.randomUUID() + ".png";
        BrandKit updated = brandKitService.confirmImage(project.getId(), kit.getId(), "mark", gcsPath, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void aCtaClaimMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setCtaClaim("Get 20% off");
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void aCopyRuleMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setCopyRules(List.of(new CopyRule("id1", "abc", "message", List.of(CopyRuleField.HEADLINE))));
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void anApprovedLineMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setApprovedLines(List.of("Approved line"));
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void requiringAnAccentPhraseMakesTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setAccentPhraseRequired(true);
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void renamingTheKitMakesItConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setName("My Brand");
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updated)).isTrue();
    }

    @Test
    void switchingDefaultDoesNotChangeEitherKitsConfiguredStatus() {
        BrandKit defaultKit = brandKitService.resolveDefault(project.getId());
        assertThat(brandKitService.isConfigured(defaultKit)).isFalse();
        BrandKit second = brandKitService.createKit(project.getId(),
                new CreateBrandKitRequest("second", "Second"), admin);
        // A custom name differing from the default kit's name already makes it configured.
        assertThat(brandKitService.isConfigured(second)).isTrue();

        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setIsDefault(true);
        BrandKit updatedSecond = brandKitService.patchKit(project.getId(), second.getId(), patch, admin);
        assertThat(brandKitService.isConfigured(updatedSecond)).isTrue();

        BrandKit reloadedOldDefault = brandKitRepository.findByIdAndProjectId(defaultKit.getId(), project.getId())
                .orElseThrow();
        assertThat(brandKitService.isConfigured(reloadedOldDefault)).isFalse();
    }

    @Test
    void tokenPatchMergesKeepingOmittedKeysUnchanged() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        String originalAccent2 = kit.getTokens().get("accent2").asText();

        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(Map.of("accent", "#111111"));
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        assertThat(updated.getTokens().get("accent").asText()).isEqualTo("#111111");
        assertThat(updated.getTokens().get("accent2").asText()).isEqualTo(originalAccent2);
        assertThat(updated.getTokens().get("darkBg")).isNotNull();
    }

    @Test
    void fullTokenMapPatchStillReplacesEveryKey() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        Map<String, String> fullMap = Map.of(
                "accent", "#111111", "accent2", "#222222", "darkBg", "#333333", "darkInk", "#444444",
                "lightBg", "#555555", "lightCard", "#666666", "ink", "#777777", "ink2", "#888888");
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(fullMap);
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        fullMap.forEach((key, value) -> assertThat(updated.getTokens().get(key).asText()).isEqualTo(value));
    }

    @Test
    void tokenPatchStartsFromAnEmptyMapWhenStoredTokensAreNotAnObject() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        kit.setTokens(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        kit = brandKitRepository.saveAndFlush(kit);

        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(Map.of("accent", "#123456"));

        // Must not throw (no ClassCastException casting a non-object JsonNode to ObjectNode), and the
        // merge starts from empty rather than pulling in stale/malformed data.
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        assertThat(updated.getTokens().size()).isEqualTo(1);
        assertThat(updated.getTokens().get("accent").asText()).isEqualTo("#123456");
    }

    @Test
    void tokenPatchWithAJsonNullValueRemovesTheKey() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        assertThat(kit.getTokens().has("accent")).isTrue();

        Map<String, String> tokensWithNull = new java.util.HashMap<>();
        tokensWithNull.put("accent", null);
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setTokens(tokensWithNull);
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        assertThat(updated.getTokens().has("accent")).isFalse();
        // Every other key is untouched.
        assertThat(updated.getTokens().get("accent2")).isNotNull();
    }

    @Test
    void addApprovedLinesAppendsAndDedupesKeepingOrder() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest seed = new PatchBrandKitRequest();
        seed.setApprovedLines(List.of("Line A", "Line B"));
        kit = brandKitService.patchKit(project.getId(), kit.getId(), seed, admin);

        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setAddApprovedLines(List.of("Line B", "Line C"));
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);

        List<String> lines = new java.util.ArrayList<>();
        updated.getApprovedLines().forEach(n -> lines.add(n.asText()));
        assertThat(lines).containsExactly("Line A", "Line B", "Line C");
    }

    @Test
    void sendingApprovedLinesAndAddApprovedLinesTogetherIs422() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setApprovedLines(List.of("Line A"));
        patch.setAddApprovedLines(List.of("Line B"));

        assertThatThrownBy(() -> brandKitService.patchKit(project.getId(), kit.getId(), patch, admin))
                .isInstanceOf(BrandKitValidationException.class)
                .satisfies(e -> {
                    BrandKitValidationException bkve = (BrandKitValidationException) e;
                    assertThat(bkve.fieldErrors()).extracting(BrandKitValidationException.FieldError::field)
                            .contains("addApprovedLines");
                });
    }

    @Test
    void the422DetailNamesTheFailingField() {
        CopyRule badRule = new CopyRule("bad", "[unterminated", "message", List.of(CopyRuleField.HEADLINE));
        CreateBrandKitRequest request = new CreateBrandKitRequest("broken2", "Broken Kit 2");
        request.setCopyRules(List.of(badRule));

        assertThatThrownBy(() -> brandKitService.createKit(project.getId(), request, admin))
                .isInstanceOf(BrandKitValidationException.class)
                .satisfies(e -> assertThat(e.getMessage()).startsWith("copyRules[0].pattern:"));
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Brand Admin");
        return userRepository.save(user);
    }
}
