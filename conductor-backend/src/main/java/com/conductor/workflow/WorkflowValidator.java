package com.conductor.workflow;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.util.*;
import java.util.Queue;

@Component
public class WorkflowValidator {

    private static final Set<String> ALLOWED_STEP_TYPES = Set.of("http", "docker", "kestra", "condition");
    private static final Set<String> VALID_RUNS_ON_SCALARS = Set.of("conductor", "self-hosted");
    private static final java.util.regex.Pattern CRON_PATTERN =
            java.util.regex.Pattern.compile("^\\S+ \\S+ \\S+ \\S+ \\S+$");

    public WorkflowValidationResult validate(String yaml, Set<String> existingSecretKeys) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> parsed;
        try {
            Yaml snakeYaml = new Yaml();
            parsed = snakeYaml.load(yaml);
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : 0;
            int col = e.getProblemMark() != null ? e.getProblemMark().getColumn() + 1 : 0;
            errors.add("[" + line + ":" + col + "] YAML parse error: " + e.getProblem());
            return new WorkflowValidationResult(errors, warnings);
        } catch (YAMLException e) {
            errors.add("[0:0] YAML parse error: " + e.getMessage());
            return new WorkflowValidationResult(errors, warnings);
        }

        if (parsed == null) {
            errors.add("Workflow YAML is empty");
            return new WorkflowValidationResult(errors, warnings);
        }

        // Required fields (name is passed separately in the API request, not required in YAML)
        // Note: SnakeYAML 1.1 parses bare 'on' as Boolean.TRUE, so check both
        boolean hasOn = parsed.containsKey("on") || parsed.containsKey(Boolean.TRUE);
        if (!hasOn) errors.add("Missing required field: on");
        if (!parsed.containsKey("jobs")) errors.add("Missing required field: jobs");

        if (!errors.isEmpty()) return new WorkflowValidationResult(errors, warnings);

        // Validate jobs
        Object jobsObj = parsed.get("jobs");
        if (!(jobsObj instanceof Map)) {
            errors.add("'jobs' must be a mapping");
            return new WorkflowValidationResult(errors, warnings);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) jobsObj;

        // Cycle detection and condition target tracking
        Map<String, List<String>> needs = new HashMap<>();
        Set<String> conditionTargets = new HashSet<>();

        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            String jobId = entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            Object needsVal = job.get("needs");
            List<String> deps = new ArrayList<>();
            if (needsVal instanceof List) {
                for (Object dep : (List<?>) needsVal) deps.add(dep.toString());
            } else if (needsVal instanceof String) {
                deps.add(needsVal.toString());
            }
            needs.put(jobId, deps);

            // Validate runs-on
            Object runsOnVal = job.get("runs-on");
            if (runsOnVal != null) {
                if (runsOnVal instanceof List) {
                    // List of strings — allowed
                } else if (runsOnVal instanceof String runsOnStr) {
                    if (!VALID_RUNS_ON_SCALARS.contains(runsOnStr)) {
                        errors.add("Invalid runs-on value: " + runsOnStr);
                    }
                } else {
                    errors.add("Invalid runs-on value: " + runsOnVal);
                }
            }

            // Validate loop block
            Object loopObj = job.get("loop");
            if (loopObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> loopDef = (Map<String, Object>) loopObj;
                Object maxIter = loopDef.get("max_iterations");
                if (maxIter == null) {
                    errors.add("loop.max_iterations must be a positive integer");
                } else if (!(maxIter instanceof Number) || ((Number) maxIter).intValue() <= 0) {
                    errors.add("loop.max_iterations must be a positive integer");
                }
                Object until = loopDef.get("until");
                if (until == null || until.toString().isBlank()) {
                    errors.add("loop.until is required");
                }
            }

            // Validate step types and collect condition targets
            Object stepsObj = job.get("steps");
            if (stepsObj instanceof List) {
                List<?> stepsList = (List<?>) stepsObj;
                for (int i = 0; i < stepsList.size(); i++) {
                    Object stepObj = stepsList.get(i);
                    if (!(stepObj instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> step = (Map<String, Object>) stepObj;
                    Object typeVal = step.get("type");
                    Object usesVal = step.get("uses");

                    if (typeVal != null && usesVal != null) {
                        errors.add("Step cannot have both 'type' and 'uses' fields");
                        continue;
                    }

                    if (usesVal instanceof String uses && uses.startsWith("docker://")) {
                        // Valid docker uses syntax — treated as docker step type
                        continue;
                    }

                    if (typeVal != null) {
                        String type = typeVal.toString();
                        if (!ALLOWED_STEP_TYPES.contains(type)) {
                            errors.add("Unknown step type: " + typeVal);
                        } else if ("kestra".equals(type)) {
                            validateKestraStep(step, errors);
                        } else if ("condition".equals(type)) {
                            // Condition step must be last
                            if (i != stepsList.size() - 1) {
                                errors.add("condition step must be the last step in job " + jobId);
                            }
                            validateConditionStep(step, jobId, jobs, errors, conditionTargets);
                        }
                    }
                }
            }
        }

        // Validate condition targets not in regular needs
        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            String jobId = entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            List<String> jobNeeds = needs.getOrDefault(jobId, List.of());
            for (String need : jobNeeds) {
                if (conditionTargets.contains(need)) {
                    errors.add("job " + need + " is a condition target and cannot appear in needs of job " + jobId);
                }
            }
        }

