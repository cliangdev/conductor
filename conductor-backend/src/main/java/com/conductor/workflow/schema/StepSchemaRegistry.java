package com.conductor.workflow.schema;

import com.conductor.workflow.WorkflowExecutionBackend;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.conductor.workflow.schema.StepFieldType.ARRAY;
import static com.conductor.workflow.schema.StepFieldType.BOOLEAN;
import static com.conductor.workflow.schema.StepFieldType.INTEGER;
import static com.conductor.workflow.schema.StepFieldType.MAP;
import static com.conductor.workflow.schema.StepFieldType.OBJECT;
import static com.conductor.workflow.schema.StepFieldType.STRING;

/**
 * Hand-authored, data-shaped mirror of what {@code WorkflowValidator} accepts for each step type —
 * a parallel, additive registry (not a replacement for the validator's imperative checks, which keep
 * running exactly as before). Exists so docs and the Claude Code workflow-authoring skill have a
 * single source to read instead of hand-transcribing the validator's logic as prose, which drifts.
 *
 * <p>Two things keep this honest against the real validator:
 * <ul>
 *   <li>{@link #verifyStepTypesMatchValidator()} — a {@code @PostConstruct} fail-fast check that this
 *       registry's step-type keys exactly match the {@code Set<String>} {@code WorkflowValidator}
 *       builds from the wired {@link WorkflowExecutionBackend} beans (plus {@code "condition"}).</li>
 *   <li>{@code StepSchemaSyncTest} (test tree) — runs generated YAML fixtures through the real {@code
 *       WorkflowValidator} to catch drift in individual fields, not just the step-type set.</li>
 * </ul>
 */
@Component
public class StepSchemaRegistry {

    private final List<WorkflowExecutionBackend> backends;
    private final Map<String, StepTypeSchema> stepTypesByType;
    private final List<InterpolationRoot> interpolationRoots;
    private final List<InterpolationFunction> interpolationFunctions;

    public StepSchemaRegistry(List<WorkflowExecutionBackend> backends) {
        this.backends = backends;
        this.stepTypesByType = buildStepTypes();
        this.interpolationRoots = buildInterpolationRoots();
        this.interpolationFunctions = buildInterpolationFunctions();
    }

    /**
     * Fails application startup if this registry's step types ever diverge from what {@code
     * WorkflowValidator} actually allows (a new executor bean added without a matching schema entry,
     * or vice versa) — the same "unchecked-uses hole" class of bug {@code
     * WorkflowValidatorRegistrySmokeTest} guards for the validator itself.
     */
    @PostConstruct
    void verifyStepTypesMatchValidator() {
        Set<String> validatorAllowedStepTypes = new HashSet<>();
        for (WorkflowExecutionBackend backend : backends) {
            validatorAllowedStepTypes.add(backend.getStepType());
        }
        validatorAllowedStepTypes.add("condition");

        if (!stepTypesByType.keySet().equals(validatorAllowedStepTypes)) {
            throw new IllegalStateException("StepSchemaRegistry step types " + stepTypesByType.keySet()
                    + " do not match WorkflowValidator's allowed step types " + validatorAllowedStepTypes
                    + " -- keep StepSchemaRegistry.buildStepTypes() in sync with the wired "
                    + "WorkflowExecutionBackend beans.");
        }
    }

    /** Every step type's schema, in a stable, hand-chosen order. */
    public List<StepTypeSchema> stepTypes() {
        return List.copyOf(stepTypesByType.values());
    }

    public Optional<StepTypeSchema> findStepType(String type) {
        return Optional.ofNullable(stepTypesByType.get(type));
    }

    /** The valid {@code ${{ root.path }}} roots (see {@code WorkflowValidator#KNOWN_INTERPOLATION_ROOTS}). */
    public List<InterpolationRoot> interpolationRoots() {
        return interpolationRoots;
    }

    /** The {@code always()}/{@code success()}/{@code failure()} status functions (see {@code ConditionEvaluator}). */
    public List<InterpolationFunction> interpolationFunctions() {
        return interpolationFunctions;
    }

    private static Map<String, StepTypeSchema> buildStepTypes() {
        Map<String, StepTypeSchema> byType = new LinkedHashMap<>();
        for (StepTypeSchema schema : List.of(
                httpSchema(), dockerSchema(), kestraSchema(), integrationSchema(),
                conditionSchema(), agentSchema(), claudeCodeSchema(), actionSchema())) {
            byType.put(schema.type(), schema);
        }
        return byType;
    }

