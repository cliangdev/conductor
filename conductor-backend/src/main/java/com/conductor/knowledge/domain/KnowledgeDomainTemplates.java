package com.conductor.knowledge.domain;

/**
 * Single shared {@code %DOMAIN_SLUG%}/{@code %DOMAIN_DISPLAY%} substitution for the classpath skeleton
 * resources rendered per-domain -- {@code domains/_suggested-skeleton.md} and
 * {@code domains/_curation-skeleton.md}. Used by both {@code KnowledgeDomainService} (approval-time
 * seeding of a gap-report domain's schema/curation pages) and {@code KnowledgeWorkflowProvisioner}
 * (registry-driven seeding of every ACTIVE domain's curation page); a plain static helper rather than a
 * new collaborator, since neither of those classes depends on the other and this substitution carries
 * no state or dependencies that would justify a Spring bean.
 */
public final class KnowledgeDomainTemplates {

    private KnowledgeDomainTemplates() {
    }

    /** Replaces every {@code %DOMAIN_SLUG%}/{@code %DOMAIN_DISPLAY%} placeholder in {@code template}. */
    public static String render(String template, KnowledgeDomain domain) {
        return template
                .replace("%DOMAIN_SLUG%", domain.getSlug())
                .replace("%DOMAIN_DISPLAY%", domain.getDisplayName());
    }
}
