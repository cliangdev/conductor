package com.conductor.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps images embedded in a project doc from rotting.
 *
 * <p>Uploading an image returns a <em>signed</em> URL, and a signed URL expires (15 minutes by
 * default). Storing that URL in the document's Markdown — which is what happened before this existed —
 * means every image in every doc is a dead link within the hour.
 *
 * <p>So stored content carries a stable marker instead:
 * {@code conductor-image:projects/{projectId}/docs/{docId}/images/{file}}. {@link #normalize} rewrites
 * a signed URL back to its marker on the way in (whoever is writing — the editor pasting an upload
 * response, or an agent round-tripping content it just read), and {@link #render} mints a fresh signed
 * URL on the way out. Markdown in the database therefore never contains a credential or an expiry.
 */
public final class DocImageMarkers {

    public static final String SCHEME = "conductor-image:";

    /**
     * The storage path of a doc image, wherever it appears in a URL. Deliberately anchored on the
     * {@code projects/…/docs/…/images/…} shape rather than on a host, so it matches a GCS signed URL, a
     * local {@code /api/v1/local-files/…} URL, and any future storage backend alike. Trailing query
     * string (the signature) is dropped.
     */
    private static final Pattern SIGNED_IMAGE_URL = Pattern.compile(
            "https?://[^\\s)\"']*?(projects/[^/\\s)\"']+/docs/[^/\\s)\"']+/images/[^/\\s)\"'?]+)(?:\\?[^\\s)\"']*)?");

    /** A marker and its storage path. */
    private static final Pattern MARKER = Pattern.compile(
            Pattern.quote(SCHEME) + "([^\\s)\"']+)");

    private DocImageMarkers() {
    }

    /** Storage path for a newly uploaded doc image. */
    public static String storagePath(String projectId, String docId, String filename) {
        return "projects/" + projectId + "/docs/" + docId + "/images/" + filename;
    }

    public static String marker(String storagePath) {
        return SCHEME + storagePath;
    }

    /**
     * Replaces any doc-image URL with its stable marker. Idempotent, and leaves every other link — an
     * external image, a normal hyperlink — untouched.
     */
    public static String normalize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        Matcher matcher = SIGNED_IMAGE_URL.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(marker(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Replaces each marker with a freshly signed URL.
     *
     * <p>Only paths under this project's own docs are signed. Markdown is user-authored, so a marker is
     * attacker-controlled text: without the prefix check, writing
     * {@code conductor-image:projects/<someone-else>/…} into a doc would make the server hand back a
     * signed URL for another project's object. A marker that fails the check is left as-is — visibly
     * broken, rather than quietly honoured.
     */
    public static String render(String content, String projectId, UrlSigner signer) {
        if (content == null || content.isEmpty() || !content.contains(SCHEME)) {
            return content;
        }
        String allowedPrefix = "projects/" + projectId + "/docs/";
        Matcher matcher = MARKER.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            String replacement = path.startsWith(allowedPrefix) ? signer.sign(path) : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Narrow view of {@link StorageService#generateSignedUrl} so this stays testable without one. */
    @FunctionalInterface
    public interface UrlSigner {
        String sign(String storagePath);
    }
}