    // --- flat-style step types (fields live directly on the step, not under `with:`) ---

    private static StepTypeSchema httpSchema() {
        return new StepTypeSchema("http",
                "Calls an HTTP endpoint and optionally extracts values from the response into step "
                        + "outputs. A response with status >= 400 fails the step.",
                List.of(
                        new StepFieldSchema("url", STRING, false,
                                "Request URL. Not enforced as required by WorkflowValidator today, but the "
                                        + "step has nothing to call at execution time if it's blank."),
                        new StepFieldSchema("method", STRING, false, "HTTP method. Defaults to GET."),
                        new StepFieldSchema("headers", MAP, false,
                                "Key-value map of request headers. Values are interpolated."),
                        new StepFieldSchema("body", STRING, false,
                                "Request body string. Interpolated before sending."),
                        new StepFieldSchema("timeout", INTEGER, false,
                                "Timeout in seconds. Documented default 30 / max 120, but not range-checked "
                                        + "by WorkflowValidator.", "not validated by WorkflowValidator"),
                        new StepFieldSchema("outputs", MAP, false,
                                "Map of output key -> dot-notation path into the response JSON body.")
                ));
    }

    private static StepTypeSchema dockerSchema() {
        return new StepTypeSchema("docker",
                "Runs a command inside a Docker container (`uses: docker://<image>`, or `docker://` "
                        + "alone for the default Conductor runner image). Requires `runs-on: conductor` or "
                        + "`runs-on: self-hosted` at the job level.",
                List.of(
                        new StepFieldSchema("env", MAP, false,
                                "Map of environment variables for the container. Values are interpolated."),
                        new StepFieldSchema("run", STRING, false,
                                "Shell command(s) to execute inside the container."),
                        new StepFieldSchema("timeout_minutes", INTEGER, false,
                                "How long to wait for the container to finish. Documented default 5 / max "
                                        + "120, but — unlike claude-code/agent — not range-checked by "
                                        + "WorkflowValidator.", "not range-validated by WorkflowValidator"),
                        new StepFieldSchema("artifacts", ARRAY, false,
                                "List of {name, path} artifacts this step produces, downloadable by "
                                        + "downstream jobs via needs.JOB.artifacts.NAME.",
                                "each entry needs name (matching ^[a-z0-9_-]{1,160}$) and path; requires "
                                        + "runs-on: self-hosted (conductor-hosted docker artifact upload isn't "
                                        + "supported yet)")
                ));
    }

    private static StepTypeSchema kestraSchema() {
        return new StepTypeSchema("kestra",
                "Triggers a flow in a connected Kestra instance and optionally waits for it to finish.",
                List.of(
                        new StepFieldSchema("namespace", STRING, true, "Kestra flow namespace."),
                        new StepFieldSchema("flow_id", STRING, true, "Kestra flow ID."),
                        new StepFieldSchema("base_url", STRING, false,
                                "Kestra instance URL. Defaults to the KESTRA_BASE_URL env var, else the "
                                        + "conductor-hosted default."),
                        new StepFieldSchema("api_token", STRING, false,
                                "Kestra API bearer token. Defaults to the KESTRA_API_TOKEN env var."),
                        new StepFieldSchema("inputs", MAP, false,
                                "Input values passed to the Kestra flow. Interpolated."),
                        new StepFieldSchema("wait", BOOLEAN, false,
                                "Wait for the flow to complete before continuing. Defaults to true."),
                        new StepFieldSchema("timeout_minutes", INTEGER, false,
                                "How long to wait before timing out. Defaults to 60. Not range-checked by "
                                        + "WorkflowValidator.", "not range-validated by WorkflowValidator"),
                        new StepFieldSchema("fail_on_warning", BOOLEAN, false,
                                "Treat a Kestra WARNING execution state as a failure. Defaults to false."),
                        new StepFieldSchema("outputs", MAP, false,
                                "Map of output key -> dot-notation path into the Kestra execution response.")
                ));
    }

    private static StepTypeSchema conditionSchema() {
        return new StepTypeSchema("condition",
                "Branches execution to one of two jobs based on a boolean expression. Always succeeds "
                        + "itself; must be the last step in its job; cannot declare continue-on-error.",
                List.of(
                        new StepFieldSchema("expression", STRING, true,
                                "Boolean expression, evaluated the same way as a job's if: (comparison/"
                                        + "logical operators plus always()/success()/failure())."),
                        new StepFieldSchema("then", STRING, true,
                                "Job id to enqueue when the expression is true.",
                                "must reference an existing job in this workflow and cannot create a cycle"),
                        new StepFieldSchema("else", STRING, true,
                                "Job id to enqueue when the expression is false.",
                                "must reference an existing job in this workflow and cannot create a cycle")
                ));
    }