        // Validate schedule trigger
        Object onBlock = parsed.containsKey("on") ? parsed.get("on") : parsed.get(Boolean.TRUE);
        if (onBlock instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> triggers = (Map<String, Object>) onBlock;
            Object scheduleTrigger = triggers.get("schedule");
            if (scheduleTrigger instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> scheduleConfig = (Map<String, Object>) scheduleTrigger;
                Object cronVal = scheduleConfig.get("cron");
                if (cronVal == null) {
                    errors.add("schedule trigger missing required field: cron");
                } else {
                    String cron = cronVal.toString().trim();
                    if (!CRON_PATTERN.matcher(cron).matches()) {
                        errors.add("Invalid cron expression: " + cron);
                    }
                }
            }
        }

        if (detectCycle(needs)) {
            errors.add("Circular dependency detected in jobs needs graph: " + needs.keySet());
        }

        // Secret reference warnings
        Set<String> referencedSecrets = extractSecretReferences(yaml);
        for (String key : referencedSecrets) {
            if (!existingSecretKeys.contains(key)) {
                warnings.add("Referenced secret '" + key + "' is not defined for this project");
            }
        }

        return new WorkflowValidationResult(errors, warnings);
    }

    @SuppressWarnings("unchecked")
    private void validateConditionStep(Map<String, Object> step, String jobId,
                                       Map<String, Object> jobs, List<String> errors,
                                       Set<String> conditionTargets) {
        Object expression = step.get("expression");
        if (expression == null || expression.toString().isBlank()) {
            errors.add("condition step missing required field: expression");
        }

        Object thenJob = step.get("then");
        Object elseJob = step.get("else");

        if (thenJob == null || thenJob.toString().isBlank()) {
            errors.add("condition step missing required field: then");
        } else {
            String thenJobId = thenJob.toString();
            conditionTargets.add(thenJobId);
            if (!jobs.containsKey(thenJobId)) {
                errors.add("condition step 'then' references unknown job: " + thenJobId);
            } else {
                // Check cycle: thenJob cannot be an ancestor of jobId in the static needs graph
                if (isAncestor(thenJobId, jobId, jobs)) {
                    errors.add("condition step creates a cycle: job " + jobId + " cannot route to ancestor " + thenJobId);
                }
            }
        }

        if (elseJob == null || elseJob.toString().isBlank()) {
            errors.add("condition step missing required field: else");
        } else {
            String elseJobId = elseJob.toString();
            conditionTargets.add(elseJobId);
            if (!jobs.containsKey(elseJobId)) {
                errors.add("condition step 'else' references unknown job: " + elseJobId);
            } else {
                if (isAncestor(elseJobId, jobId, jobs)) {
                    errors.add("condition step creates a cycle: job " + jobId + " cannot route to ancestor " + elseJobId);
                }
            }
        }
    }

    /**
     * Returns true if potentialAncestor is an ancestor of targetJobId in the static needs graph.
     */
    @SuppressWarnings("unchecked")
    private boolean isAncestor(String potentialAncestor, String targetJobId, Map<String, Object> jobs) {
        // Walk up from targetJobId via needs — if we find potentialAncestor, it's an ancestor
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new java.util.LinkedList<>();
        queue.add(targetJobId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);
            if (!(jobs.get(current) instanceof Map)) continue;
            Map<String, Object> jobDef = (Map<String, Object>) jobs.get(current);
            Object needsVal = jobDef.get("needs");
            List<String> needsList = new ArrayList<>();
            if (needsVal instanceof List) {
                for (Object n : (List<?>) needsVal) needsList.add(n.toString());
            } else if (needsVal instanceof String) {
                needsList.add(needsVal.toString());
            }
            for (String need : needsList) {
                if (need.equals(potentialAncestor)) return true;
                queue.add(need);
            }
        }
        return false;
    }

    private void validateKestraStep(Map<String, Object> step, List<String> errors) {
        Object namespace = step.get("namespace");
        if (namespace == null || namespace.toString().isBlank()) {
            errors.add("kestra step missing required field: namespace");
        }
        Object flowId = step.get("flow_id");
        if (flowId == null || flowId.toString().isBlank()) {
            errors.add("kestra step missing required field: flow_id");
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
