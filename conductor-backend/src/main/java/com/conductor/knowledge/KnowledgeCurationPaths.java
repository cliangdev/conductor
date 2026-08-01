package com.conductor.knowledge;

import com.conductor.knowledge.domain.KnowledgeDomain;

import java.util.List;

/**
 * Single source of truth for the wiki paths of the classpath-seeded curation policy pages -- see
 * {@code src/main/resources/knowledge/_curation.md} (root) and
 * {@code src/main/resources/knowledge/domains/_curation-skeleton.md} (per-domain, templated).
 *
 * <p>The path is <b>derived, not stored</b>. {@code knowledge_domains} has a {@code schema_page_path}
 * column but deliberately no curation counterpart: the {@code <slug>/_curation.md} convention is
 * stable, and a stored column would need a migration to backfill existing rows, would need to stay in
 * sync on every slug rename, and would need mirroring into {@code KnowledgeDomainDto} /
 * {@code list_knowledge_domains} -- all for no gain over deriving it from the slug wherever it's
 * needed. Single-sourcing that derivation here also avoids the mistake {@code _schema.md}'s path made:
 * that one is currently rebuilt independently in at least three places --
 * {@code KnowledgeWorkflowProvisioner.DomainSeed#schemaPagePath()},
 * {@code KnowledgeDomainService#insertSuggestedInNewTx}, and the stored {@code schema_page_path}
 * column itself. Nothing here should be duplicated elsewhere; import this class instead.
 */
public final class KnowledgeCurationPaths {

    /** File name of a curation policy page, root or per-domain. */
    public static final String FILE_NAME = "_curation.md";

    /** Path of the root curation policy page. */
    public static final String ROOT = FILE_NAME;

    private KnowledgeCurationPaths() {
    }

    /** The curation page path for {@code domain}'s own area, e.g. {@code "engineering/_curation.md"}. */
    public static String forDomain(KnowledgeDomain domain) {
        return domain.getPathPrefix() + FILE_NAME;
    }

    /** The curation page path for a domain slug, e.g. {@code "engineering"} -&gt; {@code "engineering/_curation.md"}. */
    public static String forSlug(String slug) {
        return slug + "/" + FILE_NAME;
    }

    /**
     * Resolves which curation page governs an arbitrary wiki page path: the ACTIVE domain whose
     * {@code pathPrefix} is the <b>longest</b> match wins -- not first-match in slug order (unlike
     * {@code KnowledgeDomainResolver}'s pattern routing), so the result stays deterministic even if
     * prefixes ever nest (e.g. {@code engineering/} and {@code engineering/platform/} both matching a
     * page under {@code engineering/platform/}). Falls back to {@link #ROOT} when no domain's prefix
     * matches -- including a root-level page with no {@code /}, and an empty domain list.
     *
     * <p>Callers are expected to pass only ACTIVE domains, the same convention
     * {@code KnowledgeDomainResolver} follows via {@code findByProjectIdAndStateOrderBySlugAsc} -- this
     * method does not itself filter by state.
     */
    public static String forPage(String pagePath, List<KnowledgeDomain> domains) {
        String bestPrefix = null;
        for (KnowledgeDomain domain : domains) {
            String prefix = domain.getPathPrefix();
            if (prefix != null && pagePath.startsWith(prefix)
                    && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        return bestPrefix != null ? bestPrefix + FILE_NAME : ROOT;
    }
}
