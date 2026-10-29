package com.conductor.service;

import com.conductor.entity.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Small, stateless facts about a document's content: what kind it is and its hash. */
public final class DocumentContent {

    private DocumentContent() {}

    /**
     * An HTML document is one whose content type is {@code text/html} (parameters such as a charset are
     * ignored) or whose filename ends in {@code .html} or {@code .htm}, case-insensitive. This is the single
     * place that decides it.
     */
    public static boolean isHtml(String contentType, String filename) {
        if (contentType != null) {
            String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (base.equals("text/html")) {
                return true;
            }
        }
        if (filename != null) {
            String name = filename.toLowerCase(Locale.ROOT);
            return name.endsWith(".html") || name.endsWith(".htm");
        }
        return false;
    }

    public static boolean isHtml(Document document) {
        return isHtml(document.getContentType(), document.getFilename());
    }

    /** SHA-256 of the UTF-8 bytes as lowercase hex, or null when there is no content. */
    public static String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
