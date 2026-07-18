package com.conductor.knowledge.domain;

import com.conductor.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the domain lane a {@code KnowledgeSource} is stamped with at submit time --
 * {@code KnowledgeIngestionService#submit} calls this once, before insert; the result is never
 * re-resolved later even if the registry subsequently changes (see {@code KnowledgeSource#domain}).
 *
 * <p>Precedence: an explicit caller-supplied domain (validated against the project's ACTIVE registry;
 * an unknown or non-ACTIVE slug is rejected, not silently dropped) beats the first ACTIVE domain (in
 * slug order, for a deterministic result under overlapping patterns) whose {@code sourceTypePatterns}
 * glob-matches {@code sourceType}, which in turn beats {@code null} -- the generalist/unclassified lane.
 * A domain dismissed or deleted after a source was stamped is never a dispatch-time problem: routing
 * happens once here, and {@code LibrarianDispatchService} re-resolves the *agent* (not the domain) at
 * claim time, falling back to the generalist librarian if the owning agent is gone.
 */
@Component
public class KnowledgeDomainResolver {

    private final KnowledgeDomainRepository domainRepository;

    public KnowledgeDomainResolver(KnowledgeDomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    /**
     * @param explicitDomain caller's requested domain slug, or null to route by pattern.
     * @return the resolved domain slug, or null for the generalist lane.
     * @throws BusinessException if {@code explicitDomain} doesn't name an ACTIVE domain in this project.
     */
    public String resolve(String projectId, String explicitDomain, String sourceType) {
        if (explicitDomain != null && !explicitDomain.isBlank()) {
            KnowledgeDomain domain = domainRepository.findByProjectIdAndSlug(projectId, explicitDomain)
                    .filter(d -> d.getState() == KnowledgeDomainState.ACTIVE)
                    .orElseThrow(() -> new BusinessException(
                            "Unknown or inactive knowledge domain: " + explicitDomain));
            return domain.getSlug();
        }

        if (sourceType == null || sourceType.isBlank()) {
            return null;
        }
        List<KnowledgeDomain> active =
                domainRepository.findByProjectIdAndStateOrderBySlugAsc(projectId, KnowledgeDomainState.ACTIVE);
        for (KnowledgeDomain domain : active) {
            for (String pattern : domain.getSourceTypePatterns()) {
                if (matches(pattern, sourceType)) {
                    return domain.getSlug();
                }
            }
        }
        return null;
    }

    /** {@code *} is a wildcard; every other character is treated literally (regex-quoted). */
    private boolean matches(String globPattern, String value) {
        return Pattern.matches(globToRegex(globPattern), value);
    }

    private String globToRegex(String globPattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < globPattern.length(); i++) {
            char c = globPattern.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }
}
