package com.conductor.workflow.lifecycle;

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
            new WorkflowDefinitionValidator(new SkillRegistry(mapper, projectSkillRepository));

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
