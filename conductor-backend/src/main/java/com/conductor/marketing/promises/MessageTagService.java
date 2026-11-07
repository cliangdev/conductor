package com.conductor.marketing.promises;

import com.conductor.exception.BusinessException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tags a target with the message it makes. A target carries one message, so tagging it again moves it. A message is
 * stored trimmed, lower-cased and with its whitespace collapsed, so one message is one string; the product feed's
 * {@code message:<tag>} rows are matched to it case-insensitively. A Post and a Creative must exist in the workspace
 * (a typo would otherwise tag nothing, silently); a keyword or a product page is not checked, because the Apple Ads
 * feed may not have synced it yet.
 */
@Service
public class MessageTagService {

    public static final int MAX_MESSAGE = 120;
    public static final int MAX_REF = 200;
    public static final int MAX_LABEL = 200;

    public static final String CREATIVE = "CREATIVE";
    public static final String PRODUCT_PAGE = "PRODUCT_PAGE";
    public static final String KEYWORD = "KEYWORD";
    public static final String PAYWALL_HEADLINE = "PAYWALL_HEADLINE";
    public static final String POST = "POST";

    /** In the order the ledger lists them. */
    public static final List<String> KINDS = List.of(CREATIVE, PRODUCT_PAGE, KEYWORD, PAYWALL_HEADLINE, POST);
    static final String KIND_LIST = String.join(", ", KINDS);

    /** A blank label means none. */
    public record TagInput(String message, String targetKind, String targetRef, String label) {}

    private final MarketingMessageTagRepository tags;
    private final NamedParameterJdbcTemplate jdbc;

    public MessageTagService(MarketingMessageTagRepository tags, DataSource dataSource) {
        this.tags = tags;
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Upserts the tag for the target. A 400 names a malformed field, a 404 a Post or Creative that is not the
     * workspace's. A re-tag replaces the message and the label and keeps the original creator and time.
     */
    @Transactional
    public MarketingMessageTag tag(String projectId, TagInput input, String actorUserId) {
        String message = normaliseMessage(input.message());
        String kind = requireKind(input.targetKind());
        String ref = requireRef(input.targetRef());
        String label = normaliseLabel(input.label());
        checkExists(projectId, kind, ref);

        tags.upsert(UUID.randomUUID().toString(), projectId, message, kind, ref, label, actorUserId);
        return tags.findByProjectIdAndTargetKindAndTargetRef(projectId, kind, ref).orElseThrow();
    }

    /** Removes the target's tag; false when it had none. Safe to repeat. */
    @Transactional
    public boolean untag(String projectId, String targetKind, String targetRef) {
        String kind = requireKind(targetKind);
        String ref = requireRef(targetRef);
        return tags.deleteByProjectIdAndTargetKindAndTargetRef(projectId, kind, ref) > 0;
    }

    /** Every tag of the workspace, ordered by message, then kind, then target ref. */
    @Transactional(readOnly = true)
    public List<MarketingMessageTag> list(String projectId) {
        return tags.findByProjectId(projectId).stream()
                .sorted(Comparator.comparing(MarketingMessageTag::getMessage)
                        .thenComparing(MarketingMessageTag::getTargetKind)
                        .thenComparing(MarketingMessageTag::getTargetRef))
                .toList();
    }

    // ---- input rules --------------------------------------------------------------------------------

    static String normaliseMessage(String raw) {
        String message = raw == null ? "" : raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (message.isEmpty() || message.length() > MAX_MESSAGE) {
            throw new BusinessException("message is required and at most " + MAX_MESSAGE + " characters");
        }
        return message;
    }

    static String requireKind(String raw) {
        String kind = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new BusinessException("targetKind must be one of " + KIND_LIST);
        }
        return kind;
    }

    static String requireRef(String raw) {
        String ref = raw == null ? "" : raw.trim();
        if (ref.isEmpty() || ref.length() > MAX_REF) {
            throw new BusinessException("targetRef is required and at most " + MAX_REF + " characters");
        }
        return ref;
    }

    private static String normaliseLabel(String raw) {
        String label = raw == null ? "" : raw.trim();
        if (label.length() > MAX_LABEL) {
            throw new BusinessException("label must be at most " + MAX_LABEL + " characters");
        }
        return label.isEmpty() ? null : label;
    }

    private void checkExists(String projectId, String kind, String ref) {
        String table = switch (kind) {
            case POST -> "work_items";
            case CREATIVE -> "creative";
            default -> null;
        };
        if (table == null) {
            return;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id AND project_id = :projectId)",
                new MapSqlParameterSource("id", ref).addValue("projectId", projectId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new EntityNotFoundException((POST.equals(kind) ? "Post" : "Creative") + " not found: " + ref);
        }
    }
}
