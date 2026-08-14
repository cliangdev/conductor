package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectDocService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.model.TriggersSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link CoordinatorToolProvider}: one mocked collaborator per composed
 *  bounded context, a real {@link ObjectMapper}, matching {@code AgentExecutionServiceTest}'s idiom. */
class CoordinatorToolProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");

    private WorkItemRepository workItemRepository;
    private WorkItemService workItemService;
    private ProjectRepository projectRepository;
    private WorkflowService workflowService;
    private WorkflowTriggerService workflowTriggerService;
    private WorkflowYamlParser workflowYamlParser;
    private WorkflowRunRepository workflowRunRepository;
    private WorkflowJobRunRepository workflowJobRunRepository;
    private WorkflowStepRunRepository workflowStepRunRepository;
    private AgentRepository agentRepository;
    private ProjectDocService projectDocService;
    private AgentExecutionService agentExecutionService;
    private CoordinatorToolProvider provider;

    @BeforeEach
    void setUp() {
        workItemRepository = mock(WorkItemRepository.class);
        workItemService = mock(WorkItemService.class);
        projectRepository = mock(ProjectRepository.class);
        workflowService = mock(WorkflowService.class);
        workflowTriggerService = mock(WorkflowTriggerService.class);
        workflowYamlParser = mock(WorkflowYamlParser.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        workflowJobRunRepository = mock(WorkflowJobRunRepository.class);
        workflowStepRunRepository = mock(WorkflowStepRunRepository.class);
        agentRepository = mock(AgentRepository.class);
        projectDocService = mock(ProjectDocService.class);
        agentExecutionService = mock(AgentExecutionService.class);

        provider = new CoordinatorToolProvider(workItemRepository, workItemService, projectRepository,
                workflowService, workflowTriggerService, workflowYamlParser, workflowRunRepository,
                workflowJobRunRepository, workflowStepRunRepository, agentRepository, projectDocService,
                agentExecutionService, MAPPER);

        when(projectRepository.findById("p1")).thenReturn(Optional.of(project("p1", "COND")));
    }

    private Project project(String id, String key) {
        Project p = new Project();
        p.setId(id);
        p.setKey(key);
        return p;
    }

    private AgentTool tool(String bareName) {
        return provider.available("p1").stream()
                .filter(t -> t.name().equals(bareName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + bareName));
    }

    // ---- sourceId / discovery / schema plumbing ----

    @Test
    void sourceIdIsCoordinator() {
        assertThat(provider.sourceId()).isEqualTo("coordinator");
    }

    @Test
    void offersAllTenTools() {
        assertThat(provider.available("p1")).extracting(AgentTool::name).containsExactlyInAnyOrder(
                "create_work_item", "list_work_items", "get_work_item", "list_workflows", "dispatch_workflow",
                "get_workflow_run", "list_agents", "search_project_docs", "read_project_doc", "ask_agent");
    }

    @Test
    void everyToolHasAParsableNonEmptyInputSchemaAndNonBlankDescription() throws Exception {
        for (AgentTool t : provider.available("p1")) {
            assertThat(t.id()).isEqualTo("coordinator:" + t.name());
            assertThat(t.description()).isNotBlank();
            String json = MAPPER.writeValueAsString(t.inputSchema());
            Map<String, Object> parsed = MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            assertThat(parsed.get("type")).isEqualTo("object");
            assertThat(parsed).containsKey("properties");
        }
    }

    @Test
    void claudeCodeToolNameMapsOnlyTheSevenMcpOverlappingTools() {
        for (String bare : List.of("create_work_item", "list_work_items", "get_work_item", "list_workflows",
                "dispatch_workflow", "get_workflow_run", "list_agents")) {
            assertThat(provider.claudeCodeToolName("coordinator:" + bare))
                    .contains("mcp__conductor__" + bare);
        }
        for (String bare : List.of("search_project_docs", "read_project_doc", "ask_agent")) {
            assertThat(provider.claudeCodeToolName("coordinator:" + bare)).isEmpty();
        }
    }

    @Test
    void resolveFindsAnOfferedToolAndReturnsEmptyForAnUnknownId() {
        assertThat(provider.resolve("p1", "coordinator:list_agents")).isPresent();
        assertThat(provider.resolve("p1", "coordinator:not_a_tool")).isEmpty();
    }

    // ---- create_work_item ----

    @Test
    void createWorkItemHappyPathAttributesToTheCallingAgentsSlug() throws Exception {
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agent("agent-1", "ceo", "CEO", "ACTIVE", true)));
        WorkItem created = workItem("w9", 5, "New Title", "DRAFT", "BUG");
        created.setWorkflow("ENGINEERING");
        when(workItemService.createWorkItem(eq("p1"), eq("BUG"), eq("New Title"), eq("desc"), eq("ENGINEERING"),
                eq(ProjectActor.agent("Agent (ceo)"))))
                .thenReturn(created);

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "New Title", "description", "desc"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("id")).isEqualTo("w9");
        assertThat(row.get("displayId")).isEqualTo("COND-5");
        assertThat(row.get("workflow")).isEqualTo("ENGINEERING");
    }

    @Test
    void createWorkItemFallsBackToAgentIdWhenCallingAgentRowIsGone() throws Exception {
        when(agentRepository.findById("agent-1")).thenReturn(Optional.empty());
        WorkItem created = workItem("w9", 1, "T", "DRAFT", "BUG");
        when(workItemService.createWorkItem(eq("p1"), eq("BUG"), eq("T"), any(), eq("ENGINEERING"),
                eq(ProjectActor.agent("Agent (agent-1)"))))
                .thenReturn(created);

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "T"), CTX);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void createWorkItemMissingRequiredFieldsReturnsError() {
        ToolResult result = tool("create_work_item").invoke(Map.of("type", "BUG"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void createWorkItemServiceExceptionBecomesToolError() {
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agent("agent-1", "ceo", "CEO", "ACTIVE", true)));
        when(workItemService.createWorkItem(anyString(), anyString(), anyString(), any(), anyString(), any(ProjectActor.class)))
                .thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "T"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- list_work_items ----

    private WorkItem workItem(String id, int seq, String title, String status, String type) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setSequenceNumber(seq);
        w.setTitle(title);
        w.setCurrentStatus(status);
        w.setType(type);
        return w;
    }

    @Test
    void listWorkItemsHappyPathReturnsCompactRows() throws Exception {
        when(workItemRepository.findByProjectFiltered("p1", null, null, null))
                .thenReturn(List.of(workItem("w1", 1, "Fix bug", "OPEN", "BUG")));

        ToolResult result = tool("list_work_items").invoke(Map.of(), CTX);

        assertThat(result.ok()).isTrue();
        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        assertThat(rows).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("id")).isEqualTo("w1");
        assertThat(row.get("displayId")).isEqualTo("COND-1");
        assertThat(row.get("title")).isEqualTo("Fix bug");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("type")).isEqualTo("BUG");
    }

    @Test
    void listWorkItemsCapsAtFifty() throws Exception {
        List<WorkItem> many = new java.util.ArrayList<>();
        for (int i = 0; i < 75; i++) {
            many.add(workItem("w" + i, i, "t" + i, "OPEN", "TASK"));
        }
        when(workItemRepository.findByProjectFiltered("p1", null, null, null)).thenReturn(many);

        ToolResult result = tool("list_work_items").invoke(Map.of("limit", 200), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        assertThat(rows).hasSize(50);
    }

    @Test
    void listWorkItemsRepositoryExceptionBecomesToolError() {
        when(workItemRepository.findByProjectFiltered(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        ToolResult result = tool("list_work_items").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("list_work_items failed");
    }

    // ---- get_work_item ----

    @Test
    void getWorkItemById() throws Exception {
        WorkItem w = workItem("w1", 7, "Title", "OPEN", "BUG");
        w.setDescription("desc");
        w.setWorkflow("ENGINEERING");
        w.setProject(project("p1", "COND"));
        when(workItemRepository.findByIdWithProjectAndAssignee("w1")).thenReturn(Optional.of(w));

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "w1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("displayId")).isEqualTo("COND-7");
        assertThat(row.get("description")).isEqualTo("desc");
        assertThat(row.get("workflow")).isEqualTo("ENGINEERING");
    }

    @Test
    void getWorkItemByDisplayIdFallsBackToSequenceLookup() throws Exception {
        when(workItemRepository.findByIdWithProjectAndAssignee("COND-7")).thenReturn(Optional.empty());
        WorkItem w = workItem("w1", 7, "Title", "OPEN", "BUG");
        when(workItemRepository.findByProjectIdAndSequenceNumber("p1", 7)).thenReturn(Optional.of(w));

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "COND-7"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("id")).isEqualTo("w1");
    }

    @Test
    void getWorkItemNotFoundReturnsError() {
        when(workItemRepository.findByIdWithProjectAndAssignee("nope")).thenReturn(Optional.empty());

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "nope"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void getWorkItemFromAnotherProjectIsTreatedAsNotFound() {
        WorkItem w = workItem("w1", 1, "Title", "OPEN", "BUG");
        w.setProject(project("other-project", "OTH"));
        when(workItemRepository.findByIdWithProjectAndAssignee("w1")).thenReturn(Optional.of(w));

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "w1"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- list_workflows ----

    private WorkflowDefinition workflowDef(String id, String name, boolean enabled, boolean lifecycle) throws Exception {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setName(name);
        def.setEnabled(enabled);
        if (lifecycle) {
            def.setDefinition(MAPPER.readTree("{\"id\":\"wf\"}"));
        }
        return def;
    }

    @Test
    void listWorkflowsHappyPath() throws Exception {
        when(workflowService.listWorkflows(eq("p1"), any(), any(), any()))
                .thenReturn(List.of(workflowDef("wf1", "My Workflow", true, false)));

        ToolResult result = tool("list_workflows").invoke(Map.of(), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("name")).isEqualTo("My Workflow");
        assertThat(row.get("kind")).isEqualTo("AUTOMATION");
        assertThat(row.get("enabled")).isEqualTo(true);
    }

    @Test
    void listWorkflowsServiceExceptionBecomesToolError() {
        when(workflowService.listWorkflows(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("list_workflows").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- dispatch_workflow ----

    private TriggersSpec triggers(boolean allowsManual) {
        return new TriggersSpec(null, null, List.of(), List.of(), allowsManual, Map.of());
    }

    private WorkflowSpec spec(boolean allowsManual) {
        return new WorkflowSpec("wf", triggers(allowsManual), null, Map.of(), Map.of());
    }

    @Test
    void dispatchWorkflowHappyPath() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true, false);
        def.setYaml("on: {workflow_dispatch: {}}");
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        when(workflowYamlParser.parse(def.getYaml())).thenReturn(spec(true));
        WorkflowRun run = new WorkflowRun();
        run.setId("run-9");
        run.setStatus(WorkflowRunStatus.PENDING);
        when(workflowTriggerService.triggerManual(eq(def), eq("agent:agent-1"), any())).thenReturn(run);

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("runId")).isEqualTo("run-9");
    }

    @Test
    void dispatchWorkflowRefusesWhenDisabled() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", false, false);
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("disabled");
    }

    @Test
    void dispatchWorkflowRefusesWhenManualDispatchNotAllowed() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true, false);
        def.setYaml("on: {}");
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        when(workflowYamlParser.parse(def.getYaml())).thenReturn(spec(false));

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("managed automatically");
    }

    @Test
    void dispatchWorkflowServiceExceptionBecomesToolError() {
        when(workflowService.getWorkflow(anyString(), anyString()))
                .thenThrow(new EntityNotFoundException("not found"));

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "nope"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- get_workflow_run ----

    @Test
    void getWorkflowRunHappyPathReturnsCompactStepSummary() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true, false);
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        run.setStatus(WorkflowRunStatus.RUNNING);
        when(workflowRunRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        WorkflowJobRun job = new WorkflowJobRun();
        job.setId("job-1");
        job.setJobId("main");
        job.setStatus(WorkflowJobStatus.RUNNING);
        when(workflowJobRunRepository.findByRunId("run-1")).thenReturn(List.of(job));

        WorkflowStepRun step = new WorkflowStepRun();
        step.setStepName("build");
        step.setStatus(WorkflowStepStatus.SUCCESS);
        when(workflowStepRunRepository.findByJobRunIdOrderByStartedAtAscIdAsc("job-1")).thenReturn(List.of(step));

        ToolResult result = tool("get_workflow_run").invoke(Map.of("workflowId", "wf1", "runId", "run-1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        List<?> jobs = (List<?>) row.get("jobs");
        assertThat(jobs).hasSize(1);
        Map<?, ?> jobRow = (Map<?, ?>) jobs.get(0);
        List<?> steps = (List<?>) jobRow.get("steps");
        assertThat(((Map<?, ?>) steps.get(0)).get("stepName")).isEqualTo("build");
    }

    @Test
    void getWorkflowRunBelongingToAnotherWorkflowIsNotFound() {
        WorkflowDefinition def = workflowDef2("wf1");
        WorkflowDefinition otherDef = workflowDef2("wf2");
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(otherDef);
        when(workflowRunRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        ToolResult result = tool("get_workflow_run").invoke(Map.of("workflowId", "wf1", "runId", "run-1"), CTX);

        assertThat(result.ok()).isFalse();
    }

    private WorkflowDefinition workflowDef2(String id) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setName(id);
        def.setEnabled(true);
        return def;
    }

    // ---- list_agents ----

    private Agent agent(String id, String slug, String name, String state, boolean addressable) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId("p1");
        a.setSlug(slug);
        a.setName(name);
        a.setState(state);
        a.setProvider("fake");
        a.setConfigJson(addressable ? "{\"addressable\":true}" : "{}");
        a.setToolIds("[]");
        return a;
    }

    @Test
    void listAgentsHappyPath() throws Exception {
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of(agent("a1", "ceo", "CEO", "ACTIVE", true)));

        ToolResult result = tool("list_agents").invoke(Map.of(), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("slug")).isEqualTo("ceo");
        assertThat(row.get("addressable")).isEqualTo(true);
    }

    @Test
    void listAgentsRepositoryExceptionBecomesToolError() {
        when(agentRepository.findByProjectId(anyString())).thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("list_agents").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- search_project_docs / read_project_doc ----

    private ProjectDoc doc(String id, String title, String content) {
        ProjectDoc d = new ProjectDoc();
        d.setId(id);
        d.setTitle(title);
        d.setContent(content);
        return d;
    }

    @Test
    void searchProjectDocsHappyPath() throws Exception {
        when(projectDocService.searchDocs("p1", "hello")).thenReturn(List.of(doc("d1", "Doc One", "hello world")));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "hello"), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertThat(row.get("title")).isEqualTo("Doc One");
        assertThat((String) row.get("snippet")).contains("hello");
    }

    @Test
    void searchProjectDocsRespectsLimit() throws Exception {
        when(projectDocService.searchDocs("p1", "x")).thenReturn(
                List.of(doc("d1", "a", "x"), doc("d2", "b", "x"), doc("d3", "c", "x")));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "x", "limit", 2), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        assertThat(rows).hasSize(2);
    }

    @Test
    void searchProjectDocsServiceExceptionBecomesToolError() {
        when(projectDocService.searchDocs(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "x"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void readProjectDocHappyPath() throws Exception {
        when(projectDocService.getDoc("p1", "d1")).thenReturn(doc("d1", "Doc One", "full content"));

        ToolResult result = tool("read_project_doc").invoke(Map.of("id", "d1"), CTX);

        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("content")).isEqualTo("full content");
    }

    @Test
    void readProjectDocNotFoundBecomesToolError() {
        when(projectDocService.getDoc("p1", "missing")).thenThrow(new EntityNotFoundException("Document not found"));

        ToolResult result = tool("read_project_doc").invoke(Map.of("id", "missing"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- ask_agent ----

    @Test
    void askAgentHappyPath() throws Exception {
        Agent target = agent("a2", "researcher", "Researcher", "ACTIVE", false);
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of(target));
        when(agentExecutionService.run(eq("p1"), eq("a2"), eq("What's up?"), any(), eq(null)))
                .thenReturn(new AgentRunResult("run-5", "All good.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher", "task", "What's up?"), CTX);

        assertThat(result.ok()).isTrue();
        Map<?, ?> row = MAPPER.readValue(result.payload(), Map.class);
        assertThat(row.get("output")).isEqualTo("All good.");
        assertThat(row.get("runId")).isEqualTo("run-5");
    }

    @Test
    void askAgentRefusesTargetingItself() {
        Agent self = agent("agent-1", "self-slug", "Self", "ACTIVE", false);
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of(self));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "self-slug", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("itself");
    }

    @Test
    void askAgentRefusesCoordinatorChainTarget() {
        Agent chainable = agent("a3", "chainer", "Chainer", "ACTIVE", false);
        chainable.setToolIds("[\"coordinator:ask_agent\"]");
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of(chainable));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "chainer", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("coordinator chains");
    }

    @Test
    void askAgentTargetNotFoundBecomesToolError() {
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of());

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "nonexistent", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void askAgentFailedRunResultBecomesToolErrorCarryingRunId() {
        Agent target = agent("a2", "researcher", "Researcher", "ACTIVE", false);
        when(agentRepository.findByProjectId("p1")).thenReturn(List.of(target));
        when(agentExecutionService.run(eq("p1"), eq("a2"), anyString(), any(), eq(null)))
                .thenReturn(new AgentRunResult("run-6", "", null, TokenUsage.ZERO, AgentRun.Status.FAILED.name()));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("run-6");
    }
}
