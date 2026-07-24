package com.conductor.workflow.schema;

import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift guard for {@link StepSchemaRegistry}: for every {@link StepTypeSchema} it declares, this
 * generates YAML fixtures and runs them through the REAL {@link WorkflowValidator} (not a mock) — so
 * if the registry's hand-authored description of a required field ever diverges from what the
 * validator actually enforces, this test fails.
 *
 * <p><b>Scope note — "wrong type" fixtures.</b> The brief for this test also asks for a wrong-type
 * fixture per required field "where practical". Every required field in this registry (checked
 * against {@code WorkflowValidator}'s source) is validated with a {@code value.toString().isBlank()}
 * check, not an {@code instanceof} type check — so a required field given e.g. a nested map or a
 * number would stringify to something non-blank and pass anyway. A wrong-type fixture is therefore
 * not a meaningful negative test for any required field today, so none are generated. (Fields that
 * DO have real type constraints — {@code with.credentials}, {@code with.env}, {@code
 * with.timeout_minutes}, {@code with.output_schema}, {@code with.inputs}, {@code
 * with.conductor_mcp} — are all optional, and are already covered directly against the validator by
 * {@code WorkflowValidatorExtensionsTest}/{@code WorkflowValidatorCredentialsTest}.)
 */
class StepSchemaSyncTest {

    private static final StepSchemaRegistry REGISTRY = new StepSchemaRegistry(List.of());
    private static final WorkflowValidator VALIDATOR = new WorkflowValidator(Set.of(
            "http", "docker", "kestra", "condition", "integration", "agent", "claude-code", "action"));
    private static final List<String> WITH_STYLE_TYPES = List.of("integration", "agent", "claude-code", "action");

    @TestFactory
    Stream<DynamicTest> registrySchemaMatchesRealValidatorBehavior() {
        List<DynamicTest> tests = new ArrayList<>();

        for (StepTypeSchema schema : REGISTRY.stepTypes()) {
            List<StepFieldSchema> requiredFields = schema.fields().stream()
                    .filter(StepFieldSchema::required)
                    .toList();

            tests.add(DynamicTest.dynamicTest(
                    schema.type() + ": minimal valid fixture (every required field present) has no errors",
                    () -> {
                        String yaml = buildWorkflowYaml(schema, null);
                        WorkflowValidationResult result = VALIDATOR.validate(yaml, Set.of());
                        assertThat(result.getErrors())
                                .as("step type '%s' minimal valid fixture:%n%s", schema.type(), yaml)
                                .isEmpty();
                    }));

            for (StepFieldSchema field : requiredFields) {
                tests.add(DynamicTest.dynamicTest(
                        schema.type() + ": omitting required field '" + field.name() + "' is rejected",
                        () -> {
                            String yaml = buildWorkflowYaml(schema, field.name());
                            WorkflowValidationResult result = VALIDATOR.validate(yaml, Set.of());
                            assertThat(result.hasErrors())
                                    .as("step type '%s' omitting required field '%s' should be rejected:%n%s",
                                            schema.type(), field.name(), yaml)
                                    .isTrue();
                            assertThat(result.getErrors())
                                    .as("step type '%s' omitting '%s' should produce an error naming that "
                                                    + "field, got: %s",
                                            schema.type(), field.name(), result.getErrors())
                                    .anyMatch(e -> e.toLowerCase().contains(field.name().toLowerCase()));
                        }));
            }
        }

        return tests.stream();
    }

    /**
     * Builds a full workflow YAML wrapping one step of {@code schema}'s type, with every required
     * field set to a valid sample value except {@code omitFieldName} (if non-null, that field is left
     * out entirely). Uses SnakeYAML's dumper rather than hand-templated strings so nesting/indentation
     * is always well-formed regardless of step shape.
     */
    private static String buildWorkflowYaml(StepTypeSchema schema, String omitFieldName) {
        Map<String, Object> fieldValues = new LinkedHashMap<>();
        for (StepFieldSchema field : schema.fields()) {
            if (!field.required() || field.name().equals(omitFieldName)) {
                continue;
            }
            fieldValues.put(field.name(), sampleValue(schema.type(), field.name()));
        }

        Map<String, Object> stepMap = new LinkedHashMap<>();
        if ("docker".equals(schema.type())) {
            // docker's "image" isn't a with:/root field -- it's embedded in the uses: value itself.
            stepMap.put("uses", "docker://alpine:3.19");
            stepMap.putAll(fieldValues);
        } else if (WITH_STYLE_TYPES.contains(schema.type())) {
            stepMap.put("uses", schema.type());
            stepMap.put("with", fieldValues);
        } else {
            // http, kestra, condition: flat authoring style, fields live directly on the step.
            stepMap.put("type", schema.type());
            stepMap.putAll(fieldValues);
        }

        Map<String, Object> mainJob = new LinkedHashMap<>();
        if ("claude-code".equals(schema.type())) {
            // claude-code steps require a container-capable runs-on regardless of with: content.
            mainJob.put("runs-on", "cloud-run");
        }
        mainJob.put("steps", List.of(stepMap));

        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("main", mainJob);
        if ("condition".equals(schema.type())) {
            // then:/else: must reference real jobs, so give the condition step two trivial targets.
            jobs.put("route-a", trivialHttpJob());
            jobs.put("route-b", trivialHttpJob());
        }

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("on", Map.of("schedule", Map.of("cron", "0 * * * *")));
        workflow.put("jobs", jobs);
        return new Yaml().dump(workflow);
    }

    private static Map<String, Object> trivialHttpJob() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "http");
        step.put("url", "https://example.com");
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("steps", List.of(step));
        return job;
    }

    private static Object sampleValue(String stepType, String fieldName) {
        if ("condition".equals(stepType)) {
            if ("then".equals(fieldName)) return "route-a";
            if ("else".equals(fieldName)) return "route-b";
            if ("expression".equals(fieldName)) return "${{ event.ok == 'true' }}";
        }
        return "sample-" + fieldName;
    }
}
