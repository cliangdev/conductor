package com.conductor.knowledge.controller;

import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.api.KnowledgeApi;
import com.conductor.generated.model.KnowledgeActor;
import com.conductor.generated.model.KnowledgeOrigin;
import com.conductor.generated.model.KnowledgePageBatchWriteRequest;
import com.conductor.generated.model.KnowledgePageBatchWriteResponse;
import com.conductor.generated.model.KnowledgePageRevisionView;
import com.conductor.generated.model.KnowledgePageView;
import com.conductor.generated.model.KnowledgePageWrite;
import com.conductor.generated.model.KnowledgePageWriteResult;
import com.conductor.generated.model.KnowledgeSearchHit;
import com.conductor.generated.model.KnowledgeSourceCounts;
import com.conductor.generated.model.KnowledgeSourceDto;
import com.conductor.generated.model.KnowledgeSourceReceipt;
import com.conductor.generated.model.KnowledgeSourceStatus;
import com.conductor.generated.model.KnowledgeSourceSubmitRequest;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSourceCountsView;
import com.conductor.knowledge.KnowledgeSourceView;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.knowledge.page.PageView;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.knowledge.page.PageWriteResult;
import com.conductor.knowledge.page.RevisionView;
import com.conductor.knowledge.page.SearchHit;
import com.conductor.security.ApiKeyAuthenticationToken;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * External {@code /api/v1} surface for the Knowledge Center: the ingestion inbox
 * ({@link KnowledgeIngestionService}) and the OKF wiki bundle ({@link KnowledgePageService},
 * {@link KnowledgeSearchService}). One controller because the spec's single {@code knowledge} tag
 * produces one generated interface -- see {@code KnowledgeApi}.
 */
@RestController
public class KnowledgeController implements KnowledgeApi {

    private final KnowledgeIngestionService ingestionService;
    private final KnowledgePageService pageService;
    private final KnowledgeSearchService searchService;
    private final ProjectSecurityService projectSecurityService;

