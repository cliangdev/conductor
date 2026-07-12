package com.conductor.workflow;

import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.LoopSpec;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Queue;

@Component
public class WorkflowValidator {

    private static final Set<String> VALID_RUNS_ON_SCALARS = Set.of("conductor", "self-hosted", "cloud-run");
    private static final String CLAUDE_CODE_TYPE = "claude-code";
    private static final java.util.regex.Pattern CRON_PATTERN =
            java.util.regex.Pattern.compile("^\\S+ \\S+ \\S+ \\S+ \\S+$");

    private final WorkflowYamlParser yamlParser;
    private final Set<String> allowedStepTypes;

    @Autowired
    public WorkflowValidator(WorkflowYamlParser yamlParser, List<WorkflowExecutionBackend> backends) {
        this.yamlParser = yamlParser;
        Set<String> types = new HashSet<>();
        for (WorkflowExecutionBackend backend : backends) {
            types.add(backend.getStepType());
        }
        // "condition" has no executor backend — it's handled inline by the orchestrator — but it's
        // a legitimate step type authors can write, so the registry includes it alongside whatever
        // WorkflowExecutionBackend beans are wired up.
        types.add("condition");
        this.allowedStepTypes = Set.copyOf(types);
    }

    /**
     * Test-only constructor with an explicit allowed step-type set, bypassing the Spring-wired
     * executor registry. Package-private: production code always goes through the registry-driven
     * constructor above.
     */
    WorkflowValidator(Set<String> allowedStepTypes) {
        this.yamlParser = new WorkflowYamlParser();
        this.allowedStepTypes = Set.copyOf(allowedStepTypes);
    }

    /** Delegates with no project runtime targets — existing callers/tests keep today's behavior unchanged. */
    public WorkflowValidationResult validate(String yaml, Set<String> existingSecretKeys) {
        return validate(yaml, existingSecretKeys, Set.of());
    }

    /**
     * @param runtimeTargetNames ALL of the project's runtime target names (any status, not just
     *                           ACTIVE — see {@code RuntimeTargetService#targetNames}), accepted as
     *                           {@code runs-on} scalars alongside the builtin conductor/self-hosted/
     *                           cloud-run values. Readiness (target must be ACTIVE) is enforced at
     *                           execution time by {@code RuntimeTargetResolver}, not here.
     */
    public WorkflowValidationResult validate(String yaml, Set<String> existingSecretKeys, Set<String> runtimeTargetNames) {
        WorkflowSpec spec;
        try {
            spec = yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
            return new WorkflowValidationResult(List.of(e.getMessage()), List.of());
        }

        WorkflowValidationResult result = validate(spec, runtimeTargetNames);

        // Secret-reference warnings scan the raw YAML text (a regex over ${{ secrets.X }}), not the
        // typed model, so they're computed here rather than in validate(WorkflowSpec, ...).
        List<String> warnings = new ArrayList<>(result.getWarnings());
        for (String key : extractSecretReferences(yaml)) {
            if (!existingSecretKeys.contains(key)) {
                warnings.add("Referenced secret '" + key + "' is not defined for this project");
            }
        }
        return new WorkflowValidationResult(result.getErrors(), warnings);
    }

    /**
     * Validates an already-parsed {@link WorkflowSpec} against the step-type registry and the
     * project's runtime targets. Does not include secret-reference warnings (those need the raw
     * YAML text) — the {@code validate(String, ...)} overloads add those on top of this.
     */
    public WorkflowValidationResult validate(WorkflowSpec spec, Set<String> runtimeTargetNames) {
        List<String> errors = new ArrayList<>();

        // Structural gates: these must hold before any typed field can be trusted, so they read
        // the raw parsed document directly rather than the typed WorkflowSpec fields.
        boolean hasOn = spec.raw().containsKey("on") || spec.raw().containsKey(Boolean.TRUE);
        if (!hasOn) errors.add("Missing required field: on");
        if (!spec.raw().containsKey("jobs")) errors.add("Missing required field: jobs");
        if (!errors.isEmpty()) return new WorkflowValidationResult(errors, List.of());

        Object jobsObj = spec.raw().get("jobs");
        if (!(jobsObj instanceof Map)) {
            errors.add("'jobs' must be a mapping");
            return new WorkflowValidationResult(errors, List.of());
        }

        Map<String, JobSpec> jobs = spec.jobs();

        // Cycle detection and condition target tracking
        Map<String, List<String>> needsGraph = new HashMap<>();
        Set<String> conditionTargets = new HashSet<>();

        for (JobSpec job : jobs.values()) {
            needsGraph.put(job.id(), job.needs());

            // Validate runs-on (list-valued runs-on is read from raw — JobSpec.runsOn() is null for
            // that case, since only a scalar runs-on is validated against runtime target names).
            Object runsOnVal = job.raw().get("runs-on");
            if (runsOnVal != null) {
                if (runsOnVal instanceof List) {
                    // List of strings — allowed
                } else if (runsOnVal instanceof String runsOnStr) {
                    if (!VALID_RUNS_ON_SCALARS.contains(runsOnStr) && !runtimeTargetNames.contains(runsOnStr)) {
                        errors.add("Invalid runs-on value: " + runsOnStr
                                + " (not a built-in runner or a project runtime target)");
                    }
                } else {
                    errors.add("Invalid runs-on value: " + runsOnVal);
                }
            }

            validateLoop(job, errors);
            validateSteps(job, jobs, runsOnVal, runtimeTargetNames, conditionTargets, errors);
        }

        // Validate condition targets not in regular needs
        for (JobSpec job : jobs.values()) {
            for (String need : job.needs()) {
                if (conditionTargets.contains(need)) {
                    errors.add("job " + need + " is a condition target and cannot appear in needs of job " + job.id());
                }
            }
        }

        validateScheduleTrigger(spec, errors);

        if (detectCycle(needsGraph)) {
            errors.add("Circular dependency detected in jobs needs graph: " + needsGraph.keySet());
        }

        return new WorkflowValidationResult(errors, List.of());
    }

