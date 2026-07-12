package com.conductor.workflow;

import com.conductor.integration.ConnectorRegistry;
import com.conductor.workflow.model.ArtifactSpec;
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
    /**
     * Null under the test-only constructor. Used only for the (best-effort, warning-only) action-step
     * connector/action-id lint — never for the required-field checks, so validation still works
     * without it wired up.
     */
    private final ConnectorRegistry connectorRegistry;

    @Autowired
    public WorkflowValidator(WorkflowYamlParser yamlParser, List<WorkflowExecutionBackend> backends,
                             ConnectorRegistry connectorRegistry) {
        this.yamlParser = yamlParser;
        this.connectorRegistry = connectorRegistry;
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
        this.connectorRegistry = null;
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

    private static final java.util.regex.Pattern EXPR_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{\\{\\s*(.+?)\\s*\\}\\}");
    private static final Set<String> KNOWN_INTERPOLATION_ROOTS =
            Set.of("event", "secrets", "steps", "needs", "inputs", "loop");

    /**
     * Validates an already-parsed {@link WorkflowSpec} against the step-type registry and the
     * project's runtime targets. Does not include secret-reference warnings (those need the raw
     * YAML text) — the {@code validate(String, ...)} overloads add those on top of this.
     */
    public WorkflowValidationResult validate(WorkflowSpec spec, Set<String> runtimeTargetNames) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

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
        // jobId -> artifact names its steps declare producing; name -> job id, to catch a duplicate
        // artifact name declared by two different jobs anywhere in the run graph.
        Map<String, Set<String>> jobProducedArtifacts = new HashMap<>();
        Map<String, String> artifactNameOwner = new HashMap<>();

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
            Set<String> produced = new HashSet<>();
            validateSteps(job, jobs, runsOnVal, runtimeTargetNames, conditionTargets, errors, warnings, produced);
            for (String artifactName : produced) {
                String owner = artifactNameOwner.putIfAbsent(artifactName, job.id());
                if (owner != null) {
                    errors.add("Duplicate artifact name '" + artifactName + "' produced by both job '"
                            + owner + "' and job '" + job.id() + "' — artifact names must be unique across the run");
                }
            }
            jobProducedArtifacts.put(job.id(), produced);
        }

        // Validate condition targets not in regular needs
        for (JobSpec job : jobs.values()) {
            for (String need : job.needs()) {
                if (conditionTargets.contains(need)) {
                    errors.add("job " + need + " is a condition target and cannot appear in needs of job " + job.id());
                }
            }
        }

        // Validate consumes: — each name must be produced by one of this job's needs.
        for (JobSpec job : jobs.values()) {
            for (String consumedName : job.consumes()) {
                boolean producedByNeed = job.needs().stream()
                        .anyMatch(needJobId -> jobProducedArtifacts.getOrDefault(needJobId, Set.of()).contains(consumedName));
                if (!producedByNeed) {
                    errors.add("job '" + job.id() + "' consumes artifact '" + consumedName
                            + "' which is not produced by any job in its needs");
                }
            }
        }

        validateScheduleTrigger(spec, errors);

        if (detectCycle(needsGraph)) {
            errors.add("Circular dependency detected in jobs needs graph: " + needsGraph.keySet());
        }

        warnings.addAll(lintInterpolations(spec));
        return new WorkflowValidationResult(errors, warnings);
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
                               Set<String> runtimeTargetNames, Set<String> conditionTargets,
                               List<String> errors, List<String> warnings, Set<String> producedArtifacts) {
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

            Object continueOnErrorVal = step.raw().get("continue-on-error");
            if (continueOnErrorVal != null && !(continueOnErrorVal instanceof Boolean)) {
                errors.add("Step continue-on-error must be a boolean in job " + job.id());
            }
            if ("condition".equals(resolvedType) && continueOnErrorVal != null) {
                errors.add("condition step cannot have continue-on-error in job " + job.id());
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
                case "action" -> validateActionStep(step, errors, warnings);
                default -> { /* http, docker: no extra config checks today */ }
            }

            validateStepArtifacts(step, job.id(), resolvedType, runsOnVal, errors, producedArtifacts);
        }
    }

    /**
     * Validates a step's {@code artifacts:} list (docker/claude-code steps only). A {@code docker}
     * step also needs {@code runs-on: self-hosted} — the conductor-hosted worker-VM docker path
     * (WorkerVmClient) doesn't implement artifact upload/download yet; {@code claude-code} steps have
     * no such restriction since both the self-hosted daemon and the Cloud Run path support it.
     */
    private void validateStepArtifacts(StepSpec step, String jobId, String resolvedType, Object runsOnVal,
                                        List<String> errors, Set<String> producedArtifacts) {
        Object artifactsRaw = step.raw().get("artifacts");
        if (artifactsRaw == null) {
            return;
        }
        boolean allowedType = "docker".equals(resolvedType) || CLAUDE_CODE_TYPE.equals(resolvedType);
        if (!allowedType) {
            errors.add("'artifacts:' is only supported on docker and claude-code steps (job '" + jobId
                    + "', step type '" + resolvedType + "')");
            return;
        }
        if ("docker".equals(resolvedType) && !"self-hosted".equals(runsOnVal)) {
            errors.add("artifacts on conductor-hosted docker steps not yet supported in job '" + jobId
                    + "' — docker steps need 'runs-on: self-hosted' to declare artifacts");
        }
        if (!(artifactsRaw instanceof List<?> list)) {
            errors.add("job '" + jobId + "': step 'artifacts:' must be a list");
            return;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                errors.add("job '" + jobId + "': each 'artifacts:' entry must be a map with name/path");
                continue;
            }
            Object nameObj = entryMap.get("name");
            Object pathObj = entryMap.get("path");
            if (nameObj == null || nameObj.toString().isBlank()) {
                errors.add("job '" + jobId + "': artifacts entry missing required field: name");
                continue;
            }
            String artifactName = nameObj.toString();
            if (!ArtifactSpec.NAME_PATTERN.matcher(artifactName).matches()) {
                errors.add("job '" + jobId + "': artifact name '" + artifactName
                        + "' must match ^[a-z0-9_-]{1,160}$");
            }
            if (pathObj == null || pathObj.toString().isBlank()) {
                errors.add("job '" + jobId + "': artifact '" + artifactName + "' missing required field: path");
            }
            producedArtifacts.add(artifactName);
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

    /**
     * {@code with.connector} and {@code with.action} are required (hard errors — the executor cannot
     * run without them). Whether the connector id is registered, and whether it declares the given
     * action id, is checked only as a WARNING: the registry is the set of connector beans wired up in
     * THIS environment, and a local-profile authoring/test environment may lack a connector (e.g.
     * {@code @Profile("!local")}) that is very much registered in production — flagging that as an
     * error would make workflows unpublishable in exactly the environment authors use to write them.
     */
    private void validateActionStep(StepSpec step, List<String> errors, List<String> warnings) {
        Object connectorObj = step.with().get("connector");
        Object actionObj = step.with().get("action");
        if (connectorObj == null || connectorObj.toString().isBlank()) {
            errors.add("action step missing required field: with.connector");
        }
        if (actionObj == null || actionObj.toString().isBlank()) {
            errors.add("action step missing required field: with.action");
        }
        if (connectorRegistry == null || connectorObj == null || connectorObj.toString().isBlank()) {
            return;
        }

        String connectorId = connectorObj.toString();
        Optional<com.conductor.integration.ActionConnector> connector = connectorRegistry.findAction(connectorId);
        if (connector.isEmpty()) {
            warnings.add("action step references connector '" + connectorId
                    + "' which is not registered as an action connector in this environment");
            return;
        }
        if (actionObj != null && !actionObj.toString().isBlank()) {
            String actionId = actionObj.toString();
            boolean known = connector.get().getActions().stream().anyMatch(a -> a.id().equals(actionId));
            if (!known) {
                warnings.add("action step references action '" + actionId + "' which connector '"
                        + connectorId + "' does not declare");
            }
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

    /**
     * Publish-time lint over every {@code ${{ ... }}} occurrence in each job (job-level fields plus
     * all of its steps) — WARNINGS only, never errors, since these are best-effort hints rather than
     * a strict contract: an unknown root, a {@code steps.<id>.*} reference to a step not in the same
     * job, a {@code needs.<job>.*} reference to a job not in this job's {@code needs}, or a {@code
     * inputs.<name>} reference not declared under {@code on.workflow_dispatch.inputs} (warns even
     * when no {@code inputs:} block exists at all — there's nothing to validate against, so any such
     * reference is flagged).
     */
    private List<String> lintInterpolations(WorkflowSpec spec) {
        List<String> warnings = new ArrayList<>();
        Set<String> declaredInputs = declaredInputNames(spec);

        for (JobSpec job : spec.jobs().values()) {
            Set<String> stepIds = new HashSet<>();
            for (StepSpec step : job.steps()) {
                if (step.id() != null) stepIds.add(step.id());
            }
            Set<String> needs = new HashSet<>(job.needs());

            List<String> strings = new ArrayList<>();
            collectStrings(job.raw(), strings);

            for (String text : strings) {
                java.util.regex.Matcher m = EXPR_PATTERN.matcher(text);
                while (m.find()) {
                    String expr = m.group(1).trim();
                    String root = expr.split("\\.", 2)[0];

                    if (!KNOWN_INTERPOLATION_ROOTS.contains(root)) {
                        warnings.add("job '" + job.id() + "': unknown reference in '${{ " + expr + " }}'");
                        continue;
                    }
                    if (root.equals("steps")) {
                        String[] parts = expr.split("\\.", 3);
                        if (parts.length >= 2 && !stepIds.contains(parts[1])) {
                            warnings.add("job '" + job.id() + "': steps." + parts[1]
                                    + " is not a step in this job");
                        }
                    } else if (root.equals("needs")) {
                        String[] parts = expr.split("\\.", 3);
                        if (parts.length >= 2 && !needs.contains(parts[1])) {
                            warnings.add("job '" + job.id() + "': needs." + parts[1]
                                    + " is not declared in this job's needs");
                        }
                    } else if (root.equals("inputs")) {
                        String[] parts = expr.split("\\.", 2);
                        if (parts.length == 2 && !declaredInputs.contains(parts[1])) {
                            warnings.add("job '" + job.id() + "': inputs." + parts[1]
                                    + " is not declared under on.workflow_dispatch.inputs");
                        }
                    }
                }
            }
        }
        return warnings;
    }

    /** {@code on.workflow_dispatch.inputs} names, GitHub-style ({@code name: {description?, required?}}). */
    private Set<String> declaredInputNames(WorkflowSpec spec) {
        Object workflowDispatch = spec.triggers().raw().get("workflow_dispatch");
        if (!(workflowDispatch instanceof Map<?, ?> wd) || !(wd.get("inputs") instanceof Map<?, ?> inputs)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Object key : inputs.keySet()) names.add(String.valueOf(key));
        return names;
    }

    /** Recursively collects every String leaf value out of a parsed-YAML Map/List/scalar tree. */
    private void collectStrings(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) collectStrings(value, out);
        } else if (node instanceof List<?> list) {
            for (Object value : list) collectStrings(value, out);
        } else if (node instanceof String s) {
            out.add(s);
        }
    }

    private Set<String> extractSecretReferences(String yaml) {
        Set<String> keys = new HashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\$\\{\\{\\s*secrets\\.([A-Z][A-Z0-9_]*)\\s*\\}\\}");
        java.util.regex.Matcher m = p.matcher(yaml);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }
}
