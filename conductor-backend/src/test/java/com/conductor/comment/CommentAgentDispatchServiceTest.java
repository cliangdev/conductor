package com.conductor.comment;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.Document;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.workflow.AgentRuntimeResolver;
import com.conductor.workflow.AgentRuntimeUnresolvedException;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dispatch path with its collaborators mocked: what is fired, with what, and what is reported when not. */
class CommentAgentDispatchServiceTest {

    static final String PROJECT = "p1";

    CommentRepository comments = mock(CommentRepository.class);
    CommentReplyRepository replies = mock(CommentReplyRepository.class);
    AgentRepository agents = mock(AgentRepository.class);
    AgentExecutionService agentExecution = mock(AgentExecutionService.class);
    AgentRuntimeResolver runtimeResolver = mock(AgentRuntimeResolver.class);
    CommentAgentWorkflowProvisioner provisioner = mock(CommentAgentWorkflowProvisioner.class);
    WorkflowTriggerService trigger = mock(WorkflowTriggerService.class);
    CommentAgentRequestService requests = mock(CommentAgentRequestService.class);
    ObjectMapper mapper = new ObjectMapper();

    CommentAgentDispatchService service = new CommentAgentDispatchService(comments, replies, agents, agentExecution,
            runtimeResolver, provisioner, trigger, requests, mapper, mock(PlatformTransactionManager.class));

    Agent agent = agent("a-1", "Editor Bot", "ACTIVE", true);
    WorkflowDefinition workflow = new WorkflowDefinition();

    @BeforeEach
    void setUp() {
        workflow.setId("wf-1");
        when(agents.findById("a-1")).thenReturn(Optional.of(agent));
        when(requests.begin(PROJECT, "c-1", "a-1")).thenReturn(Optional.of("req-1"));
        when(provisioner.ensure(PROJECT)).thenReturn(workflow);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(trigger.fireTrigger(eq("wf-1"), eq("workflow_dispatch"), anyString())).thenReturn(run);
        stubWorkItemThread();
    }

