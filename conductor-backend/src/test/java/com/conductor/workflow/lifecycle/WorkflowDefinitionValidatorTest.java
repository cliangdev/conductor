package com.conductor.workflow.lifecycle;

import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.ProjectSkill;
import com.conductor.repository.ProjectSkillRepository;
import com.conductor.workflow.WorkflowValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowDefinitionValidatorTest {

    private static final String PROJECT_ID = "proj-test";

    private final ObjectMapper mapper = new ObjectMapper();
    // No project-registered skills — only the built-in registry is bindable, so the unknown-skill case still fails.
    private final ProjectSkillRepository projectSkillRepository = mock(ProjectSkillRepository.class);
    private final WorkflowDefinitionValidator validator =
            new WorkflowDefinitionValidator(new SkillRegistry(mapper, projectSkillRepository), new SystemTriggerRegistry(mapper),
                    new PublishPlatformRegistry());

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private JsonNode engineering() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            return mapper.readTree(in);
        }
    }

    // A minimal structurally + semantically valid definition to mutate for negative cases.
    private static final String MINI = """
            {
              "schemaVersion": 1,
              "id": "MINI",
              "area": "MINI",
              "version": 1,
              "state": "DRAFT",
              "noun": "Item",
              "default_view": "list",
              "types": ["TASK"],
              "statuses": [
                {"id": "OPEN", "category": "open", "initial": true},
                {"id": "DONE", "category": "terminal", "terminal": true}
              ],
              "transitions": [
                {"from": "OPEN", "to": "DONE", "label": "Finish"}
              ]
            }
            """;

    @Test
    void engineeringExampleIsValid() throws Exception {
        WorkflowValidationResult result = validator.validate(PROJECT_ID, engineering());
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void miniDefinitionIsValid() throws Exception {
        assertThat(validator.validate(PROJECT_ID, json(MINI)).getErrors()).isEmpty();
    }

    // --- publishes_from: the status a publishing Workflow dispatches from ---

    private static final String PUBLISHING = """
            {
              "schemaVersion": 1, "id": "PUB", "area": "PUB", "version": 1, "state": "DRAFT",
              "noun": "Post", "default_view": "calendar", "types": ["POST"],
              "asset_types": ["instagram_post"],
              "publishes_from": "QUEUED",
              "statuses": [
                {"id": "DRAFT", "category": "open", "initial": true},
                {"id": "QUEUED", "category": "in_progress"},
                {"id": "LIVE", "category": "terminal", "terminal": true}
              ],
              "transitions": [
                {"from": "DRAFT", "to": "QUEUED", "label": "Queue"},
                {"from": "QUEUED", "to": "LIVE", "label": "Live"}
              ]
            }
            """;

    @Test
    void marketingAndAutopilotExamplesAreValid() throws Exception {
        for (String example : new String[] {"marketing", "marketing-autopilot"}) {
            try (InputStream in = getClass().getResourceAsStream("/schema/examples/" + example + ".workflow.json")) {
                assertThat(validator.validate(PROJECT_ID, mapper.readTree(in)).getErrors()).as(example).isEmpty();
            }
        }
    }

    @Test
    void publishingWorkflowMayDeclareItsScheduledStatus() throws Exception {
        assertThat(validator.validate(PROJECT_ID, json(PUBLISHING)).getErrors()).isEmpty();
    }

    @Test
    void publishingWorkflowWithNeitherMarkerNorScheduledStatusIsRejected() throws Exception {
        String bad = PUBLISHING.replace("\"publishes_from\": \"QUEUED\",", "");
        assertThat(validator.validate(PROJECT_ID, json(bad)).getErrors())
                .anyMatch(e -> e.contains("must declare publishes_from"));
    }

    @Test
    void publishingWorkflowWithALegacyScheduledStatusNeedsNoMarker() throws Exception {
        String legacy = PUBLISHING.replace("\"publishes_from\": \"QUEUED\",", "").replace("QUEUED", "SCHEDULED");
        assertThat(validator.validate(PROJECT_ID, json(legacy)).getErrors()).isEmpty();
    }

    @Test
    void publishesFromMustNameAnExistingNonTerminalStatusThatReachesATerminalOne() throws Exception {
        assertThat(validator.validate(PROJECT_ID, json(PUBLISHING.replace("\"publishes_from\": \"QUEUED\"", "\"publishes_from\": \"NOPE\""))).getErrors())
                .anyMatch(e -> e.contains("publishes_from references unknown status: NOPE"));
        assertThat(validator.validate(PROJECT_ID, json(PUBLISHING.replace("\"publishes_from\": \"QUEUED\"", "\"publishes_from\": \"LIVE\""))).getErrors())
                .anyMatch(e -> e.contains("is terminal"));
        String deadEnd = PUBLISHING.replace("{\"from\": \"QUEUED\", \"to\": \"LIVE\", \"label\": \"Live\"}",
                "{\"from\": \"QUEUED\", \"to\": \"DRAFT\", \"label\": \"Back\"}, {\"from\": \"DRAFT\", \"to\": \"LIVE\", \"label\": \"Skip\"}");
        assertThat(validator.validate(PROJECT_ID, json(deadEnd)).getErrors())
                .anyMatch(e -> e.contains("no transition to a terminal status"));
    }

    @Test
    void nonPublishingWorkflowNeedsNoScheduledStatus() throws Exception {
        assertThat(validator.validate(PROJECT_ID, json(MINI)).getErrors()).isEmpty();
    }

    @Test
    void nullDefinitionRejected() {
        assertThat(validator.validate(PROJECT_ID, null).hasErrors()).isTrue();
    }

    @Test
    void structuralErrorRejectedWithSchemaPrefix() throws Exception {
        // default_view not in the enum -> structural failure from the JSON Schema.
        String bad = MINI.replace("\"default_view\": \"list\"", "\"default_view\": \"kanban\"");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.startsWith("schema:"));
    }

    @Test
    void missingInitialStatusRejected() throws Exception {
        String bad = MINI.replace("\"initial\": true", "\"initial\": false");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("exactly one status must be initial"));
    }

    @Test
    void deadEndStatusRejected() throws Exception {
        // STUCK is non-terminal with no outgoing edge -> cannot reach a terminal.
        String bad = """
                {
                  "schemaVersion": 1, "id": "DE", "area": "DE", "version": 1, "state": "DRAFT",
                  "noun": "Item", "default_view": "list", "types": ["TASK"],
                  "statuses": [
                    {"id": "OPEN", "category": "open", "initial": true},
                    {"id": "STUCK", "category": "in_progress"},
                    {"id": "DONE", "category": "terminal", "terminal": true}
                  ],
                  "transitions": [
                    {"from": "OPEN", "to": "DONE", "label": "Finish"},
                    {"from": "OPEN", "to": "STUCK", "label": "Get stuck"}
                  ]
                }
                """;
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("STUCK") && e.contains("dead-end"));
    }

    @Test
    void unknownSkillRejected() throws Exception {
        // Engineering, but bind a skill id that is not in the registry.
        String bad = mapper.writeValueAsString(engineering()).replace("conductor:implement", "conductor:ghost");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("unknown skill 'conductor:ghost'"));
    }

    @Test
    void transitionToUnknownStatusRejected() throws Exception {
        String bad = MINI.replace("\"to\": \"DONE\"", "\"to\": \"NOWHERE\"");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("unknown 'to' status: NOWHERE"));
    }

    @Test
    void unknownSystemTriggerRejected() throws Exception {
        // The schema now allows any open-string trigger; the registry gate (Java) rejects unregistered ones.
        String bad = MINI.replace(
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\"}",
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\", \"trigger\": \"connector_event\"}");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors())
                .anyMatch(e -> e.contains("unknown system trigger 'connector_event'"));
    }

    @Test
    void registeredStatusChangedTriggerAccepted() throws Exception {
        // A non-pr_merged system trigger is now bindable without a schema enum edit.
        String def = MINI.replace(
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\"}",
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\", \"trigger\": \"status_changed\"}");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(def));
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void statusChangedCycleRejected() throws Exception {
        // A -> B -> A both on status_changed would auto-loop at runtime; reject at publish. (A -> DONE keeps
        // reachability valid so the cycle check — not a dead-end error — is what fires.)
        String bad = """
                {
                  "schemaVersion": 1, "id": "CYC", "area": "CYC", "version": 1, "state": "DRAFT",
                  "noun": "Item", "default_view": "list", "types": ["TASK"],
                  "statuses": [
                    {"id": "OPEN", "category": "open", "initial": true},
                    {"id": "AA", "category": "in_progress"},
                    {"id": "BB", "category": "in_progress"},
                    {"id": "DONE", "category": "terminal", "terminal": true}
                  ],
                  "transitions": [
                    {"from": "OPEN", "to": "AA", "label": "Start"},
                    {"from": "AA", "to": "BB", "label": "Fwd", "trigger": "status_changed"},
                    {"from": "BB", "to": "AA", "label": "Back", "trigger": "status_changed"},
                    {"from": "AA", "to": "DONE", "label": "Finish"}
                  ]
                }
                """;
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(bad));
        assertThat(result.getErrors()).anyMatch(e -> e.contains("status_changed") && e.contains("auto-loop"));
    }

    @Test
    void projectRegisteredSkillAccepted() throws Exception {
        // A skill the project has registered (not a built-in) is bindable — no redeploy needed.
        ProjectSkill registered = new ProjectSkill();
        registered.setSkillId("marketing:seo-report");
        when(projectSkillRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(registered));
        String def = MINI.replace(
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\"}",
                "{\"from\": \"OPEN\", \"to\": \"DONE\", \"label\": \"Finish\","
                        + " \"steps\": [{\"kind\": \"skill\", \"mode\": \"BLOCKING\", \"skill\": \"marketing:seo-report\"}]}");
        WorkflowValidationResult result = validator.validate(PROJECT_ID, json(def));
        assertThat(result.getErrors()).isEmpty();
    }
}
