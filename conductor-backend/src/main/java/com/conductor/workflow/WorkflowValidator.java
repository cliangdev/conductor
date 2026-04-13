package com.conductor.workflow;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.util.*;

@Component
public class WorkflowValidator {

    private static final Set<String> ALLOWED_STEP_TYPES = Set.of("http", "docker", "kestra");
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

        // Cycle detection
        Map<String, List<String>> needs = new HashMap<>();
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

            // Validate step types
            Object stepsObj = job.get("steps");
            if (stepsObj instanceof List) {
                for (Object stepObj : (List<?>) stepsObj) {
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
                        }
                    }
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