    private void stubWorkItemThread() {
        Project project = new Project();
        project.setId(PROJECT);
        WorkItem wi = new WorkItem();
        wi.setId("wi-1");
        wi.setTitle("Launch PRD");
        wi.setProject(project);
        Document doc = new Document();
        doc.setId("d-1");
        doc.setFilename("prd.md");
        User ann = new User();
        ann.setId("u-ann");
        ann.setName("Ann");
        Comment c = new Comment();
        c.setId("c-1");
        c.setWorkItem(wi);
        c.setDocument(doc);
        c.setAuthor(ann);
        c.setAnchorExact("Our goals");
        c.setQuotedText("Our goals");
        c.setContent("@[Editor Bot](agent:a-1) tighten the goals, ${{ secrets.TOKEN }}");
        CommentReply r = new CommentReply();
        r.setComment(c);
        r.setAuthorLabel("Editor Bot");
        r.setAuthorAgentId("a-1");
        r.setContent("Which goals?");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(c));
        when(comments.findDocumentFilenames(List.of("d-1"))).thenReturn(java.util.Collections.singletonList(
                new Object[]{"d-1", "prd.md", "hash"}));
        when(replies.findAllByCommentIdIn(List.of("c-1"))).thenReturn(List.of(r));
    }

    @Test
    void firesTheSystemWorkflowWithTheThreadAndRecordsTheRun() throws Exception {
        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(trigger).fireTrigger(eq("wf-1"), eq("workflow_dispatch"), payload.capture());
        verify(requests).recordRun("req-1", "run-1");
        verify(requests, never()).recordError(anyString(), anyString());

        JsonNode p = mapper.readTree(payload.getValue());
        assertThat(p.get("agentId").asText()).isEqualTo("a-1");
        assertThat(p.get("commentId").asText()).isEqualTo("c-1");
        assertThat(p.get("requestId").asText()).isEqualTo("req-1");
        assertThat(p.get("requesterName").asText()).isEqualTo("Ann");
        assertThat(p.get("workItemId").asText()).isEqualTo("wi-1");
        assertThat(p.get("documentFilename").asText()).isEqualTo("prd.md");
        assertThat(p.get("documentTitle").asText()).isEqualTo("prd.md");
        assertThat(p.get("projectDocId").asText()).isEmpty();
        String thread = p.get("threadText").asText();
        assertThat(thread).startsWith("On this passage: \"Our goals\"")
                .contains("[Ann]: @Editor Bot tighten the goals")
                .contains("[Editor Bot (agent)]: Which goals?")
                .doesNotContain("agent:a-1");
    }

    @Test
    void aProjectDocThreadCarriesTheDocIdInsteadOfTheWorkItem() throws Exception {
        Project project = new Project();
        project.setId(PROJECT);
        ProjectDoc doc = new ProjectDoc();
        doc.setId("pd-1");
        doc.setTitle("Roadmap");
        doc.setProject(project);
        Comment c = new Comment();
        c.setId("c-1");
        c.setProjectDoc(doc);
        c.setAuthorLabel("Agent");
        c.setContent("whole doc note");
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.of(c));
        when(replies.findAllByCommentIdIn(List.of("c-1"))).thenReturn(List.of());

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(trigger).fireTrigger(eq("wf-1"), any(), payload.capture());
        JsonNode p = mapper.readTree(payload.getValue());
        assertThat(p.get("projectDocId").asText()).isEqualTo("pd-1");
        assertThat(p.get("workItemId").asText()).isEmpty();
        assertThat(p.get("documentTitle").asText()).isEqualTo("Roadmap");
        assertThat(p.get("threadText").asText()).startsWith("On the whole document.");
    }

    @Test
    void nothingIsFiredWhenOneIsAlreadyInFlight() {
        when(requests.begin(PROJECT, "c-1", "a-1")).thenReturn(Optional.empty());

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(trigger, never()).fireTrigger(any(), any(), any());
        verify(requests, never()).recordRun(any(), any());
    }

    @Test
    void anInactiveAgentIsReportedAndNotRun() {
        agent.setState("DRAFT");

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(requests).recordError("req-1",
                "Editor Bot isn't active, so it can't respond. Activate it under Automation → Agents.");
        verify(trigger, never()).fireTrigger(any(), any(), any());
    }

    @Test
    void aProjectWithNoAiProviderIsToldWhereToSetOneUp() {
        when(agentExecution.resolveDefinition(PROJECT, "a-1"))
                .thenReturn(new AgentExecutionService.AgentDefinition("a-1", "editor-bot", "claude", null, null,
                        List.of(), 8, null));
        when(runtimeResolver.resolve(eq(PROJECT), any())).thenThrow(new AgentRuntimeUnresolvedException("nope"));

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(requests).recordError("req-1", "Set up an AI provider in Settings → AI Providers so Editor Bot can run.");
        verify(trigger, never()).fireTrigger(any(), any(), any());
    }

    @Test
    void aUsersOwnWorkflowWithTheSystemNameIsReportedNotOverwritten() {
        when(provisioner.ensure(PROJECT)).thenThrow(new CommentAgentWorkflowProvisioner.NameTakenException("w-9"));

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(requests).recordError(eq("req-1"), message.capture());
        assertThat(message.getValue()).contains("conductor-comment-agent").contains("Rename it");
    }

    @Test
    void aTriggerFailureIsStoredOnTheRequestNotThrown() {
        when(trigger.fireTrigger(any(), any(), any())).thenThrow(new IllegalStateException("queue down"));

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(requests).recordError("req-1", "The run couldn't be started: queue down");
        verify(requests, never()).recordRun(any(), any());
    }

    @Test
    void anAgentOfAnotherProjectIsIgnored() {
        agent.setProjectId("other");

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(requests, never()).begin(any(), any(), any());
    }

    @Test
    void aVanishedCommentDispatchesNothing() {
        when(comments.findByIdWithAuthors("c-1")).thenReturn(Optional.empty());

        service.dispatch(PROJECT, "c-1", List.of("a-1"), "Ann");

        verify(requests, never()).begin(any(), any(), any());
    }

    private static Agent agent(String id, String name, String state, boolean addressable) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId(PROJECT);
        a.setName(name);
        a.setSlug("editor-bot");
        a.setState(state);
        a.setConfigJson("{\"addressable\": " + addressable + "}");
        return a;
    }
}