    // --- uses:/with: step types (fields live under the step's `with:` block) ---

    private static StepTypeSchema integrationSchema() {
        return new StepTypeSchema("integration",
                "Fetches data (read-only) from a connected integration without embedding credentials "
                        + "in the workflow YAML; the ACTIVE connection is resolved at runtime.",
                List.of(
                        new StepFieldSchema("connector", STRING, true,
                                "Connector ID of an ACTIVE integration (e.g. gsc, posthog, revenuecat, "
                                        + "gcp-billing) — see the list_connector_catalog MCP tool / GET "
                                        + "/integrations/catalog."),
                        new StepFieldSchema("operation", STRING, false,
                                "Named operation to run. Omit to use the connector's default fetch."),
                        new StepFieldSchema("params", MAP, false,
                                "Optional map of connector-specific override parameters.")
                ));
    }

    private static StepTypeSchema agentSchema() {
        return new StepTypeSchema("agent",
                "Hands a task to a project-scoped AI agent (Automation -> Agents) and exposes its "
                        + "answer as step outputs. Runtime (api vs claude-code) is resolved fresh on every "
                        + "run from the agent's config / the project's configured credentials — never "
                        + "declared on the step.",
                List.of(
                        new StepFieldSchema("agent", STRING, true,
                                "Slug (or id) of an agent defined in this project. Interpolated — blank "
                                        + "after interpolation fails the step."),
                        new StepFieldSchema("task", STRING, false,
                                "The instruction for the agent. Interpolated; not itself checked by "
                                        + "WorkflowValidator."),
                        new StepFieldSchema("context", MAP, false,
                                "Optional map of structured data passed to the agent. Each value is "
                                        + "interpolated."),
                        new StepFieldSchema("output_schema", OBJECT, false,
                                "Optional shape requesting a structured JSON answer from the agent."),
                        new StepFieldSchema("timeout_minutes", INTEGER, false,
                                "Wall-clock timeout; only enforced by the claude-code runtime (the api "
                                        + "runtime is bounded by maxToolTurns instead).", "1-120"),
                        new StepFieldSchema("credentials", ARRAY, false,
                                "List of {connector, as} entries minting a runtime credential — claude-code "
                                        + "runtime only; fails the step on the api runtime.",
                                "connector must support the CREDENTIAL capability (list_connector_catalog); "
                                        + "as must not collide with a reserved env key"),
                        new StepFieldSchema("env", MAP, false,
                                "Plain map of extra env vars — claude-code runtime only; fails the step on "
                                        + "the api runtime.", "keys must not collide with a reserved env key")
                ));
    }

    private static StepTypeSchema claudeCodeSchema() {
        return new StepTypeSchema("claude-code",
                "Hands a prompt to Claude Code running headlessly (`claude -p`) inside the Conductor "
                        + "runner image. Requires the job's runs-on to be cloud-run, self-hosted, or a "
                        + "project runtime target (never the shared conductor runner) — subscription auth "
                        + "only, on every runtime.",
                List.of(
                        new StepFieldSchema("prompt", STRING, true,
                                "The instruction given to Claude Code. Interpolated."),
                        new StepFieldSchema("inputs", MAP, false,
                                "Map of filename -> content written to /conductor/inputs/ before Claude "
                                        + "Code starts. Values are interpolated.",
                                "each value must be a scalar (string/number/boolean), not a nested object"),
                        new StepFieldSchema("conductor_mcp", BOOLEAN, false,
                                "When true, wires up the Conductor MCP server so the prompt can call "
                                        + "Conductor tools. Defaults to false.", "must be a boolean"),
                        new StepFieldSchema("allowed_tools", STRING, false,
                                "Comma-separated allowlist passed to --allowedTools."),
                        new StepFieldSchema("max_turns", INTEGER, false,
                                "Maximum agent turns before Claude Code stops itself.",
                                "must be a positive integer"),
                        new StepFieldSchema("timeout_minutes", INTEGER, false,
                                "Hard wall-clock timeout for the whole step. Defaults to 30.", "1-120"),
                        new StepFieldSchema("output_schema", OBJECT, false,
                                "JSON Schema requesting a structured JSON answer, passed to --json-schema.",
                                "must be a map"),
                        new StepFieldSchema("credentials", ARRAY, false,
                                "List of {connector, as} entries. Mints a connector-issued runtime "
                                        + "credential and injects it into the container's env under the key "
                                        + "named by as.",
                                "connector must support the CREDENTIAL capability (list_connector_catalog); "
                                        + "as must not collide with a reserved env key (CONDUCTOR_* prefix or "
                                        + "CLAUDE_CODE_OAUTH_TOKEN)"),
                        new StepFieldSchema("env", MAP, false,
                                "Plain map of extra env vars for the container. Values are interpolated.",
                                "keys must not collide with a reserved env key"),
                        new StepFieldSchema("artifacts", ARRAY, false,
                                "List of {name, path} artifacts this step produces, downloadable by "
                                        + "downstream jobs via needs.JOB.artifacts.NAME. Unlike docker, "
                                        + "claude-code supports artifacts on any runtime (no self-hosted "
                                        + "requirement).",
                                "each entry needs name (matching ^[a-z0-9_-]{1,160}$) and path")
                ));
    }

