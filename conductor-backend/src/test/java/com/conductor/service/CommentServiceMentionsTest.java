package com.conductor.service;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.comment.CommentAgentRequestService;
import com.conductor.comment.CommentSignals;
import com.conductor.comment.Mention;
import com.conductor.comment.MentionParser;
import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What {@code CommentService} does with mentions, agent authors, agent requests and quote-only anchors. */
class CommentServiceMentionsTest {

    CommentRepository comments = mock(CommentRepository.class);
    CommentReplyRepository replies = mock(CommentReplyRepository.class);
    WorkItemRepository workItems = mock(WorkItemRepository.class);
    DocumentRepository documents = mock(DocumentRepository.class);
    ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    ProjectDocRepository projectDocs = mock(ProjectDocRepository.class);
    AgentRepository agents = mock(AgentRepository.class);
    MentionParser parser = mock(MentionParser.class);
    CommentSignals signals = mock(CommentSignals.class);
    CommentAgentRequestService requests = mock(CommentAgentRequestService.class);
    SignalBus bus = mock(SignalBus.class);
    ProjectRepository projects = mock(ProjectRepository.class);

    ProjectSecurityService security = mock(ProjectSecurityService.class);
    CommentService service = new CommentService(comments, replies, workItems, documents, members,
            security, mock(StorageService.class), bus, projects,
            projectDocs, agents, mock(DocumentService.class), mock(ProjectDocService.class), parser, signals, requests,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    User ann = user("u-ann", "Ann");
    User bob = user("u-bob", "Bob");
    Project project = new Project();
    WorkItem workItem = new WorkItem();
    Document document = new Document();
    ProjectDoc projectDoc = new ProjectDoc();
    static final Mention BOB = new Mention(Mention.Kind.USER, "u-bob", "Bob");
    static final Mention BOT = new Mention(Mention.Kind.AGENT, "a-1", "Editor Bot");

    @BeforeEach
    void setUp() {
        project.setId("p1");
        workItem.setId("wi-1");
        workItem.setTitle("PRD");
        workItem.setProject(project);
        document.setId("d-1");
        document.setWorkItem(workItem);
        document.setFilename("spec.md");
        document.setContentType("text/markdown");
        document.setContent("first line\nthe second line\nthe second line again\nthird");
        projectDoc.setId("pd-1");
        projectDoc.setTitle("Notes");
        projectDoc.setProject(project);
        projectDoc.setContent("alpha beta gamma");

        when(projects.existsById(any())).thenReturn(true);
        when(members.existsByProjectIdAndUserId(any(), any())).thenReturn(true);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(workItem));
        when(documents.findByIdAndWorkItemId("d-1", "wi-1")).thenReturn(Optional.of(document));
        when(projectDocs.findById("pd-1")).thenReturn(Optional.of(projectDoc));
        when(comments.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("c-new");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            return c;
        });
        when(replies.save(any(CommentReply.class))).thenAnswer(inv -> {
            CommentReply r = inv.getArgument(0);
            if (r.getId() == null) r.setId("r-new");
            return r;
        });
        when(comments.findDocumentFilenames(anyCollection())).thenReturn(java.util.Collections.singletonList(
                new Object[]{"d-1", "spec.md", null}));
        when(parser.parse(any(), any(), anyBoolean())).thenAnswer(inv ->
                new MentionParser.Parsed(inv.getArgument(1), List.of()));
    }

    private void parsesAs(String stored, Mention... mentions) {
        when(parser.parse(any(), any(), anyBoolean())).thenReturn(new MentionParser.Parsed(stored, List.of(mentions)));
    }

    private static CommentTarget.WorkItemDocument wi() {
        return new CommentTarget.WorkItemDocument("wi-1", "d-1");
    }

    // ---- create ----

    @Test
    void theValidatedBodyIsWhatIsStoredAndTheMentionsArePublished() {
        parsesAs("hi @[Bob](user:u-bob)", BOB);

        service.createThread("p1", wi(), "hi @[x](user:u-bob)", null, null, null, ProjectActor.of(ann));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("hi @[Bob](user:u-bob)");
        verify(signals).mentioned(any(Comment.class), eq(null), eq(List.of(BOB)), eq("hi @[Bob](user:u-bob)"), any());
    }

    @Test
    void aPersonMayMentionAgentsButAMachineMayNot() {
        service.createThread("p1", wi(), "x", null, null, null, ProjectActor.of(ann));
        verify(parser).parse("p1", "x", true);

        service.createThread("p1", wi(), "y", null, null, null, ProjectActor.agent("Editor Bot", "a-1"));
        verify(parser).parse("p1", "y", false);
    }

    @Test
    void anAgentActorIsRecordedWithItsIdAndName() {
        service.createThread("p1", wi(), "x", null, null, null, ProjectActor.agent("Editor Bot", "a-1"));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(saved.capture());
        assertThat(saved.getValue().getAuthor()).isNull();
        assertThat(saved.getValue().getAuthorLabel()).isEqualTo("Editor Bot");
        assertThat(saved.getValue().getAuthorAgentId()).isEqualTo("a-1");
    }

    @Test
    void aProjectDocThreadPublishesTheProjectDocSignalAndAWorkItemThreadDoesNot() {
        service.createThread("p1", new CommentTarget.ProjectDoc("pd-1"), "x", null, null, null, ProjectActor.of(ann));
        verify(signals).projectDocComment(eq(SignalTypes.CONDUCTOR_PROJECT_DOC_COMMENT_ADDED), any(Comment.class), eq("x"), any());

        service.createThread("p1", wi(), "y", null, null, null, ProjectActor.of(ann));
        verify(signals, org.mockito.Mockito.times(1)).projectDocComment(any(), any(), any(), any());
        verify(bus).publish(org.mockito.ArgumentMatchers.argThat(s ->
                s.type().equals(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED)));
    }

    // ---- replies ----

    @Test
    void aReplyPublishesItsOwnMentionsWithTheReplyId() {
        Comment thread = thread(ann, "root");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));
        parsesAs("@[Editor Bot](agent:a-1) please", BOT);

        service.addReply("p1", "c-1", "@[x](agent:a-1) please", ProjectActor.of(bob));

        verify(signals).mentioned(eq(thread), eq("r-new"), eq(List.of(BOT)), eq("@[Editor Bot](agent:a-1) please"), any());
        verify(requests, never()).linkReply(any(), any(), any());
    }

    @Test
    void anAgentsReplyCarriesItsIdAndIsLinkedToTheRequestItAnswers() {
        Comment thread = thread(ann, "root");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));

        CommentReplyView view = service.addReply("p1", "c-1", "Changed: tightened.", ProjectActor.agent("Editor Bot", "a-1"));

        ArgumentCaptor<CommentReply> saved = ArgumentCaptor.forClass(CommentReply.class);
        verify(replies).save(saved.capture());
        assertThat(saved.getValue().getAuthorAgentId()).isEqualTo("a-1");
        assertThat(saved.getValue().getAuthorLabel()).isEqualTo("Editor Bot");
        verify(requests).linkReply("c-1", "a-1", "r-new");
        assertThat(view.authorKind()).isEqualTo("AGENT");
    }

    @Test
    void aProjectDocReplyPublishesTheProjectDocRepliedSignal() {
        Comment thread = thread(ann, "root");
        thread.setWorkItem(null);
        thread.setDocument(null);
        thread.setProjectDoc(projectDoc);
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));

        service.addReply("p1", "c-1", "ok", ProjectActor.of(bob));

        verify(signals).projectDocComment(eq(SignalTypes.CONDUCTOR_PROJECT_DOC_COMMENT_REPLIED), eq(thread), eq("ok"), any());
    }

    // ---- edits ----

    @Test
    void editingAThreadNotifiesOnlyTheMentionsThatAreNew() {
        Comment thread = thread(ann, "hi @[Bob](user:u-bob)");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));
        String edited = "hi @[Bob](user:u-bob) and @[Editor Bot](agent:a-1)";
        parsesAs(edited, BOB, BOT);

        service.updateComment("p1", "c-1", edited, null, ProjectActor.of(ann));

        verify(signals).mentioned(eq(thread), eq(null), eq(List.of(BOT)), eq(edited), any());
        assertThat(thread.getContent()).isEqualTo(edited);
    }

    @Test
    void editingWithoutAddingAMentionPublishesNone() {
        Comment thread = thread(ann, "hi @[Bob](user:u-bob)");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));
        parsesAs("hello @[Bob](user:u-bob)", BOB);

        service.updateComment("p1", "c-1", "hello @[Bob](user:u-bob)", null, ProjectActor.of(ann));

        verify(signals).mentioned(any(), eq(null), eq(List.of()), any(), any());
    }

    @Test
    void editingAReplyNotifiesOnlyNewMentions() {
        Comment thread = thread(ann, "root");
        CommentReply reply = new CommentReply();
        reply.setId("r-1");
        reply.setComment(thread);
        reply.setAuthor(bob);
        reply.setContent("cc @[Ann](user:u-ann)");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));
        when(replies.findById("r-1")).thenReturn(Optional.of(reply));
        Mention ann = new Mention(Mention.Kind.USER, "u-ann", "Ann");
        parsesAs("cc @[Ann](user:u-ann) and @[Editor Bot](agent:a-1)", ann, BOT);

        service.updateReply("p1", "c-1", "r-1", "cc @[Ann](user:u-ann) and @[Editor Bot](agent:a-1)", ProjectActor.of(bob));

        verify(signals).mentioned(eq(thread), eq("r-1"), eq(List.of(BOT)), any(), any());
    }

    // ---- views ----

    @Test
    void responsesCarryMentionsFromTheBodyAndTheAgentRequestsWithTheirStatus() {
        Comment thread = thread(ann, "ask @[Editor Bot](agent:a-1)");
        CommentReply reply = new CommentReply();
        reply.setId("r-1");
        reply.setComment(thread);
        reply.setAuthor(bob);
        reply.setContent("cc @[Ann](user:u-ann)");
        reply.setCreatedAt(OffsetDateTime.now());
        when(comments.findListByWorkItemIdAndDocumentId("wi-1", "d-1")).thenReturn(List.of(thread));
        when(replies.findAllByCommentIdIn(anyCollection())).thenReturn(List.of(reply));
        OffsetDateTime at = OffsetDateTime.now();
        when(requests.statesFor(anyCollection())).thenReturn(List.of(
                new CommentAgentRequestService.RequestState("c-1", "a-1", CommentAgentRequestService.Status.FAILED,
                        "Set up an AI provider in Settings → AI Providers so Editor Bot can run.", at)));
        Agent bot = new Agent();
        bot.setId("a-1");
        bot.setName("Editor Bot");
        bot.setSlug("editor-bot");
        bot.setAvatarEmoji("🤖");
        bot.setAvatarColor("#123456");
        when(agents.findAllById(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.contains("a-1") ? List.of(bot) : List.of();
        });

        CommentWithRepliesView view = service.listThreads("p1", wi(), CommentService.StatusFilter.ALL,
                ProjectActor.of(ann)).get(0);

        assertThat(view.mentions()).containsExactly(BOT);
        assertThat(view.replies().get(0).mentions()).extracting(Mention::id).containsExactly("u-ann");
        assertThat(view.agentRequests()).hasSize(1);
        var req = view.agentRequests().get(0);
        assertThat(req.agentId()).isEqualTo("a-1");
        assertThat(req.agentName()).isEqualTo("Editor Bot");
        assertThat(req.avatarEmoji()).isEqualTo("🤖");
        assertThat(req.status()).isEqualTo("FAILED");
        assertThat(req.error()).contains("AI Providers");
        assertThat(req.createdAt()).isEqualTo(at);
    }

    // ---- quote-only anchors ----

    @Test
    void aQuoteOnlyAnchorGetsPrefixAndSuffixFromTheDocument() {
        CommentWithRepliesView view = service.createThread("p1", wi(), "check", null,
                new CommentAnchor("third", null, null, null, null), null, ProjectActor.agent("Agent"));

        assertThat(view.anchor().exact()).isEqualTo("third");
        assertThat(view.anchor().prefix()).endsWith("again ").hasSizeLessThanOrEqualTo(32);
        assertThat(view.anchor().suffix()).isNotNull();
        assertThat(view.quotedText()).isEqualTo("third");
    }

    @Test
    void aRepeatedQuoteAnchorsToTheFirstOccurrence() {
        CommentWithRepliesView view = service.createThread("p1", wi(), "check", null,
                new CommentAnchor("second line", null, null, null, null), null, ProjectActor.of(ann));

        assertThat(view.anchor().prefix()).isEqualTo("first line the ");
        assertThat(view.anchor().suffix()).startsWith(" the second line again");
    }

    @Test
    void aQuoteThatIsNotInTheDocumentIsRefused() {
        assertThatThrownBy(() -> service.createThread("p1", wi(), "x", null,
                new CommentAnchor("not there", null, null, null, null), null, ProjectActor.of(ann)))
                .isInstanceOf(BusinessException.class).hasMessage("That text isn't in the document");
        verify(comments, never()).save(any());
    }

    @Test
    void anAnchorWithContextAlreadyIsKeptAsSent() {
        CommentWithRepliesView view = service.createThread("p1", wi(), "x", null,
                new CommentAnchor("zzz", "pre ", " suf", "sec", "Section"), null, ProjectActor.of(ann));

        assertThat(view.anchor()).isEqualTo(new CommentAnchor("zzz", "pre ", " suf", "sec", "Section"));
    }

    @Test
    void theOriginalWorkItemRouteDoesNotGuessContextForAnAnchor() {
        document.setContentType("text/html");
        document.setFilename("page.html");
        document.setContent("<p>hello</p>");

        Comment saved = service.createComment("p1", "wi-1", "d-1", "x", null,
                new CommentAnchor("not in the page", null, null, null, null), null, ProjectActor.of(ann)).comment();

        assertThat(saved.getAnchorExact()).isEqualTo("not in the page");
        assertThat(saved.getAnchorPrefix()).isNull();
    }

    // ---- who is the author of a machine-written row ----

    private static final ProjectActor BOT_A = ProjectActor.agent("Bot A", "a-A");
    private static final ProjectActor BOT_B = ProjectActor.agent("Bot B", "a-B");

    private Comment machineThread(String label, String agentId) {
        Comment c = thread(null, "bot text");
        c.setAuthorLabel(label);
        c.setAuthorAgentId(agentId);
        return c;
    }

    private CommentReply machineReply(Comment thread, String label, String agentId) {
        CommentReply r = new CommentReply();
        r.setId("r-1");
        r.setComment(thread);
        r.setAuthorLabel(label);
        r.setAuthorAgentId(agentId);
        r.setContent("bot reply");
        when(replies.findById("r-1")).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void anAgentCannotEditOrDeleteAnotherAgentsReplyButCanEditAndDeleteItsOwn() {
        Comment thread = thread(ann, "root");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(thread));
        machineReply(thread, "Bot B", "a-B");

        assertThatThrownBy(() -> service.updateReply("p1", "c-1", "r-1", "mine now", BOT_A))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.deleteReply("p1", "c-1", "r-1", BOT_A))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        verify(replies, never()).delete(any());

        service.updateReply("p1", "c-1", "r-1", "edited by its author", BOT_B);
        service.deleteReply("p1", "c-1", "r-1", BOT_B);
        verify(replies).delete(any(CommentReply.class));
    }

    @Test
    void aMachineActorIsNeverTheAuthorOfAPersonsRowOrOfARowWithoutAMatchingIdentity() {
        Comment byPerson = thread(ann, "ann's");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(byPerson));
        assertThatThrownBy(() -> service.deleteComment("p1", "c-1", BOT_A))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateComment("p1", "c-1", "x", null, BOT_A))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);

        // A project API key (a label, no agent) cannot touch an agent's comment, whatever its label.
        Comment byAgent = machineThread("Bot A", "a-A");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(byAgent));
        assertThatThrownBy(() -> service.deleteComment("p1", "c-1", ProjectActor.agent("API key")))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);

        // A row with neither a label nor an agent id belongs to no machine.
        Comment anonymous = machineThread(null, null);
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(anonymous));
        assertThatThrownBy(() -> service.deleteComment("p1", "c-1", ProjectActor.agent("API key")))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        verify(comments, never()).delete(any());
    }

    @Test
    void aMachineWithTheSameLabelAndNoAgentIdMayDeleteItsOwnLabelledComment() {
        Comment byKey = machineThread("API key", null);
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(byKey));

        service.deleteComment("p1", "c-1", ProjectActor.agent("API key"));

        verify(comments).delete(byKey);
    }

    @Test
    void anAgentMayDeleteItsOwnCommentAndNotAnotherAgents() {
        Comment byA = machineThread("Bot A", "a-A");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(byA));
        assertThatThrownBy(() -> service.deleteComment("p1", "c-1", BOT_B))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);

        service.deleteComment("p1", "c-1", BOT_A);
        verify(comments).delete(byA);
    }

    @Test
    void peopleKeepTheirRules() {
        Comment byAnn = thread(ann, "ann's");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(byAnn));

        // Author edits; a non-author non-editor may not edit or delete; an editor may delete.
        service.updateComment("p1", "c-1", "edited", null, ProjectActor.of(ann));
        assertThatThrownBy(() -> service.updateComment("p1", "c-1", "x", null, ProjectActor.of(bob)))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.deleteComment("p1", "c-1", ProjectActor.of(bob)))
                .isInstanceOf(com.conductor.exception.ForbiddenException.class);
        when(security.isEditor("p1", "u-bob")).thenReturn(true);
        service.deleteComment("p1", "c-1", ProjectActor.of(bob));
        verify(comments).delete(byAnn);
    }

    // ---- the v1 project-doc shim notifies like the v2 create ----

    @Test
    void theV1ProjectDocCreateEmitsTheCommentAddedAndMentionSignalsLikeV2() {
        parsesAs("hi @[Bob](user:u-bob)", BOB);

        CommentWithRepliesView view = service.createProjectDocComment("p1", "pd-1", ProjectActor.of(ann),
                "hi @[x](user:u-bob)", 2, "the line");

        verify(signals).projectDocComment(eq(SignalTypes.CONDUCTOR_PROJECT_DOC_COMMENT_ADDED), any(Comment.class),
                eq("hi @[Bob](user:u-bob)"), any());
        verify(signals).mentioned(any(Comment.class), eq(null), eq(List.of(BOB)), eq("hi @[Bob](user:u-bob)"), any());
        assertThat(view.content()).isEqualTo("hi @[Bob](user:u-bob)");
        assertThat(view.lineNumber()).isEqualTo(2);
        assertThat(view.quotedText()).isEqualTo("the line");
    }

    // ---- fixtures ----

    private Comment thread(User author, String content) {
        Comment c = new Comment();
        c.setId("c-1");
        c.setWorkItem(workItem);
        c.setDocument(document);
        c.setAuthor(author);
        c.setContent(content);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }

    private static User user(String id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setEmail(id + "@x.test");
        return u;
    }
}
