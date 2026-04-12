package com.conductor.workflow;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.util.*;

@Component
public class WorkflowValidator {

    private static final Set<String> ALLOWED_STEP_TYPES = Set.of("http");

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

        // Required fields
        if (!parsed.containsKey("name")) errors.add("Missing required field: name");
        if (!parsed.containsKey("on")) errors.add("Missing required field: on");
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

            // Validate step types
            Object stepsObj = job.get("steps");
            if (stepsObj instanceof List) {
                for (Object stepObj : (List<?>) stepsObj) {
                    if (!(stepObj instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> step = (Map<String, Object>) stepObj;
                    Object typeVal = step.get("type");
                    if (typeVal != null && !ALLOWED_STEP_TYPES.contains(typeVal.toString())) {
                        errors.add("Unknown step type: " + typeVal);
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
