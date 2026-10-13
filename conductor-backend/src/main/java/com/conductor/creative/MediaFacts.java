package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Everything the Creative validator, readiness checklist and render-spec builder need to know about one
 * media item (photo, clip or audio track), as a plain value — either a library {@link CreativePhoto}
 * ({@link #of}) or, for a draft spec only, a file that exists on the caller's machine ({@link #local}).
 *
 * <p>Mirrors {@link CreativePhoto}'s getter names on purpose, so code written against the entity reads the
 * same against this. It carries no persistence state: building the spec of a Creative that was never saved
 * must not touch an entity, and a draft's local media have no entity at all.
 */
public final class MediaFacts {

    /** URL scheme a render spec carries for a media file that lives on the caller's machine. */
    public static final String LOCAL_PREFIX = "local:";

    private final String ref;
    private final String localKey;
    private final String gcsPath;
    private final String mediaKind;
    private final String contentType;
    private final boolean uploaded;
    private final boolean blocked;
    private final boolean checked;
    private final boolean aiGenerated;
    private final String source;
    private final String licence;
    private final BigDecimal durationSeconds;
    private final Boolean hasAudio;
    private final Integer width;
    private final Integer height;
    private final JsonNode focal;

    private MediaFacts(String ref, String localKey, String gcsPath, String mediaKind, String contentType, boolean uploaded,
                       boolean blocked, boolean checked, boolean aiGenerated, String source, String licence,
                       BigDecimal durationSeconds, Boolean hasAudio, Integer width, Integer height, JsonNode focal) {
        this.ref = ref;
        this.localKey = localKey;
        this.gcsPath = gcsPath;
        this.mediaKind = mediaKind;
        this.contentType = contentType;
        this.uploaded = uploaded;
        this.blocked = blocked;
        this.checked = checked;
        this.aiGenerated = aiGenerated;
        this.source = source;
        this.licence = licence;
        this.durationSeconds = durationSeconds;
        this.hasAudio = hasAudio;
        this.width = width;
        this.height = height;
        this.focal = focal;
    }

    /** A library media item. */
    public static MediaFacts of(CreativePhoto photo) {
        return new MediaFacts(photo.getId(), null, photo.getGcsPath(), photo.getMediaKind(), photo.getContentType(), photo.isUploaded(),
                photo.isBlocked(), photo.isChecked(), photo.isAiGenerated(), photo.getSource(), photo.getLicence(),
                photo.getDurationSeconds(), photo.getHasAudio(), photo.getWidth(), photo.getHeight(), photo.getFocal());
    }

    /**
     * A media file on the caller's machine, referenced as {@code local:<key>}. It is never looked up, so it
     * counts as uploaded and unblocked; and since nobody has vetted it, it is unchecked, with no source or
     * licence — readiness reports exactly that. It has no focal point: the local job knows its own file.
     */
    public static MediaFacts local(String key, String mediaKind, BigDecimal durationSeconds, Boolean hasAudio,
                                   Integer width, Integer height) {
        return local(key, mediaKind, durationSeconds, hasAudio, width, height, null, null, null);
    }

    /** As above, carrying provenance the caller declared for the file (still unchecked; never persisted). */
    public static MediaFacts local(String key, String mediaKind, BigDecimal durationSeconds, Boolean hasAudio,
                                   Integer width, Integer height, String source, String licence, Boolean aiGenerated) {
        return new MediaFacts(LOCAL_PREFIX + key, key, null, mediaKind, null, true, false, false,
                Boolean.TRUE.equals(aiGenerated), source, licence, durationSeconds, hasAudio, width, height, null);
    }

    /** The id (library) or {@code local:<key>} this media was referenced by. */
    public String getRef() { return ref; }

    public boolean isLocal() { return localKey != null; }

    public String getLocalKey() { return localKey; }

    public String getGcsPath() { return gcsPath; }

    public String getMediaKind() { return mediaKind; }

    public String getContentType() { return contentType; }

    /** A GIF: allowed into the library by a Post upload, but not something a render can use. */
    public boolean isGif() { return "image/gif".equals(contentType); }

    public boolean isUploaded() { return uploaded; }

    public boolean isBlocked() { return blocked; }

    public boolean isChecked() { return checked; }

    public boolean isAiGenerated() { return aiGenerated; }

    public boolean isVideo() { return CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind); }

    public boolean isAudio() { return CreativePhoto.MEDIA_KIND_AUDIO.equals(mediaKind); }

    public String getSource() { return source; }

    public String getLicence() { return licence; }

    public BigDecimal getDurationSeconds() { return durationSeconds; }

    public Boolean getHasAudio() { return hasAudio; }

    public Integer getWidth() { return width; }

    public Integer getHeight() { return height; }

    public JsonNode getFocal() { return focal; }
}
