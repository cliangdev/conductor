package com.conductor.knowledge.page;

import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read/write path for the wiki bundle: page path normalization, optimistic-concurrency batch writes
 * with revision history + link-graph maintenance, and the two virtual pages ({@code index.md},
 * {@code log.md}) an LLM librarian reads to orient itself. Auth is out of scope in this phase --
 * controllers (Phase 2) check project membership before calling in.
 */
@Service
public class KnowledgePageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePageService.class);

    static final String VIRTUAL_INDEX = "index.md";
    static final String VIRTUAL_LOG = "log.md";
    private static final int MAX_PATH_LENGTH = 512;
    private static final int LOG_REVISION_LIMIT = 100;

    private static final Pattern PATH_PATTERN = Pattern.compile("^[a-z0-9_][a-z0-9_/.-]*\\.md$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)]+)\\)");
    private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageRevisionRepository revisionRepository;
    private final KnowledgeLinkRepository linkRepository;
    private final KnowledgeSourceRepository sourceRepository;
    private final FrontmatterParser frontmatterParser;
    private final ObjectMapper objectMapper;

    public KnowledgePageService(KnowledgePageRepository pageRepository,
                                KnowledgePageRevisionRepository revisionRepository,
                                KnowledgeLinkRepository linkRepository,
                                KnowledgeSourceRepository sourceRepository,
                                FrontmatterParser frontmatterParser,
                                ObjectMapper objectMapper) {
        this.pageRepository = pageRepository;
        this.revisionRepository = revisionRepository;
        this.linkRepository = linkRepository;
        this.sourceRepository = sourceRepository;
        this.frontmatterParser = frontmatterParser;
        this.objectMapper = objectMapper;
    }

    /**
     * A write that's been validated and concurrency-checked, ready to apply. {@code current} is the
     * pre-write row (possibly a soft-deleted tombstone, possibly absent). {@code create} distinguishes
     * a fresh row (or resurrecting a tombstone) from a version-bump update, for revision change-kind.
     */
    private record Prepared(String path, KnowledgePage current, FrontmatterParser.Parsed parsed, boolean delete, boolean create) {
    }

    /**
     * Applies every write in {@code writes} atomically: any optimistic-concurrency conflict across the
     * whole batch aborts the entire batch (nothing written) with a {@link KnowledgeConflictException}
     * carrying every conflicting path. On success, rebuilds each written page's outgoing links, re-resolves
     * dangling links that now point at a newly-live path, and (if {@code sourceIds} is non-empty) marks
     * those ingestion sources PROCESSED in the same transaction -- so a crash never leaves a source
     * re-processed or a write silently lost.
     *
     * <p>Concurrency rules per write: a live page requires a matching {@code baseVersion} (null or stale
     * is a conflict). A path with no page, or only a soft-deleted tombstone, is "creatable" with
     * {@code baseVersion == null}; supplying a {@code baseVersion} against either is a conflict (the
     * page the caller thinks they're updating has vanished). Deletes require an exact {@code baseVersion}
     * match against a live page.
     *
     * <p>{@code writes} may be empty while {@code sourceIds} is not: that's the librarian's explicit "no
     * wiki change needed" ack for a batch, and still marks those sources PROCESSED so they don't rot
     * through the stale-processing sweep into DEAD.
     */
    @Transactional
    public List<PageWriteResult> batchWrite(String projectId, List<PageWrite> writes, List<String> sourceIds, Actor actor) {
        if (writes == null || writes.isEmpty()) {
            if (sourceIds != null && !sourceIds.isEmpty()) {
                markSourcesProcessed(projectId, sourceIds);
            }
            return List.of();
        }

        List<Prepared> prepared = new ArrayList<>();
        List<KnowledgeConflictException.Conflict> conflicts = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        for (PageWrite write : writes) {
            String path = normalizePath(write.path());
            if (!seenPaths.add(path)) {
                throw new BusinessException("Duplicate path in batch: " + path);
            }
            KnowledgePage current = pageRepository.findByProjectIdAndPath(projectId, path).orElse(null);

            if (write.delete()) {
                boolean ok = current != null && !current.isDeleted()
                        && write.baseVersion() != null && write.baseVersion() == current.getVersion();
                if (!ok) {
                    conflicts.add(conflictFor(path, current));
                    continue;
                }
                prepared.add(new Prepared(path, current, null, true, false));
                continue;
            }

            FrontmatterParser.Parsed parsed = frontmatterParser.parse(write.content());

            if (current == null || current.isDeleted()) {
                if (write.baseVersion() != null) {
                    conflicts.add(conflictFor(path, current));
                    continue;
                }
                prepared.add(new Prepared(path, current, parsed, false, true));
            } else {
                if (write.baseVersion() == null || write.baseVersion() != current.getVersion()) {
                    conflicts.add(conflictFor(path, current));
                    continue;
                }
                prepared.add(new Prepared(path, current, parsed, false, false));
            }
        }

        if (!conflicts.isEmpty()) {
            throw new KnowledgeConflictException(conflicts);
        }

        Map<String, Object> actorMap = toMap(actor);
        List<PageWriteResult> results = new ArrayList<>();
        for (Prepared p : prepared) {
            results.add(p.delete() ? applyDelete(p, sourceIds, actorMap) : applyUpsert(projectId, p, sourceIds, actorMap));
        }

        if (sourceIds != null && !sourceIds.isEmpty()) {
            markSourcesProcessed(projectId, sourceIds);
        }

        return results;
    }

    private void markSourcesProcessed(String projectId, List<String> sourceIds) {
        int updated = sourceRepository.markProcessed(projectId, sourceIds);
        if (updated < sourceIds.size()) {
            log.warn("markProcessed updated {}/{} sources for project {} -- the rest were already "
                    + "PROCESSED/DEAD or don't exist", updated, sourceIds.size(), projectId);
        }
    }

    private KnowledgeConflictException.Conflict conflictFor(String path, KnowledgePage current) {
        if (current == null || current.isDeleted()) {
            return new KnowledgeConflictException.Conflict(path, current == null ? 0 : current.getVersion(), null);
        }
        return new KnowledgeConflictException.Conflict(path, current.getVersion(),
                frontmatterParser.render(current.getFrontmatter(), current.getBody()));
    }

    private PageWriteResult applyUpsert(String projectId, Prepared p, List<String> sourceIds, Map<String, Object> actorMap) {
        FrontmatterParser.Parsed parsed = p.parsed();
        String canonical = frontmatterParser.render(parsed.frontmatter(), parsed.body());
        String hash = sha256Hex(canonical);

        KnowledgePage page = p.current() != null ? p.current() : new KnowledgePage();
        if (p.current() == null) {
            page.setProjectId(projectId);
            page.setPath(p.path());
        }
        int newVersion = p.current() == null ? 1 : p.current().getVersion() + 1;
        page.setVersion(newVersion);
        page.setDeleted(false);
        page.setPageType(parsed.type());
        page.setTitle(parsed.title());
        page.setDescription(parsed.description());
        page.setFrontmatter(parsed.frontmatter());
        page.setBody(parsed.body());
        page.setContentHash(hash);
        page = pageRepository.save(page);

        saveRevision(page, newVersion, parsed.frontmatter(), parsed.body(), hash,
                p.create() ? KnowledgePageRevision.ChangeKind.CREATE : KnowledgePageRevision.ChangeKind.UPDATE,
                actorMap, sourceIds);

        rebuildLinks(projectId, page);
        linkRepository.resolveDangling(projectId, p.path(), page.getId());

        return new PageWriteResult(p.path(), newVersion, hash);
    }

    private PageWriteResult applyDelete(Prepared p, List<String> sourceIds, Map<String, Object> actorMap) {
        KnowledgePage page = p.current();
        int newVersion = page.getVersion() + 1;
        page.setVersion(newVersion);
        page.setDeleted(true);
        page = pageRepository.save(page);

        saveRevision(page, newVersion, null, null, null, KnowledgePageRevision.ChangeKind.DELETE, actorMap, sourceIds);

        linkRepository.deleteByFromPageId(page.getId());
        linkRepository.unresolveLinksTo(page.getId());

        return new PageWriteResult(p.path(), newVersion, page.getContentHash());
    }

    private void saveRevision(KnowledgePage page, int version, Map<String, Object> frontmatter, String body, String hash,
                              KnowledgePageRevision.ChangeKind changeKind, Map<String, Object> actorMap, List<String> sourceIds) {
        KnowledgePageRevision revision = new KnowledgePageRevision();
        revision.setPage(page);
        revision.setVersion(version);
        revision.setFrontmatter(frontmatter);
        revision.setBody(body);
        revision.setContentHash(hash);
        revision.setChangeKind(changeKind);
        revision.setActor(actorMap);
        revision = revisionRepository.save(revision);

        if (sourceIds != null) {
            for (String sourceId : sourceIds) {
                revisionRepository.linkSource(revision.getId(), sourceId);
            }
        }
    }

    /** Deletes and re-extracts every outgoing link for a just-written page from its (new) body. */
    private void rebuildLinks(String projectId, KnowledgePage page) {
        linkRepository.deleteByFromPageId(page.getId());
        for (String rawTarget : extractLinkTargets(page.getBody())) {
            String resolvedPath = resolveLinkTarget(page.getPath(), rawTarget);
            if (resolvedPath == null) {
                continue; // external link, or a relative target that escapes the bundle root
            }
            KnowledgeLink link = new KnowledgeLink();
            link.setProjectId(projectId);
            link.setFromPageId(page.getId());
            link.setToPath(resolvedPath);
            pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, resolvedPath)
                    .ifPresent(target -> link.setResolvedPageId(target.getId()));
            linkRepository.save(link);
        }
    }

    private List<String> extractLinkTargets(String body) {
        List<String> targets = new ArrayList<>();
        if (body == null) {
            return targets;
        }
        Matcher matcher = MARKDOWN_LINK.matcher(body);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            int spaceIdx = raw.indexOf(' ');
            String target = spaceIdx > 0 ? raw.substring(0, spaceIdx) : raw;
            if (target.toLowerCase(Locale.ROOT).endsWith(".md") && !URI_SCHEME.matcher(target).find()) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** Resolves a bundle-absolute ({@code /a/b.md}) or relative link target against the writing page's directory. */
    private String resolveLinkTarget(String fromPath, String rawTarget) {
        if (rawTarget.startsWith("/")) {
            return rawTarget.substring(1).toLowerCase(Locale.ROOT);
        }
        String dir = dirOf(fromPath);
        String combined = dir.isEmpty() ? rawTarget : dir + "/" + rawTarget;
        return collapseRelativeSegments(combined);
    }

    /** Collapses {@code .}/{@code ..} segments; returns null if the path would escape the bundle root. */
    private String collapseRelativeSegments(String path) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.removeLast();
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack).toLowerCase(Locale.ROOT);
    }

    private String dirOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? "" : path.substring(0, idx);
    }

    /**
     * Multi-get of full page content. {@code index.md}/{@code log.md} resolve to the generated virtual
     * pages instead of a stored row; unknown/deleted paths are silently omitted (multi-get semantics).
     */
    @Transactional(readOnly = true)
    public List<PageView> getPages(String projectId, List<String> paths) {
        List<PageView> results = new ArrayList<>();
        List<String> realPaths = new ArrayList<>();
        boolean wantsIndex = false;
        boolean wantsLog = false;
        for (String raw : paths) {
            String p = normalizeForRead(raw);
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (p.equals(VIRTUAL_INDEX)) {
                wantsIndex = true;
            } else if (p.equals(VIRTUAL_LOG)) {
                wantsLog = true;
            } else {
                realPaths.add(p);
            }
        }
        if (wantsIndex) {
            results.add(buildVirtualIndex(projectId));
        }
        if (wantsLog) {
            results.add(buildVirtualLog(projectId));
        }
        if (!realPaths.isEmpty()) {
            for (KnowledgePage page : pageRepository.findByProjectIdAndPathInAndDeletedFalse(projectId, realPaths)) {
                results.add(toPageView(page));
            }
        }
        return results;
    }

    private PageView toPageView(KnowledgePage page) {
        String content = frontmatterParser.render(page.getFrontmatter(), page.getBody());
        return new PageView(page.getPath(), page.getVersion(), page.getPageType(), page.getTitle(), page.getDescription(), content);
    }

    private PageView buildVirtualIndex(String projectId) {
        List<KnowledgePage> pages = pageRepository.findByProjectIdAndDeletedFalseOrderByPath(projectId);
        Map<String, List<KnowledgePage>> byDir = new LinkedHashMap<>();
        for (KnowledgePage page : pages) {
            byDir.computeIfAbsent(dirOf(page.getPath()), d -> new ArrayList<>()).add(page);
        }

        StringBuilder sb = new StringBuilder("# Index\n\n");
        for (Map.Entry<String, List<KnowledgePage>> entry : byDir.entrySet()) {
            sb.append("## ").append(entry.getKey().isEmpty() ? "/" : "/" + entry.getKey()).append("\n\n");
            for (KnowledgePage page : entry.getValue()) {
                String title = page.getTitle() != null ? page.getTitle() : page.getPath();
                String desc = page.getDescription() != null ? " — " + page.getDescription() : "";
                sb.append("* [").append(title).append("](/").append(page.getPath()).append(")")
                        .append(desc).append(" (type: ").append(page.getPageType()).append(")\n");
            }
            sb.append('\n');
        }
        return new PageView(VIRTUAL_INDEX, 0, "index", "Index", null, sb.toString().stripTrailing() + "\n");
    }

    private PageView buildVirtualLog(String projectId) {
        List<KnowledgePageRevision> revisions =
                revisionRepository.findByPage_ProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, LOG_REVISION_LIMIT));
        Map<String, List<String>> refsByRevisionId = groupSourceRefs(revisions);
        Map<LocalDate, List<KnowledgePageRevision>> byDate = new LinkedHashMap<>();
        for (KnowledgePageRevision revision : revisions) {
            byDate.computeIfAbsent(revision.getCreatedAt().toLocalDate(), d -> new ArrayList<>()).add(revision);
        }

        StringBuilder sb = new StringBuilder("# Log\n\n");
        for (Map.Entry<LocalDate, List<KnowledgePageRevision>> entry : byDate.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");
            for (KnowledgePageRevision revision : entry.getValue()) {
                List<String> refs = refsByRevisionId.getOrDefault(revision.getId(), List.of());
                String suffix = refs.isEmpty() ? "" : " ← " + String.join(", ", refs);
                // The day heading above is the finest grouping a human needs, but "last updated X ago"
                // UI reads need real precision -- a day-only value parses as UTC midnight, making every
                // same-day write since yesterday's midnight read as "hours ago" regardless of how
                // recent it actually was. Carry the instant per-line so the frontend can diff against
                // it instead (see conductor-frontend/src/lib/knowledgeLog.ts).
                sb.append("* **").append(changeLabel(revision.getChangeKind())).append("** (")
                        .append(revision.getCreatedAt().toInstant()).append("): ")
                        .append(revision.getPage().getPath()).append(suffix).append("\n");
            }
            sb.append('\n');
        }
        return new PageView(VIRTUAL_LOG, 0, "log", "Log", null, sb.toString().stripTrailing() + "\n");
    }

    /** Title-case label for a revision's changeKind, e.g. CREATE -> "Create" -- matches the frontend
     *  parser's {@code toAction} in {@code knowledgeLog.ts}. */
    private static String changeLabel(KnowledgePageRevision.ChangeKind changeKind) {
        String name = changeKind.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    /** Revision history for one path, newest first, with actor + linked-source provenance. */
    @Transactional(readOnly = true)
    public List<RevisionView> getRevisions(String projectId, String path) {
        String p = normalizeForRead(path);
        KnowledgePage page = pageRepository.findByProjectIdAndPath(projectId, p).orElse(null);
        if (page == null) {
            return List.of();
        }
        List<KnowledgePageRevision> revisions = revisionRepository.findByPage_IdOrderByVersionDesc(page.getId());
        Map<String, List<String>> refsByRevisionId = groupSourceRefs(revisions);
        List<RevisionView> views = new ArrayList<>();
        for (KnowledgePageRevision revision : revisions) {
            List<String> refs = refsByRevisionId.getOrDefault(revision.getId(), List.of());
            Actor actor = revision.getActor() == null ? null : objectMapper.convertValue(revision.getActor(), Actor.class);
            views.add(new RevisionView(revision.getVersion(), revision.getChangeKind(), actor, revision.getCreatedAt(), refs));
        }
        return views;
    }

    /** One query for every revision's source refs, grouped by revision id -- instead of one query per
     *  revision (see {@link KnowledgePageRevisionRepository#findSourceRefsByRevisionIds}). */
    private Map<String, List<String>> groupSourceRefs(List<KnowledgePageRevision> revisions) {
        if (revisions.isEmpty()) {
            return Map.of();
        }
        List<String> ids = revisions.stream().map(KnowledgePageRevision::getId).toList();
        Map<String, List<String>> refsByRevisionId = new HashMap<>();
        for (KnowledgePageRevisionRepository.RevisionSourceRef row : revisionRepository.findSourceRefsByRevisionIds(ids)) {
            refsByRevisionId.computeIfAbsent(row.getRevisionId(), k -> new ArrayList<>()).add(row.getSourceRef());
        }
        return refsByRevisionId;
    }

    /**
     * Normalizes and validates a page path for a write: trims, strips a leading {@code /}, lowercases,
     * rejects {@code ..} segments, enforces the {@code [a-z0-9_][a-z0-9_/.-]*\.md} shape (underscore
     * allowed as a leading character so {@code _schema.md}/{@code _lint/...} are valid pages), a
     * {@value #MAX_PATH_LENGTH}-char length cap, and the two reserved virtual paths.
     */
    String normalizePath(String rawPath) {
        String p = normalizeForRead(rawPath);
        if (p == null || p.isEmpty()) {
            throw new BusinessException("Path is required");
        }
        if (p.length() > MAX_PATH_LENGTH) {
            throw new BusinessException("Path exceeds " + MAX_PATH_LENGTH + " characters: " + rawPath);
        }
        if (p.contains("..")) {
            throw new BusinessException("Path must not contain '..': " + rawPath);
        }
        if (!PATH_PATTERN.matcher(p).matches()) {
            throw new BusinessException("Invalid page path: " + rawPath);
        }
        if (p.equals(VIRTUAL_INDEX) || p.equals(VIRTUAL_LOG)) {
            throw new BusinessException("Path is reserved: " + p);
        }
        return p;
    }

    private String normalizeForRead(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String p = rawPath.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.toLowerCase(Locale.ROOT);
    }

    private String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Actor actor) {
        return actor == null ? null : objectMapper.convertValue(actor, Map.class);
    }
}