    private void validateLoop(JobSpec job, List<String> errors) {
        LoopSpec loop = job.loop();
        if (loop == null) return;
        if (loop.maxIterations() == null || loop.maxIterations() <= 0) {
            errors.add("loop.max_iterations must be a positive integer");
        }
        if (loop.until() == null || loop.until().isBlank()) {
            errors.add("loop.until is required");
        }
    }

    private void validateSteps(JobSpec job, Map<String, JobSpec> jobs, Object runsOnVal,
                               Set<String> runtimeTargetNames, Set<String> conditionTargets, List<String> errors) {
        List<StepSpec> steps = job.steps();
        for (int i = 0; i < steps.size(); i++) {
            StepSpec step = steps.get(i);

            if (step.raw().get("type") != null && step.raw().get("uses") != null) {
                errors.add("Step cannot have both 'type' and 'uses' fields");
                continue;
            }

            String resolvedType = step.type();
            if (!allowedStepTypes.contains(resolvedType)) {
                errors.add("Unknown step type: " + resolvedType);
                continue;
            }

            switch (resolvedType) {
                case "kestra" -> validateKestraStep(step, errors);
                case "condition" -> {
                    if (i != steps.size() - 1) {
                        errors.add("condition step must be the last step in job " + job.id());
                    }
                    validateConditionStep(step, job.id(), jobs, errors, conditionTargets);
                }
                case CLAUDE_CODE_TYPE -> validateClaudeCodeStep(step, job.id(), runsOnVal, runtimeTargetNames, errors);
                case "integration" -> validateIntegrationStep(step, errors);
                case "agent" -> validateAgentStep(step, errors);
                default -> { /* http, docker: no extra config checks today */ }
            }
        }
    }

    private void validateConditionStep(StepSpec step, String jobId, Map<String, JobSpec> jobs,
                                       List<String> errors, Set<String> conditionTargets) {
        Object expression = step.raw().get("expression");
        if (expression == null || expression.toString().isBlank()) {
            errors.add("condition step missing required field: expression");
        }

        Object thenJob = step.raw().get("then");
        Object elseJob = step.raw().get("else");

        if (thenJob == null || thenJob.toString().isBlank()) {
            errors.add("condition step missing required field: then");
        } else {
            String thenJobId = thenJob.toString();
            conditionTargets.add(thenJobId);
            if (!jobs.containsKey(thenJobId)) {
                errors.add("condition step 'then' references unknown job: " + thenJobId);
            } else if (isAncestor(thenJobId, jobId, jobs)) {
                errors.add("condition step creates a cycle: job " + jobId + " cannot route to ancestor " + thenJobId);
            }
        }

        if (elseJob == null || elseJob.toString().isBlank()) {
            errors.add("condition step missing required field: else");
        } else {
            String elseJobId = elseJob.toString();
            conditionTargets.add(elseJobId);
            if (!jobs.containsKey(elseJobId)) {
                errors.add("condition step 'else' references unknown job: " + elseJobId);
            } else if (isAncestor(elseJobId, jobId, jobs)) {
                errors.add("condition step creates a cycle: job " + jobId + " cannot route to ancestor " + elseJobId);
            }
        }
    }

