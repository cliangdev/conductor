package com.conductor.marketing.changes;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.MarketingChangeImportItem;
import com.conductor.generated.v2.model.MarketingChangeRecord;
import com.conductor.generated.v2.model.MarketingChangesImportRequest;
import com.conductor.generated.v2.model.MarketingChangesImportResponse;
import com.conductor.integration.connector.marketing.MarketingInsightsSnapshotQuery;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingChangesController;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The change-log import end to end against real Postgres: what the endpoint creates in which status, that
 * nothing is coerced, that a second import changes nothing, that one bad record never takes the others
 * with it, that Work Items still on the v1 snapshot accept the new verdict options once V187 has run, and
 * that overdue reviews reach the weekly digest payload.
 */
class MarketingChangeImportApiTest extends AbstractNoneWebIntegrationTest {

    private static final String WORKFLOW = "MARKETING_CHANGES";
    private static final String V187 = "db/migration/V187__marketing_change_verdicts_and_import_keys.sql";

    @Autowired private MarketingChangesController controller;
    @Autowired private MarketingChangeImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkItemImportKeyRepository importKeys;
    @Autowired private MarketingInsightsSnapshotQuery snapshotQuery;
    @Autowired private WorkflowDefinitionResolver resolver;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = user();
        project = project(admin);
        member(project, admin, MemberRole.ADMIN);
        workflowSeeder.seedMarketingChanges(project);
        actAs(admin);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---- the full sample ---------------------------------------------------------------------------

    @Test
    void theFullSampleCreatesThirtyOneLiveThreePlannedAndSeventeenReviewedAndSkipsTheReferences() throws IOException {
        MarketingChangesImportResponse response = importRecords(sample());

        assertThat(response.getCreated()).isEqualTo(51);
        assertThat(response.getAlreadyImported()).isZero();
        assertThat(response.getSkipped()).hasSize(8)
                .allSatisfy(n -> assertThat(n.getReason()).isEqualTo("reference record, not a change"));
        assertThat(response.getRefused()).isEmpty();
        assertThat(response.getItems()).hasSize(51);
        assertThat(response.getItems().stream().collect(Collectors.groupingBy(MarketingChangeImportItem::getStatus,
                Collectors.counting()))).containsEntry("LIVE", 31L).containsEntry("PLANNED", 3L)
                .containsEntry("REVIEWED", 17L).hasSize(3);
        assertThat(statusCounts(project)).containsEntry("LIVE", 31L).containsEntry("PLANNED", 3L)
                .containsEntry("REVIEWED", 17L).hasSize(3);
    }

