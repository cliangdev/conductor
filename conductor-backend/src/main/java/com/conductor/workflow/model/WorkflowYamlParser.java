package com.conductor.workflow.model;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single parser for automation-workflow YAML — the only class in {@code com.conductor.workflow}
 * that imports SnakeYAML directly. Turns the raw document into a {@link WorkflowSpec}. Parsing is
 * deliberately lenient: a structurally broken document (bad YAML syntax, an empty document) throws
 * {@link WorkflowYamlException}, but a well-formed document with missing/unknown fields never
 * throws — unknown keys are preserved on each record's {@code raw} map, and deciding whether a
 * missing field is an ERROR is {@code WorkflowValidator}'s job, not the parser's.
 */
@Component
public class WorkflowYamlParser {

    private static final String CONDUCTOR_STATUS_CHANGED = "conductor.work_item.status_changed";
    private static final String GITHUB_PULL_REQUEST = "github.pull_request";

    public WorkflowSpec parse(String yaml) {
        // Guard explicitly rather than letting SnakeYAML NPE on a null Reader: WorkflowDefinition
        // rows for a LIFECYCLE (statechart) workflow have a null `yaml` column (they use `definition`
        // instead), and callers that scan every workflow in a project (e.g. WorkflowTriggerService)
        // must be able to skip those cleanly via the same WorkflowYamlException path as malformed YAML.
        if (yaml == null) {
            throw new WorkflowYamlException("Workflow YAML is empty");
        }
        Map<String, Object> parsed;
        try {
            parsed = new Yaml().load(yaml);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : 0;
            int col = e.getProblemMark() != null ? e.getProblemMark().getColumn() + 1 : 0;
            throw new WorkflowYamlException("[" + line + ":" + col + "] YAML parse error: " + e.getProblem(), e);
        } catch (YAMLException e) {
            throw new WorkflowYamlException("[0:0] YAML parse error: " + e.getMessage(), e);
        }

        if (parsed == null) {
            throw new WorkflowYamlException("Workflow YAML is empty");
        }

        String name = textOrNull(parsed.get("name"));
        String concurrency = textOrNull(parsed.get("concurrency"));
        // SnakeYAML 1.1 parses a bare `on:` key as Boolean.TRUE rather than the string "on".
        Object onBlock = parsed.containsKey("on") ? parsed.get("on") : parsed.get(Boolean.TRUE);
        TriggersSpec triggers = parseTriggers(onBlock);
        Map<String, JobSpec> jobs = parseJobs(parsed.get("jobs"));

        return new WorkflowSpec(name, triggers, concurrency, jobs, parsed);
    }

    private TriggersSpec parseTriggers(Object onBlock) {
        if (!(onBlock instanceof Map)) {
            return new TriggersSpec(null, null, List.of(), List.of(), false, Map.of());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> triggers = (Map<String, Object>) onBlock;

        ScheduleTrigger schedule = null;
        if (triggers.get("schedule") instanceof Map<?, ?> scheduleMap) {
            Object cronVal = scheduleMap.get("cron");
            schedule = new ScheduleTrigger(cronVal != null ? cronVal.toString().trim() : null);
        }

        WebhookTrigger webhook = null;
        if (triggers.get("webhook") instanceof Map<?, ?> webhookMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> webhookConfig = (Map<String, Object>) webhookMap;
            webhook = new WebhookTrigger(webhookConfig);
        }

        List<ConductorEventTrigger> events = new ArrayList<>();
        Object eventConfig = triggers.get(CONDUCTOR_STATUS_CHANGED);
        if (eventConfig != null) {
            List<String> statusFilter = List.of();
            if (eventConfig instanceof Map<?, ?> configMap && configMap.get("filters") instanceof Map<?, ?> filtersMap) {
                statusFilter = normalizeStringList(filtersMap.get("status"));
            }
            events.add(new ConductorEventTrigger(CONDUCTOR_STATUS_CHANGED, statusFilter));
        }

        List<GitHubPullRequestTrigger> pullRequestEvents = new ArrayList<>();
        Object prConfig = triggers.get(GITHUB_PULL_REQUEST);
        if (prConfig != null) {
            List<String> actionFilter = List.of();
            List<String> labelFilter = List.of();
            if (prConfig instanceof Map<?, ?> configMap && configMap.get("filters") instanceof Map<?, ?> filtersMap) {
                actionFilter = normalizeStringList(filtersMap.get("actions"));
                labelFilter = normalizeStringList(filtersMap.get("labels"));
            }
            pullRequestEvents.add(new GitHubPullRequestTrigger(GITHUB_PULL_REQUEST, actionFilter, labelFilter));
        }

        boolean hasWorkflowDispatch = triggers.containsKey("workflow_dispatch");

        return new TriggersSpec(schedule, webhook, events, pullRequestEvents, hasWorkflowDispatch, triggers);
    }

    private Map<String, JobSpec> parseJobs(Object jobsObj) {
        Map<String, JobSpec> result = new LinkedHashMap<>();
        if (!(jobsObj instanceof Map<?, ?> rawJobs)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : rawJobs.entrySet()) {
            String jobId = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> jobMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) jobMap;
            result.put(jobId, parseJob(jobId, job));
        }
        return result;
    }

