package com.conductor.marketing.promises;

import com.conductor.creative.BrandKitService;
import com.conductor.creative.Creative;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.promises.MessageTagService.TagInput;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tagging a target with a message: one message per target (a second tag moves it), a normalised message, refs that
 * must exist where the workspace can check them, and removal that is safe to repeat.
 */
@Transactional
class MessageTagServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MessageTagService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private ObjectMapper objectMapper;

    private User user;
    private Project project;
    private String projectId;
    private int nextSequence = 1;

    @BeforeEach
    void setUp() {
        user = newUser();
        project = newProject();
        projectId = project.getId();
    }

    @Test
    void tagsATargetWithAMessageAndAnOptionalLabel() {
        MarketingMessageTag saved = service.tag(projectId,
                new TagInput("save-from-tiktok", "KEYWORD", "42", "meal planner"), user.getId());

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getMessage()).isEqualTo("save-from-tiktok");
        assertThat(saved.getTargetKind()).isEqualTo("KEYWORD");
        assertThat(saved.getTargetRef()).isEqualTo("42");
        assertThat(saved.getLabel()).isEqualTo("meal planner");
        assertThat(saved.getCreatedBy()).isEqualTo(user.getId());
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(service.list(projectId)).extracting(MarketingMessageTag::getTargetRef).containsExactly("42");
    }

    @Test
    void aTargetCarriesOneMessageSoTaggingItAgainMovesItAndReplacesTheLabel() {
        MarketingMessageTag first = service.tag(projectId,
                new TagInput("old-message", "KEYWORD", "42", "old label"), user.getId());

        MarketingMessageTag second = service.tag(projectId,
                new TagInput("new-message", "KEYWORD", "42", null), user.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getMessage()).isEqualTo("new-message");
        assertThat(second.getLabel()).isNull();
        assertThat(service.list(projectId)).hasSize(1);
        assertThat(service.list(projectId).get(0).getMessage()).isEqualTo("new-message");
    }

    @Test
    void theSameTargetRefInADifferentKindIsADifferentTarget() {
        service.tag(projectId, new TagInput("a", "KEYWORD", "42", null), null);
        service.tag(projectId, new TagInput("b", "PRODUCT_PAGE", "42", null), null);

        assertThat(service.list(projectId)).hasSize(2);
    }

    @Test
    void theSameTargetInAnotherProjectIsNotTheSameTag() {
        Project other = newProject();
        service.tag(projectId, new TagInput("a", "KEYWORD", "42", null), null);
        service.tag(other.getId(), new TagInput("b", "KEYWORD", "42", null), null);

        assertThat(service.list(projectId)).extracting(MarketingMessageTag::getMessage).containsExactly("a");
        assertThat(service.list(other.getId())).extracting(MarketingMessageTag::getMessage).containsExactly("b");
    }

    @Test
    void aMessageIsTrimmedLowerCasedAndHasItsWhitespaceCollapsed() {
        MarketingMessageTag saved = service.tag(projectId,
                new TagInput("  Save   From\tTikTok ", "KEYWORD", "42", null), null);

        assertThat(saved.getMessage()).isEqualTo("save from tiktok");
    }

    @Test
    void aMessageMustBeNonBlankAndAtMost120Characters() {
        for (String bad : new String[] {null, "", "   ", "x".repeat(121)}) {
            assertThatThrownBy(() -> service.tag(projectId, new TagInput(bad, "KEYWORD", "42", null), null))
                    .as("message '%s'", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("message");
        }
        assertThat(service.tag(projectId, new TagInput("x".repeat(120), "KEYWORD", "42", null), null).getMessage())
                .hasSize(120);
    }

    @Test
    void anUnknownKindIsRefusedWithTheAllowedKinds() {
        for (String bad : new String[] {null, "", "BILLBOARD"}) {
            assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", bad, "42", null), null))
                    .as("kind '%s'", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CREATIVE, PRODUCT_PAGE, KEYWORD, PAYWALL_HEADLINE, POST");
        }
    }

    @Test
    void theKindIsCaseInsensitive() {
        assertThat(service.tag(projectId, new TagInput("m", "keyword", "42", null), null).getTargetKind())
                .isEqualTo("KEYWORD");
    }

    @Test
    void aTargetRefMustBeNonBlankAndAtMost200Characters() {
        for (String bad : new String[] {null, "", "  ", "x".repeat(201)}) {
            assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", "PAYWALL_HEADLINE", bad, null), null))
                    .as("ref '%s'", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("targetRef");
        }
    }

    @Test
    void aLabelOver200CharactersIsRefusedAndABlankLabelIsNone() {
        assertThatThrownBy(() -> service.tag(projectId,
                new TagInput("m", "KEYWORD", "42", "x".repeat(201)), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("label");
        assertThat(service.tag(projectId, new TagInput("m", "KEYWORD", "42", "  "), null).getLabel()).isNull();
    }

    @Test
    void aPaywallHeadlineKeepsItsTextAsGivenApartFromTrimming() {
        MarketingMessageTag saved = service.tag(projectId,
                new TagInput("m", "PAYWALL_HEADLINE", "  Cook smarter: try it free ", null), null);

        assertThat(saved.getTargetRef()).isEqualTo("Cook smarter: try it free");
    }

    @Test
    void aPostMustBeAWorkItemOfThisProject() {
        WorkItem mine = newPost(project);
        WorkItem foreign = newPost(newProject());

        assertThat(service.tag(projectId, new TagInput("m", "POST", mine.getId(), null), null).getTargetRef())
                .isEqualTo(mine.getId());
        assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", "POST", foreign.getId(), null), null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", "POST", "no-such-post", null), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aCreativeMustBeACreativeOfThisProject() {
        String mine = creative(project);
        String foreign = creative(newProject());

        assertThat(service.tag(projectId, new TagInput("m", "CREATIVE", mine, null), null).getTargetRef())
                .isEqualTo(mine);
        assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", "CREATIVE", foreign, null), null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.tag(projectId, new TagInput("m", "CREATIVE", "no-such-creative", null), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aKeywordOrProductPageIsNotCheckedBecauseTheAdsFeedMayNotHaveSyncedYet() {
        assertThat(service.tag(projectId, new TagInput("m", "KEYWORD", "999999", null), null)).isNotNull();
        assertThat(service.tag(projectId, new TagInput("m", "PRODUCT_PAGE", "pp-not-synced", null), null)).isNotNull();
    }

    @Test
    void untagRemovesTheTagAndIsSafeToRepeat() {
        service.tag(projectId, new TagInput("m", "KEYWORD", "42", null), null);

        assertThat(service.untag(projectId, "KEYWORD", "42")).isTrue();
        assertThat(service.list(projectId)).isEmpty();
        assertThat(service.untag(projectId, "KEYWORD", "42")).isFalse();
    }

    @Test
    void untagOnlyRemovesTheNamedTargetInThisProject() {
        Project other = newProject();
        service.tag(projectId, new TagInput("m", "KEYWORD", "42", null), null);
        service.tag(projectId, new TagInput("m", "KEYWORD", "43", null), null);
        service.tag(other.getId(), new TagInput("m", "KEYWORD", "42", null), null);

        service.untag(projectId, "KEYWORD", "42");

        assertThat(service.list(projectId)).extracting(MarketingMessageTag::getTargetRef).containsExactly("43");
        assertThat(service.list(other.getId())).hasSize(1);
    }

    @Test
    void untagRefusesAnUnknownKind() {
        assertThatThrownBy(() -> service.untag(projectId, "BILLBOARD", "42"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("targetKind");
    }

    @Test
    void listIsOrderedByMessageThenKindThenRef() {
        service.tag(projectId, new TagInput("beta", "KEYWORD", "2", null), null);
        service.tag(projectId, new TagInput("alpha", "POST", newPost(project).getId(), null), null);
        service.tag(projectId, new TagInput("alpha", "KEYWORD", "9", null), null);
        service.tag(projectId, new TagInput("alpha", "KEYWORD", "10", null), null);

        List<MarketingMessageTag> tags = service.list(projectId);

        assertThat(tags).extracting(MarketingMessageTag::getMessage)
                .containsExactly("alpha", "alpha", "alpha", "beta");
        assertThat(tags).extracting(MarketingMessageTag::getTargetKind)
                .containsExactly("KEYWORD", "KEYWORD", "POST", "KEYWORD");
        assertThat(tags.subList(0, 2)).extracting(MarketingMessageTag::getTargetRef).containsExactly("10", "9");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private User newUser() {
        User u = new User();
        u.setFirebaseUid("tags-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Tags Test");
        return userRepository.save(u);
    }

    private Project newProject() {
        Project p = new Project();
        p.setName("Tags Test Project");
        p.setKey("TG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        return projectRepository.save(p);
    }

    private WorkItem newPost(Project in) {
        WorkItem post = new WorkItem();
        post.setProject(in);
        post.setType("POST");
        post.setTitle("A post");
        post.setCurrentStatus("PUBLISHED");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCreatedBy(user);
        post.setSequenceNumber(nextSequence++);
        return workItemRepository.saveAndFlush(post);
    }

    private String creative(Project in) {
        Creative c = new Creative();
        c.setProjectId(in.getId());
        c.setBrandKitId(brandKitService.resolveDefault(in.getId()).getId());
        c.setNumber(nextSequence++);
        c.setVariantLetter("a");
        c.setState(Creative.STATE_READY);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline("Save from TikTok");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(user.getId());
        return creativeRepository.saveAndFlush(c).getId();
    }
}
