package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.DocVersionRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ProjectDocService#setTaskState} — the in-place checkbox flip that backs ticking a task
 * item from the doc viewer without opening the editor.
 */
@ExtendWith(MockitoExtension.class)
class ProjectDocServiceTest {

    @Mock
    private ProjectDocRepository projectDocRepository;

    @Mock
    private DocVersionRepository docVersionRepository;

    @Mock
    private DocFolderService docFolderService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private DocCommentService docCommentService;

    private ProjectDocService service;
    private ProjectDoc doc;
    private DocActor human;

    @BeforeEach
    void setUp() {
        service = new ProjectDocService(
                projectDocRepository, docVersionRepository, docFolderService, projectRepository);
        // docCommentService is @Lazy field-injected in production; set it here so "never called" is a
        // real assertion rather than a null-reference accident.
        ReflectionTestUtils.setField(service, "docCommentService", docCommentService);

        User user = new User();
        user.setId("user-1");
        human = DocActor.of(user);

        Project project = new Project();
        project.setId("proj-1");

        doc = new ProjectDoc();
        doc.setId("doc-1");
        doc.setProject(project);

        when(projectDocRepository.findByIdWithUsers("doc-1")).thenReturn(Optional.of(doc));
    }

    private String toggle(String content, int lineNumber, boolean checked) {
        doc.setContent(content);
        return service.setTaskState("proj-1", "doc-1", lineNumber, checked, human).getContent();
    }

    @Test
    void checksAnUncheckedItem() {
        assertThat(toggle("- [ ] alpha", 1, true)).isEqualTo("- [x] alpha");
    }

    @Test
    void unchecksACheckedItem() {
        assertThat(toggle("- [x] alpha", 1, false)).isEqualTo("- [ ] alpha");
    }

    @Test
    void isANoOpWhenAlreadyInTheRequestedState() {
        assertThat(toggle("- [x] alpha", 1, true)).isEqualTo("- [x] alpha");
    }

    @Test
    void onlyTouchesTheAddressedLine() {
        assertThat(toggle("- [ ] alpha\n- [ ] beta\n- [ ] gamma", 2, true))
                .isEqualTo("- [ ] alpha\n- [x] beta\n- [ ] gamma");
    }

    @ParameterizedTest
    @CsvSource({
            "'- [ ] x','- [x] x'",
            "'* [ ] x','* [x] x'",
            "'+ [ ] x','+ [x] x'",
            "'1. [ ] x','1. [x] x'",
            "'1) [ ] x','1) [x] x'",
            "'    - [ ] nested','    - [x] nested'",
            "'> - [ ] quoted','> - [x] quoted'",
    })
    void handlesEveryMarkerForm(String input, String expected) {
        assertThat(toggle(input, 1, true)).isEqualTo(expected);
    }

    @Test
    void preservesATrailingNewline() {
        // The 1-arg String.split drops trailing empties, which would silently eat this on every toggle.
        assertThat(toggle("- [ ] alpha\n", 1, true)).isEqualTo("- [x] alpha\n");
    }

    @Test
    void preservesCarriageReturnsOnCrlfContent() {
        assertThat(toggle("- [ ] alpha\r\n- [ ] beta\r\n", 1, true))
                .isEqualTo("- [x] alpha\r\n- [ ] beta\r\n");
    }

    @Test
    void preservesTrailingTextAfterTheCheckbox() {
        assertThat(toggle("- [ ] alpha **bold** `code`", 1, true))
                .isEqualTo("- [x] alpha **bold** `code`");
    }

    @Test
    void neitherMintsAVersionNorMarksCommentsStale() {
        toggle("- [ ] alpha", 1, true);

        // Both are what updateDoc does and what makes it unusable for a checkbox click: one history
        // entry per tick, and every unresolved comment flagged stale even though no line moved.
        verify(docVersionRepository, never()).save(any());
        verify(docCommentService, never()).markCommentsStale(anyString());
        verify(projectDocRepository).save(doc);
    }

    @Test
    void rejectsALineThatIsNoLongerATaskItem() {
        doc.setContent("just a paragraph");

        assertThatThrownBy(() -> service.setTaskState("proj-1", "doc-1", 1, true, human))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer a task list item");
    }

    @Test
    void rejectsAPlainBulletWithNoCheckbox() {
        doc.setContent("- alpha");

        assertThatThrownBy(() -> service.setTaskState("proj-1", "doc-1", 1, true, human))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsALineNumberPastTheEndOfTheDocument() {
        doc.setContent("- [ ] alpha");

        assertThatThrownBy(() -> service.setTaskState("proj-1", "doc-1", 9, true, human))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void rejectsANonPositiveLineNumber() {
        doc.setContent("- [ ] alpha");

        assertThatThrownBy(() -> service.setTaskState("proj-1", "doc-1", 0, true, human))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void treatsNullContentAsEmptyRatherThanCrashing() {
        doc.setContent(null);

        assertThatThrownBy(() -> service.setTaskState("proj-1", "doc-1", 1, true, human))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void recordsAHumanEditorAsAUser() {
        toggle("- [ ] alpha", 1, true);

        assertThat(doc.getUpdatedBy()).isNotNull();
        assertThat(doc.getUpdatedByLabel()).isNull();
    }

    @Test
    void recordsAnAgentEditorAsALabelWithNoUser() {
        doc.setContent("- [ ] alpha");

        service.setTaskState("proj-1", "doc-1", 1, true, DocActor.agent("Agent (run abc12345)"));

        // No user row exists for a run-scoped token, so the label is the only byline the doc can carry.
        assertThat(doc.getUpdatedBy()).isNull();
        assertThat(doc.getUpdatedByLabel()).isEqualTo("Agent (run abc12345)");
    }

    @Test
    void hidesADocBelongingToAnotherProject() {
        doc.setContent("- [ ] alpha");

        // A credential scoped to proj-2 must not reach proj-1's doc by putting its own id in the path.
        assertThatThrownBy(() -> service.setTaskState("proj-2", "doc-1", 1, true, human))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