    private JobSpec parseJob(String jobId, Map<String, Object> jobMap) {
        List<String> needs = normalizeStringList(jobMap.get("needs"));
        String runsOn = jobMap.get("runs-on") instanceof String s ? s : null;
        String ifCondition = textOrNull(jobMap.get("if"));
        LoopSpec loop = parseLoop(jobMap.get("loop"));
        List<StepSpec> steps = parseSteps(jobMap.get("steps"));
        List<String> consumes = normalizeStringList(jobMap.get("consumes"));
        return new JobSpec(jobId, needs, runsOn, ifCondition, loop, steps, consumes, jobMap);
    }

    private LoopSpec parseLoop(Object loopObj) {
        if (!(loopObj instanceof Map<?, ?> loopMap)) {
            return null;
        }
        Integer maxIterations = loopMap.get("max_iterations") instanceof Number n ? n.intValue() : null;
        String until = textOrNull(loopMap.get("until"));
        boolean failOnExhausted = !Boolean.FALSE.equals(loopMap.get("fail_on_exhausted"));
        return new LoopSpec(maxIterations, until, failOnExhausted);
    }

    private List<StepSpec> parseSteps(Object stepsObj) {
        if (!(stepsObj instanceof List<?> stepsList)) {
            return List.of();
        }
        List<StepSpec> result = new ArrayList<>();
        for (Object o : stepsList) {
            if (!(o instanceof Map<?, ?> stepMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> step = (Map<String, Object>) stepMap;
            result.add(parseStep(step));
        }
        return result;
    }

    private StepSpec parseStep(Map<String, Object> stepMap) {
        String id = textOrNull(stepMap.get("id"));
        String name = textOrNull(stepMap.get("name"));
        String type = resolveStepType(stepMap);
        String ifCondition = textOrNull(stepMap.get("if"));
        boolean continueOnError = Boolean.TRUE.equals(stepMap.get("continue-on-error"));
        @SuppressWarnings("unchecked")
        Map<String, Object> with = stepMap.get("with") instanceof Map<?, ?> w ? (Map<String, Object>) w : Map.of();
        List<ArtifactSpec> artifacts = parseArtifacts(stepMap.get("artifacts"));
        return new StepSpec(id, name, type, ifCondition, continueOnError, with, artifacts, stepMap);
    }

    /**
     * Tolerant, like the rest of this parser: a malformed entry (not a map, or missing name/path) is
     * simply skipped rather than thrown on — {@code WorkflowValidator} is where a malformed
     * {@code artifacts:} entry becomes an ERROR (inspecting the raw step map, same as its other
     * shape checks).
     */
    private List<ArtifactSpec> parseArtifacts(Object artifactsObj) {
        if (!(artifactsObj instanceof List<?> list)) {
            return List.of();
        }
        List<ArtifactSpec> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> entry)) continue;
            String name = textOrNull(entry.get("name"));
            String path = textOrNull(entry.get("path"));
            if (name == null || path == null) continue;
            result.add(new ArtifactSpec(name, path));
        }
        return result;
    }

    /**
     * Resolves a step's effective type: {@code uses: docker://...} -> {@code "docker"}; any other
     * non-null {@code uses:} -> itself; otherwise the explicit {@code type:}, defaulting to {@code
     * "http"}. Mirrors the pre-existing {@code WorkflowJobOrchestrator#resolveStepType}/{@code
     * WorkflowJobSteps#resolveStepType} exactly (including: when a step has both {@code type:} and
     * {@code uses:} — an invalid combination {@code WorkflowValidator} rejects — {@code uses:} still
     * wins here, same as before).
     */
    private static String resolveStepType(Map<String, Object> stepMap) {
        if (stepMap.get("uses") instanceof String uses) {
            return uses.startsWith("docker://") ? "docker" : uses;
        }
        Object typeVal = stepMap.get("type");
        return typeVal != null ? typeVal.toString() : "http";
    }

    private static String textOrNull(Object val) {
        return val != null ? val.toString() : null;
    }

    /** Normalizes a needs-shaped value (single string, or a list of strings) to a List<String>. */
    private static List<String> normalizeStringList(Object val) {
        if (val == null) return List.of();
        if (val instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return List.of(val.toString());
    }
}