    @Test
    void importedItemsCarryTheirFieldsDatesAndStatusLikeAnyOtherWorkItem() throws IOException {
        MarketingChangesImportResponse response = importRecords(sample());

        String hurtId = recordWithVerdict("hurt");
        WorkItem hurt = itemFor(response, hurtId);
        assertThat(hurt.getCurrentStatus()).isEqualTo("REVIEWED");
        assertThat(hurt.getFields().get("verdict").asText()).isEqualTo("did_not_work");
        assertThat(hurt.getFields().get("outcome").asText()).endsWith("\nOriginal verdict: hurt");
        assertThat(hurt.getWorkflow()).isEqualTo(WORKFLOW);
        assertThat(hurt.getWorkflowVersion()).isEqualTo(1);
        assertThat(hurt.getType()).isEqualTo("ADS");
        assertThat(hurt.getFields().get("liveSince").asText()).isNotBlank();
        assertThat(hurt.getDueOn()).isNotNull();
        assertThat(hurt.getStartedAt()).isNotNull();
        assertThat(hurt.getCompletedAt()).isNotNull();
        assertThat(hurt.getDescription()).endsWith("Imported from rexipe change log (id " + hurtId + ")");

        ChangeRef expected = recordWithVerdictStartingWith("expected");
        WorkItem expectedItem = itemFor(response, expected.id());
        assertThat(expectedItem.getFields().get("verdict").asText()).isEqualTo("expected");
        assertThat(expectedItem.getFields().get("outcome").asText()).endsWith("\nOriginal verdict: " + expected.verdict());
        assertThat(expectedItem.getFields().get("outcome").asText()).contains(expected.verdict());

        WorkItem noEffect = itemFor(response, recordWithVerdict("no effect"));
        assertThat(noEffect.getFields().get("verdict").asText()).isEqualTo("no_effect");

        WorkItem live = itemsByStatus(response, "LIVE").get(0);
        assertThat(live.getCompletedAt()).isNull();
        assertThat(live.getFields().get("liveSince").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(live.getFields().has("verdict")).isFalse();

        List<Integer> sequences = response.getItems().stream()
                .map(i -> workItemRepository.findById(i.getId()).orElseThrow().getSequenceNumber()).sorted().toList();
        assertThat(sequences).doesNotHaveDuplicates();
        assertThat(sequences.get(sequences.size() - 1) - sequences.get(0)).isEqualTo(50);
    }

    @Test
    void everyImportedItemRemembersTheRecordItCameFrom() throws IOException {
        MarketingChangesImportResponse response = importRecords(sample());

        List<String> keys = importKeys.findImportedRecordIds(project.getId(), "rexipe",
                sample().stream().map(MarketingChangeRecord::getId).toList());
        assertThat(keys).hasSize(51);
        assertThat(response.getItems()).extracting(MarketingChangeImportItem::getRecordId)
                .containsExactlyInAnyOrderElementsOf(keys);
    }

    // ---- idempotency --------------------------------------------------------------------------------

    @Test
    void importingTheSameLogAgainCreatesNothingAndLeavesEditedItemsAlone() throws IOException {
        MarketingChangesImportResponse first = importRecords(sample());
        MarketingChangeImportItem edited = first.getItems().get(0);
        workItemService.patchWorkItem(project.getId(), edited.getId(), "Edited by a person", null, null, null, null,
                null, null, null, admin);
        long before = itemCount(project);

        MarketingChangesImportResponse second = importRecords(sample());

        assertThat(second.getCreated()).isZero();
        assertThat(second.getAlreadyImported()).isEqualTo(51);
        assertThat(second.getItems()).isEmpty();
        assertThat(second.getRefused()).isEmpty();
        assertThat(second.getSkipped()).hasSize(8);
        assertThat(itemCount(project)).isEqualTo(before).isEqualTo(51);
        assertThat(workItemRepository.findById(edited.getId()).orElseThrow().getTitle()).isEqualTo("Edited by a person");
    }

    @Test
    void aRecordAlreadyImportedIsNotRefusedBecauseTheLogNowSaysSomethingElse() {
        importRecords(List.of(record("c-1", "live").verdict(null)));

        MarketingChangesImportResponse again = importRecords(List.of(record("c-1", "reviewed").verdict("maybe")));

        assertThat(again.getAlreadyImported()).isEqualTo(1);
        assertThat(again.getRefused()).isEmpty();
    }

    @Test
    void theSameRecordIdFromAnotherSourceIsAnotherRecord() {
        importRecords(List.of(record("c-1", "live")), "rexipe");

        MarketingChangesImportResponse other = importRecords(List.of(record("c-1", "live")), "other-log");

        assertThat(other.getCreated()).isEqualTo(1);
        assertThat(itemCount(project)).isEqualTo(2);
    }

    @Test
    void theSameIdTwiceInOneRequestImportsTheFirstAndRefusesTheSecond() {
        MarketingChangesImportResponse response = importRecords(
                List.of(record("c-1", "live"), record("c-1", "planned")));

        assertThat(response.getCreated()).isEqualTo(1);
        assertThat(response.getRefused()).singleElement().satisfies(n -> {
            assertThat(n.getId()).isEqualTo("c-1");
            assertThat(n.getReason()).contains("duplicate id");
        });
    }

    // ---- refusals -----------------------------------------------------------------------------------

    @Test
    void anUnknownVerdictRefusesThatRecordByIdAndTheOthersStillImport() {
        MarketingChangesImportResponse response = importRecords(List.of(
                record("good-1", "reviewed").verdict("worked"),
                record("bad-1", "reviewed").verdict("maybe"),
                record("good-2", "live")));

        assertThat(response.getCreated()).isEqualTo(2);
        assertThat(response.getRefused()).singleElement().satisfies(n -> {
            assertThat(n.getId()).isEqualTo("bad-1");
            assertThat(n.getReason()).contains("maybe");
        });
        assertThat(itemCount(project)).isEqualTo(2);
        assertThat(importKeys.findImportedRecordIds(project.getId(), "rexipe", List.of("bad-1"))).isEmpty();
    }

    @Test
    void aRecordTheWorkflowRejectsIsRefusedWithItsReasonAndLeavesNoItemBehind() {
        MarketingChangesImportResponse response = importRecords(List.of(
                record("ok-1", "live"),
                record("huge-1", "live").hypothesis("h".repeat(20_001)),
                record("ok-2", "planned")));

        assertThat(response.getCreated()).isEqualTo(2);
        assertThat(response.getRefused()).singleElement().satisfies(n -> {
            assertThat(n.getId()).isEqualTo("huge-1");
            assertThat(n.getReason()).contains("hypothesis").contains("20000");
        });
        assertThat(itemCount(project)).isEqualTo(2);
        assertThat(importKeys.findImportedRecordIds(project.getId(), "rexipe", List.of("huge-1"))).isEmpty();
    }

    @Test
    void aRecordWithoutAnIdIsRefusedByItsPosition() {
        MarketingChangesImportResponse response = importRecords(List.of(record("c-1", "live"), record(null, "live")));

        assertThat(response.getCreated()).isEqualTo(1);
        assertThat(response.getRefused()).singleElement().satisfies(n -> assertThat(n.getId()).isEqualTo("#2"));
    }

    @Test
    void aRequestOfOnlyIdlessRecordsRefusesEachByPosition() {
        MarketingChangesImportResponse response = importRecords(List.of(record(null, "live"), record(" ", "planned")));

        assertThat(response.getCreated()).isZero();
        assertThat(response.getRefused()).extracting(n -> n.getId()).containsExactly("#1", "#2");
    }

    @Test
    void anEmptyRequestIsRejectedAndSoIsMoreThanFiveHundredRecords() {
        assertThatThrownBy(() -> importRecords(List.of())).isInstanceOf(BusinessException.class);

        List<MarketingChangeRecord> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(record("c-" + i, "planned"));
        }
        // The REST layer rejects it from the schema's maxItems (a 400)...
        assertThatThrownBy(() -> importRecords(tooMany)).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("500");
        // ...and the service holds the same cap for any other caller.
        List<MarketingChangeImportService.ChangeRecord> direct = tooMany.stream()
                .map(r -> new MarketingChangeImportService.ChangeRecord(r.getId(), null, null, r.getTitle(),
                        r.getStatus(), null, null, null, null, null, null, null, null, null, null, null, null))
                .toList();
        assertThatThrownBy(() -> importService.importRecords(project.getId(), null, direct, ProjectActor.of(admin)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500");
        assertThat(itemCount(project)).isZero();
    }

    // ---- who may import -----------------------------------------------------------------------------

    @Test
    void aReviewerOrAnOutsiderCannotImport() {
        User reviewer = user();
        member(project, reviewer, MemberRole.REVIEWER);
        actAs(reviewer);
        assertThatThrownBy(() -> importRecords(List.of(record("c-1", "live")))).isInstanceOf(ForbiddenException.class);

        actAs(user());
        assertThatThrownBy(() -> importRecords(List.of(record("c-1", "live")))).isInstanceOf(ForbiddenException.class);
        assertThat(itemCount(project)).isZero();
    }

    @Test
    void anEditorCanImport() {
        User editor = user();
        member(project, editor, MemberRole.CREATOR);
        actAs(editor);

        assertThat(importRecords(List.of(record("c-1", "live"))).getCreated()).isEqualTo(1);
    }

    @Test
    void aProjectWithoutTheChangesWorkflowIsNotFound() {
        Project bare = project(admin);
        member(bare, admin, MemberRole.ADMIN);

        assertThatThrownBy(() -> controller.importMarketingChanges(bare.getId(),
                new MarketingChangesImportRequest(List.of(record("c-1", "live")))))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- V187: the verdict options reach existing workspaces ----------------------------------------

    @Test
    void v187AddsTheVerdictOptionsToTheDefinitionAndItsSnapshotsSoAV1ItemAcceptsNoEffect() throws Exception {
        downgradeToTheThreeOriginalVerdicts(project);
        WorkItem pinned = workItemService.createWorkItem(project.getId(), "LISTING", "Pinned to v1", null, WORKFLOW,
                null, null, null, admin);
        assertThat(pinned.getWorkflowVersion()).isEqualTo(1);
        assertThatThrownBy(() -> setVerdict(pinned, "no_effect")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("'verdict' must be one of");

        runV187VerdictUpdate();

        assertThat(verdictOptions("workflow_definitions", project))
                .containsExactly("worked", "did_not_work", "no_effect", "expected", "inconclusive");
        assertThat(verdictOptions("workflow_definition_versions", project))
                .containsExactly("worked", "did_not_work", "no_effect", "expected", "inconclusive");
        setVerdict(pinned, "no_effect");
        setVerdict(pinned, "expected");
        assertThat(workItemRepository.findById(pinned.getId()).orElseThrow().getFields().get("verdict").asText())
                .isEqualTo("expected");
    }

    @Test
    void v187IsIdempotentAndKeepsAHandEditedOptionList() throws Exception {
        downgradeToTheThreeOriginalVerdicts(project);
        runV187VerdictUpdate();
        runV187VerdictUpdate();
        assertThat(verdictOptions("workflow_definitions", project)).containsExactly(
                "worked", "did_not_work", "no_effect", "expected", "inconclusive");

        setVerdictOptions(project, "[\"worked\", \"mixed\", \"expected\"]");
        runV187VerdictUpdate();
        runV187VerdictUpdate();
        assertThat(verdictOptions("workflow_definitions", project)).containsExactly(
                "worked", "mixed", "expected", "no_effect");
        assertThat(verdictOptions("workflow_definition_versions", project)).containsExactly(
                "worked", "mixed", "expected", "no_effect");
    }

    @Test
    void aFreshProjectAlreadyOffersAllFiveVerdicts() {
        assertThat(verdictOptions("workflow_definitions", project))
                .containsExactly("worked", "did_not_work", "no_effect", "expected", "inconclusive");
        assertThat(verdictOptions("workflow_definition_versions", project))
                .containsExactly("worked", "did_not_work", "no_effect", "expected", "inconclusive");
    }

    // ---- the weekly digest ---------------------------------------------------------------------------

    @Test
    void changesPastTheirReviewDateWithNoVerdictAppearInTheWeeklyDigestPayload() {
        MarketingChangesImportResponse response = importRecords(List.of(
                record("overdue-live", "live").title("Overdue live").reviewOn("2026-10-20"),
                record("overdue-planned", "planned").title("Overdue planned").reviewOn("2026-10-25 (four weeks)"),
                record("has-verdict", "live").title("Has a verdict").reviewOn("2026-10-10").verdict("inconclusive"),
                record("not-yet", "live").title("Not yet").reviewOn("2026-11-20"),
                record("reviewed", "reviewed").title("Reviewed").reviewOn("2026-10-01").verdict("worked"),
                record("undated", "live").title("Undated"),
                record("abandoned", "abandoned").title("Abandoned").reviewOn("2026-10-02"),
                record("due-today", "live").title("Due today").reviewOn("2026-11-05")));
        assertThat(response.getCreated()).isEqualTo(8);

        Map<String, Object> payload = snapshotQuery.fetch(project.getId(), Instant.parse("2026-11-05T10:00:00Z"));

        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) payload.get("changesDueForReview");
        assertThat(section).isNotNull();
        assertThat(section.get("label")).isEqualTo("Changes past their review date with no verdict");
        assertThat(section.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) section.get("changes");
        assertThat(changes).extracting(c -> c.get("title")).containsExactly("Overdue live", "Overdue planned");
        assertThat(changes.get(0)).containsEntry("status", "LIVE").containsEntry("dueOn", "2026-10-20")
                .containsEntry("displayId", displayId(response, "overdue-live"));
        assertThat(changes.get(1)).containsEntry("status", "PLANNED").containsEntry("dueOn", "2026-10-25");
        assertThat(changes.get(0).keySet()).containsExactly("displayId", "title", "status", "dueOn");
    }

    @Test
    void aChangeDropsOutOfTheDigestOnceItHasAVerdict() {
        MarketingChangesImportResponse response = importRecords(List.of(
                record("c-1", "live").title("Overdue").reviewOn("2026-10-20")));
        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        assertThat(snapshotQuery.fetch(project.getId(), now)).containsKey("changesDueForReview");

        setVerdict(workItemRepository.findById(response.getItems().get(0).getId()).orElseThrow(), "worked");

        assertThat(snapshotQuery.fetch(project.getId(), now)).doesNotContainKey("changesDueForReview");
    }

    @Test
    void theDigestPayloadHasNoChangesSectionForAProjectWithNothingDue() {
        importRecords(List.of(record("c-1", "live").reviewOn("2027-01-01")));

        assertThat(snapshotQuery.fetch(project.getId(), Instant.parse("2026-11-05T10:00:00Z")))
                .doesNotContainKey("changesDueForReview");
    }

    @Test
    void theDigestListsAtMostTwentyChangesButCountsThemAll() {
        List<MarketingChangeRecord> records = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            records.add(record("c-" + i, "live").title("Change " + i).reviewOn(LocalDate.of(2026, 10, 1).plusDays(i).toString()));
        }
        importRecords(records);

        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) snapshotQuery
                .fetch(project.getId(), Instant.parse("2026-11-05T10:00:00Z")).get("changesDueForReview");

        assertThat(section.get("count")).isEqualTo(25);
        assertThat((List<?>) section.get("changes")).hasSize(20);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private MarketingChangesImportResponse importRecords(List<MarketingChangeRecord> records) {
        return importRecords(records, null);
    }

    private MarketingChangesImportResponse importRecords(List<MarketingChangeRecord> records, String source) {
        MarketingChangesImportRequest request = new MarketingChangesImportRequest(records);
        if (source != null) {
            request.setSource(source);
        }
        return controller.importMarketingChanges(project.getId(), request).getBody();
    }

    /** A bare record: an id, the status, and a title. */
    private static MarketingChangeRecord record(String id, String status) {
        return new MarketingChangeRecord().id(id).status(status).title("Change " + id).type("seo")
                .date("2026-10-01");
    }

    private List<MarketingChangeRecord> sample() throws IOException {
        List<MarketingChangeRecord> records = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(MarketingChangeImportServiceTest.SAMPLE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line : reader.lines().filter(l -> !l.isBlank()).toList()) {
                records.add(mapper.readValue(line, MarketingChangeRecord.class));
            }
        }
        return records;
    }

    private record ChangeRef(String id, String verdict) {
    }

    private String recordWithVerdict(String verdict) throws IOException {
        return sample().stream().filter(r -> verdict.equals(r.getVerdict())).findFirst().orElseThrow().getId();
    }

    private ChangeRef recordWithVerdictStartingWith(String prefix) throws IOException {
        MarketingChangeRecord r = sample().stream()
                .filter(x -> x.getVerdict() != null && x.getVerdict().startsWith(prefix)).findFirst().orElseThrow();
        return new ChangeRef(r.getId(), r.getVerdict());
    }

    private WorkItem itemFor(MarketingChangesImportResponse response, String recordId) {
        String id = response.getItems().stream().filter(i -> recordId.equals(i.getRecordId())).findFirst()
                .orElseThrow().getId();
        return workItemRepository.findById(id).orElseThrow();
    }

    private List<WorkItem> itemsByStatus(MarketingChangesImportResponse response, String status) {
        return response.getItems().stream().filter(i -> status.equals(i.getStatus()))
                .map(i -> workItemRepository.findById(i.getId()).orElseThrow()).toList();
    }

    private static String displayId(MarketingChangesImportResponse response, String recordId) {
        return response.getItems().stream().filter(i -> recordId.equals(i.getRecordId())).findFirst().orElseThrow()
                .getDisplayId();
    }

    private Map<String, Long> statusCounts(Project p) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("SELECT current_status, COUNT(*) AS n FROM work_items WHERE project_id = ? AND workflow = ? "
                        + "GROUP BY current_status", rs -> {
                    counts.put(rs.getString("current_status"), rs.getLong("n"));
                }, p.getId(), WORKFLOW);
        return counts;
    }