    private static StepTypeSchema actionSchema() {
        return new StepTypeSchema("action",
                "Invokes a named outbound action (write/side-effect) on a connected integration without "
                        + "embedding credentials in the workflow YAML; the ACTIVE connection is resolved at "
                        + "runtime.",
                List.of(
                        new StepFieldSchema("connector", STRING, true,
                                "Connector ID of an ACTIVE integration with the ACTION capability (e.g. "
                                        + "discord) — see list_connector_catalog."),
                        new StepFieldSchema("action", STRING, true,
                                "Action id the connector declares (e.g. post_message) — see "
                                        + "list_integration_tools / list_connector_catalog."),
                        new StepFieldSchema("connection_id", STRING, false,
                                "Id of one specific connection to act through, for a project holding "
                                        + "several connections on the same connector (e.g. two Instagram "
                                        + "accounts). Omit it — the usual case — and the step resolves the "
                                        + "project's single ACTIVE connection for with.connector.",
                                "not checked by WorkflowValidator; at runtime the connection must exist, "
                                        + "be ACTIVE, and belong to with.connector — a miss fails the step "
                                        + "rather than falling back to connector-only resolution"),
                        new StepFieldSchema("input", MAP, false,
                                "Map passed to the action. String values are interpolated; other values "
                                        + "pass through as-is.")
                ));
    }

    private static List<InterpolationRoot> buildInterpolationRoots() {
        return List.of(
                new InterpolationRoot("event",
                        "Field from the trigger event payload — webhook body, workflow_dispatch inputs, a "
                                + "Work Item status-change field, or a dispatcher-built payload."),
                new InterpolationRoot("secrets",
                        "A project secret, referenced by its uppercase name."),
                new InterpolationRoot("inputs",
                        "A manual-dispatch input value declared under on.workflow_dispatch.inputs."),
                new InterpolationRoot("steps",
                        "An output or terminal result of a step in the current job. Exact forms: "
                                + "${{ steps.STEP_ID.outputs.KEY }}, ${{ steps.STEP_ID.result }} "
                                + "(success/failure/skipped). There is no bare ${{ steps.STEP_ID }} form."),
                new InterpolationRoot("needs",
                        "An output, terminal result, or artifact download URL of a completed upstream job. "
                                + "Exact forms: ${{ needs.JOB_ID.outputs.KEY }}, ${{ needs.JOB_ID.result }}, "
                                + "${{ needs.JOB_ID.artifacts.NAME }}. There is no bare ${{ needs.JOB_ID }} form."),
                new InterpolationRoot("loop",
                        "The current loop iteration number (1-based), inside a job with a loop: block. "
                                + "Exact form: ${{ loop.iteration }} — a bare ${{ loop }} silently resolves to "
                                + "an empty string rather than erroring.")
        );
    }

    private static List<InterpolationFunction> buildInterpolationFunctions() {
        List<InterpolationFunction> functions = new ArrayList<>();
        functions.add(new InterpolationFunction("always()",
                "Runs regardless of upstream outcome."));
        functions.add(new InterpolationFunction("success()",
                "True when every one of the job's needs ended SUCCESS (false on any FAILED, "
                        + "LOOP_EXHAUSTED, or SKIPPED). The implicit default when a job declares no if:."));
        functions.add(new InterpolationFunction("failure()",
                "True when any one of the job's needs ended FAILED or LOOP_EXHAUSTED (a SKIPPED need "
                        + "does not trip this)."));
        return List.copyOf(functions);
    }
}
