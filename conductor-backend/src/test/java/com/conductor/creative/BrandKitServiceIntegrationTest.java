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
import com.conductor.generated.v2.model.SlideshowStyle;
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
        // Brand-free: no brand-specific values in the default tokens.
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

    // ── Slideshow style ────────────────────────────────────────────────────────────────────────

    private static SlideshowStyle style() {
        return new SlideshowStyle().fontFamily("Poppins").fontUrl("https://fonts.googleapis.com/css2?family=Poppins")
                .boxBg("#FFFFFF").boxInk("#111111").darkBg("#000000").darkInk("#FAFAFA").outline("#222222")
                .accent("#FF5A5F").defaultText("stroke").textSize(64).subSize(36);
    }

    @Test
    void slideshowStyleRoundTripsThroughCreateAndPatchAndClearsWithNull() {
        CreateBrandKitRequest create = new CreateBrandKitRequest("styled", "Styled");
        create.setSlideshowStyle(style());
        BrandKit kit = brandKitService.createKit(project.getId(), create, admin);
        BrandKit read = brandKitService.getKit(project.getId(), kit.getId(), admin);
        assertThat(read.getSlideshowStyle().get("defaultText").asText()).isEqualTo("stroke");
        assertThat(read.getSlideshowStyle().get("textSize").asInt()).isEqualTo(64);

        // Absent leaves it unchanged.
        PatchBrandKitRequest rename = new PatchBrandKitRequest();
        rename.setName("Styled 2");
        assertThat(brandKitService.patchKit(project.getId(), kit.getId(), rename, admin).getSlideshowStyle()).isNotNull();

        // Present replaces the whole object.
        PatchBrandKitRequest replace = new PatchBrandKitRequest();
        replace.setSlideshowStyle(new SlideshowStyle().accent("#00FF00"));
        BrandKit replaced = brandKitService.patchKit(project.getId(), kit.getId(), replace, admin);
        assertThat(replaced.getSlideshowStyle().has("fontFamily")).isFalse();
        assertThat(replaced.getSlideshowStyle().get("accent").asText()).isEqualTo("#00FF00");

        // An explicit null deserializes to an empty style, which clears.
        PatchBrandKitRequest clear = new PatchBrandKitRequest();
        clear.setSlideshowStyle(new SlideshowStyle());
        assertThat(brandKitService.patchKit(project.getId(), kit.getId(), clear, admin).getSlideshowStyle()).isNull();
    }

    @Test
    void aSlugOverSixtyFourCharactersOrWithInvalidCharactersIs422NotA500() {
        for (String bad : new String[] {"a".repeat(65), "Upper", "with space", "under_score", "emoji-\u2603"}) {
            assertThatThrownBy(() -> brandKitService.createKit(project.getId(), new CreateBrandKitRequest(bad, "Bad"), admin))
                    .isInstanceOf(BrandKitValidationException.class)
                    .satisfies(e -> assertThat(e.getMessage()).contains("slug"));
        }
        BrandKit ok = brandKitService.createKit(project.getId(), new CreateBrandKitRequest("a".repeat(64), "Longest"), admin);
        assertThat(ok.getSlug()).hasSize(64);
    }

    @Test
    void anExplicitNullSlideshowStyleInJsonArrivesAsAnEmptyStyleAndAbsentStaysNull() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        PatchBrandKitRequest explicitNull = mapper.readValue("{\"slideshowStyle\":null}", PatchBrandKitRequest.class);
        assertThat(explicitNull.getSlideshowStyle()).isNotNull();
        assertThat(SlideshowStyles.toStored(new com.fasterxml.jackson.databind.ObjectMapper(), explicitNull.getSlideshowStyle())).isNull();
        assertThat(mapper.readValue("{\"name\":\"x\"}", PatchBrandKitRequest.class).getSlideshowStyle()).isNull();
        // Same under Jackson 2, in case the web layer is configured with it.
        com.fasterxml.jackson.databind.ObjectMapper v2 = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(v2.readValue("{\"slideshowStyle\":null}", PatchBrandKitRequest.class).getSlideshowStyle()).isNotNull();
        assertThat(v2.readValue("{\"name\":\"x\"}", PatchBrandKitRequest.class).getSlideshowStyle()).isNull();
    }

    @Test
    void invalidSlideshowStyleFieldsAreRefusedWith422NamingEachField() {
        SlideshowStyle bad = new SlideshowStyle().boxBg("red").accent("#12345").defaultText("neon")
                .textSize(10).subSize(97).fontUrl("ftp://example.com/x.css");
        CreateBrandKitRequest request = new CreateBrandKitRequest("badstyle", "Bad");
        request.setSlideshowStyle(bad);
        assertThatThrownBy(() -> brandKitService.createKit(project.getId(), request, admin))
                .isInstanceOf(BrandKitValidationException.class)
                .satisfies(e -> assertThat(((BrandKitValidationException) e).fieldErrors())
                        .extracting(BrandKitValidationException.FieldError::field)
                        .containsExactlyInAnyOrder("slideshowStyle.boxBg", "slideshowStyle.accent",
                                "slideshowStyle.defaultText", "slideshowStyle.textSize",
                                "slideshowStyle.subSize", "slideshowStyle.fontUrl"));

        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setSlideshowStyle(new SlideshowStyle().fontFamily("x".repeat(201)).fontUrl("not a url"));
        assertThatThrownBy(() -> brandKitService.patchKit(project.getId(), kit.getId(), patch, admin))
                .isInstanceOf(BrandKitValidationException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("slideshowStyle.fontFamily", "slideshowStyle.fontUrl"));
    }

    @Test
    void aSlideshowStyleDoesNotMakeTheKitConfigured() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        PatchBrandKitRequest patch = new PatchBrandKitRequest();
        patch.setSlideshowStyle(style());
        BrandKit updated = brandKitService.patchKit(project.getId(), kit.getId(), patch, admin);
        assertThat(updated.getSlideshowStyle()).isNotNull();
        assertThat(brandKitService.isConfigured(updated)).isFalse();
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Brand Admin");
        return userRepository.save(user);
    }
}