    private long itemCount(Project p) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_items WHERE project_id = ? AND workflow = ?",
                Long.class, p.getId(), WORKFLOW);
    }

    private void setVerdict(WorkItem item, String verdict) {
        workItemService.patchWorkItem(project.getId(), item.getId(), null, null, null, null, null, null, null, null,
                Map.of("verdict", verdict), null, admin);
    }

    /** Rewrites the project's definition header and every snapshot to what prod holds today: three verdicts. */
    private void downgradeToTheThreeOriginalVerdicts(Project p) {
        setVerdictOptions(p, "[\"worked\", \"did_not_work\", \"inconclusive\"]");
    }

    private void setVerdictOptions(Project p, String optionsJson) {
        String where = "definition ->> 'id' = 'MARKETING_CHANGES'";
        String set = "SET definition = jsonb_set(definition, '{fields}', (SELECT jsonb_agg(CASE WHEN f.field ->> 'key' = 'verdict' "
                + "THEN jsonb_set(f.field, '{options}', ?::jsonb) ELSE f.field END ORDER BY f.ord) "
                + "FROM jsonb_array_elements(definition -> 'fields') WITH ORDINALITY AS f(field, ord)))";
        jdbc.update("UPDATE workflow_definitions " + set + " WHERE " + where + " AND project_id = ?", optionsJson,
                p.getId());
        jdbc.update("UPDATE workflow_definition_versions " + set + " WHERE " + where
                + " AND workflow_definition_id IN (SELECT id FROM workflow_definitions WHERE project_id = ?)",
                optionsJson, p.getId());
        resolver.evictAll();
    }

    private List<String> verdictOptions(String table, Project p) {
        String scope = "workflow_definitions".equals(table) ? "project_id = ?"
                : "workflow_definition_id IN (SELECT id FROM workflow_definitions WHERE project_id = ?)";
        String json = jdbc.queryForObject("SELECT (SELECT f ->> 'options' FROM jsonb_array_elements(definition -> 'fields') f "
                + "WHERE f ->> 'key' = 'verdict') FROM " + table + " WHERE definition ->> 'id' = 'MARKETING_CHANGES' AND "
                + scope, String.class, p.getId());
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs the verdict part of V187 (everything before it creates the import-key table, which Flyway already
     * created for this database) on one connection, since it uses session-local helper functions.
     */
    private void runV187VerdictUpdate() throws Exception {
        String sql = new String(new ClassPathResource(V187).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String verdictPart = sql.substring(0, sql.indexOf("CREATE TABLE work_item_import_key"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(verdictPart.getBytes(StandardCharsets.UTF_8)));
        }
        resolver.evictAll();
    }

    private User user() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Change Importer");
        return userRepository.save(u);
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setName("Change log");
        p.setKey("CL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private void member(Project p, User u, MemberRole role) {
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(u);
        m.setRole(role);
        memberRepository.save(m);
    }

    private void actAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }
}