    public KnowledgeController(KnowledgeIngestionService ingestionService,
                               KnowledgePageService pageService,
                               KnowledgeSearchService searchService,
                               ProjectSecurityService projectSecurityService) {
        this.ingestionService = ingestionService;
        this.pageService = pageService;
        this.searchService = searchService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<KnowledgeSourceReceipt> submitKnowledgeSource(String projectId,
                                                                         KnowledgeSourceSubmitRequest request) {
        Caller caller = requireProjectAccess(projectId);
        KnowledgeSubmission submission = new KnowledgeSubmission(
                projectId, request.getSourceType(), request.getSourceRef(), request.getTitle(),
                request.getContentType(), request.getPayload(), request.getOccurredAt(), request.getDedupKey(),
                caller.origin(), request.getMetadata());
        SourceReceipt receipt = ingestionService.submit(submission);
        return ResponseEntity.status(202).body(toDto(receipt));
    }

    @Override
    public ResponseEntity<List<KnowledgeSourceDto>> listKnowledgeSources(String projectId, KnowledgeSourceStatus status,
                                                                          String ids) {
        requireProjectAccess(projectId);
        List<KnowledgeSourceView> views;
        if (ids != null && !ids.isBlank()) {
            views = ingestionService.getSources(projectId, splitCsv(ids));
        } else {
            com.conductor.knowledge.KnowledgeSourceStatus domainStatus = status != null
                    ? com.conductor.knowledge.KnowledgeSourceStatus.valueOf(status.name())
                    : com.conductor.knowledge.KnowledgeSourceStatus.PENDING;
            views = ingestionService.listSources(projectId, domainStatus);
        }
        return ResponseEntity.ok(views.stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<KnowledgeSourceCounts> getKnowledgeSourceCounts(String projectId) {
        requireProjectAccess(projectId);
        return ResponseEntity.ok(toDto(ingestionService.getSourceCounts(projectId)));
    }

    @Override
    public ResponseEntity<KnowledgePageBatchWriteResponse> batchWriteKnowledgePages(
            String projectId, KnowledgePageBatchWriteRequest request) {
        Caller caller = requireProjectAccess(projectId);
        List<PageWrite> writes = request.getWrites().stream()
                .map(this::toDomain)
                .toList();
        List<PageWriteResult> results = pageService.batchWrite(projectId, writes, request.getSourceIds(), caller.actor());

        KnowledgePageBatchWriteResponse response = new KnowledgePageBatchWriteResponse();
        response.setResults(results.stream().map(this::toDto).toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<List<KnowledgePageView>> getKnowledgePages(String projectId, String paths) {
        requireProjectAccess(projectId);
        List<PageView> pages = pageService.getPages(projectId, splitCsv(paths));
        return ResponseEntity.ok(pages.stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<KnowledgePageView> getKnowledgeIndex(String projectId) {
        requireProjectAccess(projectId);
        List<PageView> pages = pageService.getPages(projectId, List.of("index.md"));
        return ResponseEntity.ok(toDto(pages.get(0)));
    }

    @Override
    public ResponseEntity<List<KnowledgeSearchHit>> searchKnowledge(String projectId, String q, String type,
                                                                     String pathPrefix, Integer limit) {
        requireProjectAccess(projectId);
        List<SearchHit> hits = searchService.search(projectId, q, type, pathPrefix, limit);
        return ResponseEntity.ok(hits.stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<List<KnowledgePageRevisionView>> listKnowledgePageRevisions(String projectId, String path) {
        requireProjectAccess(projectId);
        List<RevisionView> revisions = pageService.getRevisions(projectId, path);
        return ResponseEntity.ok(revisions.stream().map(this::toDto).toList());
    }

    // ---- auth ----

    /**
     * Result of {@link #requireProjectAccess}: the caller's project membership has already been
     * verified, and {@code actor}/{@code origin} carry its identity for provenance on writes.
     */
    private record Caller(Actor actor, KnowledgeSubmission.Origin origin) {
    }

    /**
     * Verifies the authenticated caller may act on {@code projectId} and derives its Actor/Origin
     * provenance in the same step -- both a user JWT/user-API-key principal ({@link User}, checked
     * via {@link ProjectSecurityService#isProjectMember}) and a project-scoped API key principal
     * ({@link ApiKeyAuthenticationToken}, whose {@code projectId} must equal the path {@code projectId})
     * are accepted, mirroring {@code DaemonEventsController#requireProjectApiKeyScopeForRun}.
     */
    private Caller requireProjectAccess(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (principal instanceof User user) {
            if (!projectSecurityService.isProjectMember(projectId, user.getId())) {
                throw new ForbiddenException("Not a member of this project");
            }
            return new Caller(new Actor("user", user.getId(), null),
                    new KnowledgeSubmission.Origin("USER", user.getId()));
        }
        if (auth instanceof ApiKeyAuthenticationToken && projectId.equals(principal)) {
            return new Caller(new Actor("agent", projectId, null),
                    new KnowledgeSubmission.Origin("API_KEY", projectId));
        }
        throw new ForbiddenException("Not a member of this project");
    }

    private List<String> splitCsv(String csv) {
        List<String> values = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    // ---- DTO <-> domain mapping ----

    private KnowledgeSourceReceipt toDto(SourceReceipt receipt) {
        KnowledgeSourceReceipt dto = new KnowledgeSourceReceipt();
        dto.setSourceId(receipt.sourceId());
        dto.setStatus(receipt.status() == SourceReceipt.Status.ACCEPTED
                ? KnowledgeSourceReceipt.StatusEnum.ACCEPTED
                : KnowledgeSourceReceipt.StatusEnum.DUPLICATE);
        return dto;
    }

    private KnowledgeSourceDto toDto(KnowledgeSourceView v) {
        KnowledgeSourceDto dto = new KnowledgeSourceDto();
        dto.setId(v.id());
        dto.setProjectId(v.projectId());
        dto.setSourceType(v.sourceType());
        dto.setSourceRef(v.sourceRef());
        dto.setTitle(v.title());
        dto.setContentType(v.contentType());
        dto.setPayload(v.payload());
        dto.setPayloadOffloaded(v.payloadOffloaded());
        dto.setMetadata(v.metadata());
        if (v.origin() != null) {
            KnowledgeOrigin origin = new KnowledgeOrigin();
            origin.setKind(v.origin().kind());
            origin.setId(v.origin().id());
            dto.setOrigin(origin);
        }
        dto.setOccurredAt(v.occurredAt());
        dto.setReceivedAt(v.receivedAt());
        dto.setStatus(KnowledgeSourceStatus.valueOf(v.status().name()));
        dto.setAttempts(v.attempts());
        dto.setErrorMessage(v.errorMessage());
        dto.setPurgedAt(v.purgedAt());
        return dto;
    }

    private KnowledgeSourceCounts toDto(KnowledgeSourceCountsView v) {
        KnowledgeSourceCounts dto = new KnowledgeSourceCounts();
        dto.setPending(v.pending());
        dto.setProcessing(v.processing());
        dto.setProcessed(v.processed());
        dto.setDead(v.dead());
        return dto;
    }

    private PageWrite toDomain(KnowledgePageWrite w) {
        return new PageWrite(w.getPath(), w.getContent(), w.getBaseVersion(), Boolean.TRUE.equals(w.getDelete()));
    }

    private KnowledgePageWriteResult toDto(PageWriteResult r) {
        KnowledgePageWriteResult dto = new KnowledgePageWriteResult();
        dto.setPath(r.path());
        dto.setVersion(r.version());
        dto.setContentHash(r.contentHash());
        return dto;
    }

    private KnowledgePageView toDto(PageView p) {
        KnowledgePageView dto = new KnowledgePageView();
        dto.setPath(p.path());
        dto.setVersion(p.version());
        dto.setType(p.type());
        dto.setTitle(p.title());
        dto.setDescription(p.description());
        dto.setContent(p.content());
        return dto;
    }

    private KnowledgeSearchHit toDto(SearchHit h) {
        KnowledgeSearchHit dto = new KnowledgeSearchHit();
        dto.setPath(h.path());
        dto.setType(h.type());
        dto.setTitle(h.title());
        dto.setDescription(h.description());
        dto.setSnippet(h.snippet());
        dto.setRank(h.rank());
        return dto;
    }

    private KnowledgePageRevisionView toDto(RevisionView v) {
        KnowledgePageRevisionView dto = new KnowledgePageRevisionView();
        dto.setVersion(v.version());
        dto.setChangeKind(KnowledgePageRevisionView.ChangeKindEnum.valueOf(v.changeKind().name()));
        if (v.actor() != null) {
            KnowledgeActor actor = new KnowledgeActor();
            actor.setKind(v.actor().kind());
            actor.setId(v.actor().id());
            actor.setWorkflowRunId(v.actor().workflowRunId());
            dto.setActor(actor);
        }
        dto.setCreatedAt(v.createdAt());
        dto.setSourceRefs(v.sourceRefs());
        return dto;
    }
}