    /** Returns true if potentialAncestor is an ancestor of targetJobId in the static needs graph. */
    private boolean isAncestor(String potentialAncestor, String targetJobId, Map<String, JobSpec> jobs) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(targetJobId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);
            JobSpec jobDef = jobs.get(current);
            if (jobDef == null) continue;
            for (String need : jobDef.needs()) {
                if (need.equals(potentialAncestor)) return true;
                queue.add(need);
            }
        }
        return false;
    }

    /**
     * Validates a {@code claude-code} step's {@code with:} config. Unlike the general step-type
     * checks, {@code runs-on} eligibility is scoped to this step type — it does not become a
     * general {@code uses:} whitelist.
     */
    @SuppressWarnings("unchecked")
    private void validateClaudeCodeStep(StepSpec step, String jobId, Object runsOnVal,
                                        Set<String> runtimeTargetNames, List<String> errors) {
        if (!isClaudeCodeRunsOn(runsOnVal, runtimeTargetNames)) {
            errors.add("claude-code step in job " + jobId
                    + " requires runs-on: cloud-run or self-hosted, or a project runtime target "
                    + "(claude-code does not run on the shared conductor runner)");
        }

        Map<String, Object> with = step.with();

        Object prompt = with.get("prompt");
        if (prompt == null || prompt.toString().isBlank()) {
            errors.add("claude-code step missing required field: with.prompt");
        }

        Object timeoutMinutes = with.get("timeout_minutes");
        if (timeoutMinutes != null && (!(timeoutMinutes instanceof Number)
                || ((Number) timeoutMinutes).intValue() < 1 || ((Number) timeoutMinutes).intValue() > 120)) {
            errors.add("claude-code step with.timeout_minutes must be an integer between 1 and 120");
        }

        Object maxTurns = with.get("max_turns");
        if (maxTurns != null && (!(maxTurns instanceof Number) || ((Number) maxTurns).intValue() <= 0)) {
            errors.add("claude-code step with.max_turns must be a positive integer");
        }

        Object inputs = with.get("inputs");
        if (inputs != null) {
            if (!(inputs instanceof Map)) {
                errors.add("claude-code step with.inputs must be a map of scalar values");
            } else {
                for (Object value : ((Map<String, Object>) inputs).values()) {
                    if (value instanceof Map || value instanceof List) {
                        errors.add("claude-code step with.inputs must be a map of scalar values");
                        break;
                    }
                }
            }
        }

        Object outputSchema = with.get("output_schema");
        if (outputSchema != null && !(outputSchema instanceof Map)) {
            errors.add("claude-code step with.output_schema must be a map");
        }

        Object conductorMcp = with.get("conductor_mcp");
        if (conductorMcp != null && !(conductorMcp instanceof Boolean)) {
            errors.add("claude-code step with.conductor_mcp must be a boolean");
        }
    }

    private boolean isClaudeCodeRunsOn(Object runsOnVal, Set<String> runtimeTargetNames) {
        if (runsOnVal instanceof String s) {
            return "cloud-run".equals(s) || "self-hosted".equals(s) || runtimeTargetNames.contains(s);
        }
        if (runsOnVal instanceof List<?> list) {
            return list.stream().anyMatch(v -> "cloud-run".equals(v) || "self-hosted".equals(v)
                    || runtimeTargetNames.contains(v));
        }
        return false;
    }

    private void validateKestraStep(StepSpec step, List<String> errors) {
        Object namespace = step.raw().get("namespace");
        if (namespace == null || namespace.toString().isBlank()) {
            errors.add("kestra step missing required field: namespace");
        }
        Object flowId = step.raw().get("flow_id");
        if (flowId == null || flowId.toString().isBlank()) {
            errors.add("kestra step missing required field: flow_id");
        }
    }

    private void validateIntegrationStep(StepSpec step, List<String> errors) {
        Object connector = step.with().get("connector");
        if (connector == null || connector.toString().isBlank()) {
            errors.add("integration step missing required field: with.connector");
        }
    }

    private void validateAgentStep(StepSpec step, List<String> errors) {
        Object agent = step.with().get("agent");
        if (agent == null || agent.toString().isBlank()) {
            errors.add("agent step missing required field: with.agent");
        }
    }

    private void validateScheduleTrigger(WorkflowSpec spec, List<String> errors) {
        if (spec.triggers().schedule() == null) return;
        String cron = spec.triggers().schedule().cron();
        if (cron == null) {
            errors.add("schedule trigger missing required field: cron");
        } else if (!CRON_PATTERN.matcher(cron).matches()) {
            errors.add("Invalid cron expression: " + cron);
        }
    }

    private boolean detectCycle(Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String node : graph.keySet()) {
            if (dfs(node, graph, visited, inStack)) return true;
        }
        return false;
    }

    private boolean dfs(String node, Map<String, List<String>> graph, Set<String> visited, Set<String> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        inStack.add(node);
        for (String dep : graph.getOrDefault(node, List.of())) {
            if (dfs(dep, graph, visited, inStack)) return true;
        }
        inStack.remove(node);
        return false;
    }

    private Set<String> extractSecretReferences(String yaml) {
        Set<String> keys = new HashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\$\\{\\{\\s*secrets\\.([A-Z][A-Z0-9_]*)\\s*\\}\\}");
        java.util.regex.Matcher m = p.matcher(yaml);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }
}
