package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.service.AssetUploadPolicy;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.repository.AssetRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Photos with provenance a Creative renders with (COND-24 T2, extended to video/audio in PR1's "media
 * library"): mint a signed upload, confirm it, then patch provenance/verdict/focal points. Same mint
 * -&gt; PUT -&gt; confirm shape as {@link com.conductor.service.AssetService#createFileAsset}, but with its
 * own storage rows since a Creative photo has no owning Work Item.
 *
 * <p>The table/entity are still called "photo", but the API calls this the project's "media": IMAGE (the
 * original photo library), VIDEO and AUDIO. A VIDEO also carries an optional JPEG poster, minted through
 * its own {@link #mintPoster}/{@link #confirmPoster} pair.
 */
@Service
public class CreativePhotoService {

    /** Advisory bar: below this on the long edge, the 2x export upscales. Applies to IMAGE only. */
    static final int MIN_LONG_EDGE_PX = 2160;
    /** Advisory only (never a refusal): most platforms cap short-form video well under this. */
    static final int VIDEO_DURATION_WARNING_SECONDS = 180;
    private static final int URL_EXPIRY_MINUTES = 60;
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final long MAX_UPLOAD_BYTES_IMAGE = 100L * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES_VIDEO = 1024L * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES_AUDIO = 50L * 1024 * 1024;
    private static final Pattern FOCAL_PATTERN = Pattern.compile("^\\d{1,3}%\\s\\d{1,3}%$");
    private static final Logger log = LoggerFactory.getLogger(CreativePhotoService.class);

    /** One allowed content type: its file extension and the media kind it belongs to. */
    private record MediaTypeInfo(String extension, String mediaKind) {
    }

    private static final Map<String, MediaTypeInfo> ALLOWED_TYPES = Map.ofEntries(
            Map.entry("image/jpeg", new MediaTypeInfo("jpg", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("image/png", new MediaTypeInfo("png", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("image/webp", new MediaTypeInfo("webp", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("video/mp4", new MediaTypeInfo("mp4", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("video/quicktime", new MediaTypeInfo("mov", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("video/webm", new MediaTypeInfo("webm", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("audio/mpeg", new MediaTypeInfo("mp3", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/mp4", new MediaTypeInfo("m4a", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/aac", new MediaTypeInfo("aac", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/wav", new MediaTypeInfo("wav", CreativePhoto.MEDIA_KIND_AUDIO)));

    private final CreativePhotoRepository photoRepository;
    private final CreativeRepository creativeRepository;
    private final CreativeRegistry registry;
    private final AssetRepository assetRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final StorageObjectReferences storageReferences;
    private final ObjectMapper objectMapper;
    private final String backendBaseUrl;

    public CreativePhotoService(CreativePhotoRepository photoRepository,
                                CreativeRepository creativeRepository,
                                CreativeRegistry registry,
                                AssetRepository assetRepository,
                                CreativeRenderFrameRepository frameRepository,
                                ProjectSecurityService projectSecurityService,
                                StorageService storageService,
                                StorageObjectReferences storageReferences,
                                ObjectMapper objectMapper,
                                @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.photoRepository = photoRepository;
        this.creativeRepository = creativeRepository;
        this.registry = registry;
        this.assetRepository = assetRepository;
        this.frameRepository = frameRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.storageReferences = storageReferences;
        this.objectMapper = objectMapper;
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * The photo plus its short-lived signed GET ({@code url}, null until uploaded), the one-time signed
     * PUT ({@code uploadUrl}, present only on the create response), the signed GET of its poster
     * ({@code posterUrl}, null until a VIDEO has one) and any advisory warnings.
     */
    public record PhotoView(CreativePhoto photo, String url, String uploadUrl, String posterUrl, List<String> warnings,
                            Integer postCount, Integer creativeCount) {

        /** A view with no usage counts (the create response). */
        public PhotoView(CreativePhoto photo, String url, String uploadUrl, String posterUrl, List<String> warnings) {
            this(photo, url, uploadUrl, posterUrl, warnings, null, null);
        }

        PhotoView withCounts(UsageCounts counts) {
            return new PhotoView(photo, url, uploadUrl, posterUrl, warnings, counts.postCount(), counts.creativeCount());
        }
    }

    /** How many Work Items and Creatives use one Media item. */
    public record UsageCounts(int postCount, int creativeCount) {
        static final UsageCounts NONE = new UsageCounts(0, 0);
    }

    public record UsageCreative(String id, String displayId, String name, String state) {
    }

    public record UsagePost(String workItemId, String displayId, String title, String status, String workflow) {
    }

    public record MediaUsage(List<UsageCreative> creatives, List<UsagePost> posts) {
    }

    /** The one-time signed PUT a poster's bytes go to, and the path it will live at. */
    public record PosterUploadTicket(String uploadUrl, String gcsPath) {
    }

    @Transactional(readOnly = true)
    public List<PhotoView> listPhotos(String projectId, boolean includeBlocked, Set<String> mediaKinds, User caller) {
        return listPhotos(projectId, includeBlocked, mediaKinds, null, caller);
    }

    /** {@code labelPrefix}: case-insensitive prefix match on the label; null or empty matches everything. */
    @Transactional(readOnly = true)
    public List<PhotoView> listPhotos(String projectId, boolean includeBlocked, Set<String> mediaKinds,
                                      String labelPrefix, User caller) {
        return listPhotosPage(projectId, includeBlocked, mediaKinds, labelPrefix, null, false, false, null, null, caller)
                .items();
    }

    /** One page of the Media list; {@code nextCursor} is null on the last page (and always when unpaged). */
    public record PhotoPage(List<PhotoView> items, String nextCursor) {
    }

    /**
     * The Media list, newest first (created_at DESC, id DESC). Every filter is applied in SQL before the
     * page is cut. {@code limit} null means unpaged (the whole list, as before); with a limit the page is
     * keyset-paged after {@code cursor} (null = first page). Usage counts are returned only for the items
     * on the page.
     *
     * @param labelContains case-insensitive substring match on the label (null/blank = no filter)
     * @param uncheckedOnly only items a human has not yet checked
     * @param aiGeneratedOnly only AI-generated items
     */
    @Transactional(readOnly = true)
    public PhotoPage listPhotosPage(String projectId, boolean includeBlocked, Set<String> mediaKinds,
                                    String labelPrefix, String labelContains, boolean uncheckedOnly,
                                    boolean aiGeneratedOnly, Integer limit, String cursor, User caller) {
        return listPhotosPage(projectId, includeBlocked, mediaKinds, labelPrefix, labelContains, uncheckedOnly,
                aiGeneratedOnly, limit, cursor, null, caller);
    }

    /**
     * As above, optionally narrowed to {@code ids} (null/empty = no id filter). Lets a caller that only
     * needs a handful of known items (a tile resolving its poster) fetch and sign just those.
     */
    @Transactional(readOnly = true)
    public PhotoPage listPhotosPage(String projectId, boolean includeBlocked, Set<String> mediaKinds,
                                    String labelPrefix, String labelContains, boolean uncheckedOnly,
                                    boolean aiGeneratedOnly, Integer limit, String cursor, Set<String> ids,
                                    User caller) {
        requireMember(projectId, caller);
        Cursor after = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        Specification<CreativePhoto> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("projectId"), projectId));
            if (!includeBlocked) {
                where.add(cb.isFalse(root.get("blocked")));
            }
            if (mediaKinds != null && !mediaKinds.isEmpty()) {
                where.add(root.get("mediaKind").in(mediaKinds));
            }
            if (ids != null && !ids.isEmpty()) {
                where.add(root.get("id").in(ids));
            }
            if (labelPrefix != null && !labelPrefix.isEmpty()) {
                where.add(cb.like(cb.lower(root.get("label")), likeEscape(labelPrefix) + "%", '\\'));
            }
            if (labelContains != null && !labelContains.isBlank()) {
                where.add(cb.like(cb.lower(root.get("label")), "%" + likeEscape(labelContains.trim()) + "%", '\\'));
            }
            if (uncheckedOnly) {
                where.add(cb.isFalse(root.get("checked")));
            }
            if (aiGeneratedOnly) {
                where.add(cb.isTrue(root.get("aiGenerated")));
            }
            if (after != null) {
                Expression<OffsetDateTime> created = root.get("createdAt");
                where.add(cb.or(
                        cb.lessThan(created, after.createdAt()),
                        cb.and(cb.equal(created, after.createdAt()), cb.lessThan(root.<String>get("id"), after.id()))));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Sort order = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        List<CreativePhoto> photos = photoRepository.findBy(spec, q -> {
            var sorted = q.sortBy(order);
            return limit == null ? sorted.all() : sorted.limit(limit + 1).all();
        });
        String next = null;
        if (limit != null && photos.size() > limit) {
            photos = photos.subList(0, limit);
            CreativePhoto last = photos.get(photos.size() - 1);
            next = new Cursor(last.getCreatedAt(), last.getId()).encode();
        }
        Set<String> pageIds = new HashSet<>();
        photos.forEach(p -> pageIds.add(p.getId()));
        Map<String, UsageCounts> counts = usageCounts(projectId, pageIds);
        return new PhotoPage(photos.stream()
                .map(p -> toView(p).withCounts(counts.getOrDefault(p.getId(), UsageCounts.NONE))).toList(), next);
    }

    private static String likeEscape(String s) {
        return s.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Opaque keyset position: the last item's created_at (microsecond-exact) and id. */
    record Cursor(OffsetDateTime createdAt, String id) {
        String encode() {
            Instant at = createdAt.toInstant();
            String raw = at.getEpochSecond() + ":" + at.getNano() / 1000 + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split(":", 3);
                if (parts.length != 3 || parts[2].isEmpty()) {
                    throw new IllegalArgumentException();
                }
                long seconds = Long.parseLong(parts[0]);
                long micros = Long.parseLong(parts[1]);
                // Bounded to years Postgres timestamptz can hold; a tampered value must be a 400, not a SQL error.
                if (seconds < 0 || seconds > 253_402_300_799L || micros < 0 || micros > 999_999L) {
                    throw new IllegalArgumentException();
                }
                Instant at = Instant.ofEpochSecond(seconds, micros * 1000L);
                return new Cursor(at.atOffset(ZoneOffset.UTC), parts[2]);
            } catch (RuntimeException e) {
                throw new BusinessException("Invalid cursor");
            }
        }
    }

    @Transactional(readOnly = true)
    public PhotoView getPhoto(String projectId, String photoId, User caller) {
        requireMember(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        // Count this one photo's usage; the project-wide scan is only for the list.
        MediaUsage usage = usageOf(projectId, photo);
        return toView(photo).withCounts(new UsageCounts(usage.posts().size(), usage.creatives().size()));
    }

    // ── Usage ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Post and Creative counts for every Media item in the project that has any use, in a fixed number of
     * queries (project Creatives, direct links, frame links, the frames' Creatives) plus an in-memory scan.
     * A Post counts once per item however many of its Assets point at it, directly or through a frame.
     */
    private Map<String, UsageCounts> usageCounts(String projectId, Set<String> onlyIds) {
        // Only the columns that name Media, not whole Creative rows (copy, overrides, ...).
        Map<String, Set<String>> mediaByCreative = new HashMap<>();
        Map<String, Integer> creativeCounts = new HashMap<>();
        for (Object[] row : creativeRepository.findMediaReferencesByProjectId(projectId)) {
            Set<String> ids = referencedPhotoIds((String) row[1], (JsonNode) row[2], (JsonNode) row[3], (JsonNode) row[4],
                    (JsonNode) row[6]);
            String trackId = trackIdOf((JsonNode) row[5]);
            // A track counts as a Creative's use, but its Posts are counted through the frames it is mixed
            // into (audio_media_id), not through every frame of the Creative.
            Set<String> creativeUses = new HashSet<>(ids);
            if (trackId != null) {
                creativeUses.add(trackId);
            }
            mediaByCreative.put((String) row[0], ids);
            creativeUses.forEach(id -> creativeCounts.merge(id, 1, Integer::sum));
        }

        Map<String, Set<String>> postsByMedia = new HashMap<>();
        for (Object[] row : assetRepository.findMediaLinksByProjectId(projectId)) {
            postsByMedia.computeIfAbsent((String) row[0], k -> new HashSet<>()).add((String) row[1]);
        }
        List<Object[]> frameLinks = assetRepository.findFrameLinksByProjectId(projectId);
        if (!frameLinks.isEmpty()) {
            Map<String, String> creativeByFrame = new HashMap<>();
            Map<String, String> pathByFrame = new HashMap<>();
            Map<String, String> audioByFrame = new HashMap<>();
            for (Object[] f : frameRepository.findRefsByIdIn(
                    frameLinks.stream().map(r -> (String) r[0]).collect(java.util.stream.Collectors.toSet()))) {
                creativeByFrame.put((String) f[0], (String) f[1]);
                pathByFrame.put((String) f[0], (String) f[2]);
                if (f[3] != null) {
                    audioByFrame.put((String) f[0], (String) f[3]);
                }
            }
            // A frame also counts for the Media item whose object it reuses, even if its Creative dropped it.
            Map<String, List<String>> mediaIdsByPath = new HashMap<>();
            for (Object[] p : photoRepository.findPathsByProjectId(projectId)) {
                mediaIdsByPath.computeIfAbsent((String) p[1], k -> new ArrayList<>()).add((String) p[0]);
            }
            for (Object[] row : frameLinks) {
                for (String mediaId : mediaIdsByPath.getOrDefault(pathByFrame.get((String) row[0]), List.of())) {
                    postsByMedia.computeIfAbsent(mediaId, k -> new HashSet<>()).add((String) row[1]);
                }
                String audioId = audioByFrame.get((String) row[0]);
                if (audioId != null) {
                    postsByMedia.computeIfAbsent(audioId, k -> new HashSet<>()).add((String) row[1]);
                }
                String creativeId = creativeByFrame.get((String) row[0]);
                if (creativeId == null) {
                    continue;
                }
                for (String mediaId : mediaByCreative.getOrDefault(creativeId, Set.of())) {
                    postsByMedia.computeIfAbsent(mediaId, k -> new HashSet<>()).add((String) row[1]);
                }
            }
        }

        Map<String, UsageCounts> out = new HashMap<>();
        Set<String> all = new HashSet<>(creativeCounts.keySet());
        all.addAll(postsByMedia.keySet());
        for (String id : all) {
            if (!onlyIds.contains(id)) {
                continue;
            }
            out.put(id, new UsageCounts(postsByMedia.getOrDefault(id, Set.of()).size(), creativeCounts.getOrDefault(id, 0)));
        }
        return out;
    }

    /** Where one Media item is used: the Creatives that reference it and the Posts that link it, directly or by frame. */
    @Transactional(readOnly = true)
    public MediaUsage getUsage(String projectId, String photoId, User caller) {
        requireMember(projectId, caller);
        return usageOf(projectId, findPhoto(projectId, photoId));
    }

    private MediaUsage usageOf(String projectId, CreativePhoto photo) {
        String photoId = photo.getId();
        List<Creative> creatives = creativesReferencing(projectId, photoId);
        // Frames of those Creatives, plus frames that reuse this media's object even when their Creative
        // no longer references it: one id query rather than one per Creative.
        Set<String> frameIds;
        if (photo.isAudio()) {
            // A track is on a Post only through a video frame it is mixed into.
            frameIds = new HashSet<>(frameRepository.findIdsByAudioMediaId(photoId));
        } else {
            frameIds = new HashSet<>(frameRepository.findIdsByCreativeIdInOrGcsPath(
                    creatives.stream().map(Creative::getId).toList(), photo.getGcsPath()));
        }
        Map<String, WorkItem> posts = new java.util.LinkedHashMap<>();
        for (Asset a : assetRepository.findUsageAssets(photoId, frameIds)) {
            posts.putIfAbsent(a.getWorkItem().getId(), a.getWorkItem());
        }
        return new MediaUsage(
                creatives.stream().map(c -> new UsageCreative(c.getId(), c.displayId(), c.getName(), c.getState())).toList(),
                posts.values().stream().map(CreativePhotoService::toUsagePost).toList());
    }

    private static UsagePost toUsagePost(WorkItem w) {
        return new UsagePost(w.getId(), displayIdOf(w), w.getTitle(), w.getCurrentStatus(),
                w.getWorkflow() != null ? w.getWorkflow() : "ENGINEERING");
    }

    private static String displayIdOf(WorkItem w) {
        return w.getSequenceNumber() != null && w.getProject() != null
                ? w.getProject().getKey() + "-" + w.getSequenceNumber() : w.getId();
    }

    // ── Post uploads (the Media row behind a Work Item's file) ─────────────────────────────────

    /**
     * A PENDING Media item for a file uploaded straight onto a Work Item. It reuses the Work Item upload's
     * object path, skips the Media allowlist (the Work Item's {@code AssetUploadPolicy} already governed the
     * type, so e.g. a GIF is fine) and keeps width/height optional. The caller links it from the Asset.
     */
    @Transactional
    public CreativePhoto createForPostUpload(String projectId, String gcsPath, String label, String contentType,
                                             long sizeBytes, Integer width, Integer height,
                                             BigDecimal durationSeconds, String createdBy) {
        CreativePhoto photo = new CreativePhoto();
        photo.setId(UUID.randomUUID().toString());
        photo.setProjectId(projectId);
        photo.setLabel(label != null && label.length() > 200 ? label.substring(0, 200) : label);
        photo.setGcsPath(gcsPath);
        photo.setContentType(normalize(contentType));
        photo.setSizeBytes(sizeBytes);
        photo.setMediaKind(contentType != null && normalize(contentType).startsWith("video/")
                ? CreativePhoto.MEDIA_KIND_VIDEO : CreativePhoto.MEDIA_KIND_IMAGE);
        photo.setWidth(width);
        photo.setHeight(height);
        photo.setDurationSeconds(durationSeconds);
        photo.setAiGenerated(false);
        photo.setFocal(objectMapper.createObjectNode());
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photo.setCreatedBy(createdBy);
        return photoRepository.save(photo);
    }

    /** Marks a Post upload's Media item UPLOADED, with the size and (when known) dimensions the confirm measured. */
    @Transactional
    public void confirmForPostUpload(String mediaId, Long sizeBytes, Integer width, Integer height) {
        photoRepository.findById(mediaId).ifPresent(photo -> {
            if (sizeBytes != null && sizeBytes > 0) {
                photo.setSizeBytes(sizeBytes);
            }
            if (width != null && height != null) {
                photo.setWidth(width);
                photo.setHeight(height);
            }
            photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
            photoRepository.save(photo);
        });
    }

    /**
     * Deletes a still-PENDING Media item once the Asset that created it is gone and no other Asset links it
     * (a failed Post upload). An UPLOADED item is library content and is never touched.
     */
    @Transactional
    public void deletePendingPostUpload(String mediaId, String removedAssetId) {
        photoRepository.findById(mediaId)
                .filter(p -> CreativePhoto.UPLOAD_STATUS_PENDING.equals(p.getUploadStatus()))
                .filter(p -> !assetRepository.existsByMediaIdAndIdNot(mediaId, removedAssetId))
                .ifPresent(photoRepository::delete);
    }

    /**
     * The Media items a Work Item may link, in the order asked: each must exist in this project, be
     * UPLOADED, not blocked and not AUDIO. Anything else is a 400 naming the item.
     */
    @Transactional(readOnly = true)
    public List<CreativePhoto> requireLinkable(String projectId, List<String> mediaIds) {
        Map<String, CreativePhoto> byId = new HashMap<>();
        photoRepository.findAllByIdIn(mediaIds).forEach(p -> byId.put(p.getId(), p));
        List<CreativePhoto> out = new ArrayList<>();
        for (String id : mediaIds) {
            CreativePhoto p = byId.get(id);
            if (p == null || !projectId.equals(p.getProjectId())) {
                throw new BusinessException("Media " + id + " was not found in this workspace");
            }
            String name = p.getLabel() != null && !p.getLabel().isBlank() ? "\"" + p.getLabel() + "\"" : id;
            if (p.isBlocked()) {
                throw new BusinessException("Media " + name + " is blocked and can't be added to a post");
            }
            if (!p.isUploaded()) {
                throw new BusinessException("Media " + name + " is still uploading");
            }
            // The same allowlist a direct post upload is held to: Media also accepts types (audio, webm)
            // a post can't carry.
            String type = AssetUploadPolicy.normalizeContentType(p.getContentType());
            if (type == null || !AssetUploadPolicy.ALLOWED_CONTENT_TYPES.contains(type)) {
                throw new BusinessException("Media " + name + " is " + (type == null ? "an unknown type" : type)
                        + ", which a post can't carry");
            }
            out.add(p);
        }
        return out;
    }

    @Transactional
    public PhotoView createPhoto(String projectId, CreateCreativePhotoRequest request, User caller) {
        requireEditor(projectId, caller);
        String contentType = request.getContentType() != null ? request.getContentType().getValue() : null;
        MediaTypeInfo typeInfo = ALLOWED_TYPES.get(normalize(contentType));
        if (typeInfo == null) {
            throw new BusinessException("Content type '" + contentType + "' is not allowed for a Creative photo."
                    + " Allowed types: " + ALLOWED_TYPES.keySet().stream().sorted().toList());
        }
        // mediaKind defaults to IMAGE (the OpenAPI schema's default), but the content type is always the
        // source of truth for which kind this actually is — an explicit mediaKind is only ever a sanity
        // check against it, so a caller that names a video content type need not also spell out "VIDEO".
        if (request.getMediaKind() != null && !request.getMediaKind().getValue().equals(typeInfo.mediaKind())) {
            throw new BusinessException("mediaKind " + request.getMediaKind().getValue()
                    + " does not match content type '" + contentType + "'");
        }
        String mediaKind = typeInfo.mediaKind();
        long sizeBytes = requireAllowedSize(request.getSizeBytes(), mediaKind);

        boolean needsDimensions = !CreativePhoto.MEDIA_KIND_AUDIO.equals(mediaKind);
        if (needsDimensions && (request.getWidth() == null || request.getWidth() <= 0
                || request.getHeight() == null || request.getHeight() <= 0)) {
            throw new BusinessException("width and height are required and must be positive for " + mediaKind);
        }
        BigDecimal durationSeconds = null;
        boolean needsDuration = !CreativePhoto.MEDIA_KIND_IMAGE.equals(mediaKind);
        if (needsDuration) {
            if (request.getDurationSeconds() == null || request.getDurationSeconds().signum() <= 0) {
                throw new BusinessException("durationSeconds is required and must be positive for " + mediaKind);
            }
            durationSeconds = request.getDurationSeconds();
        }

        CreativePhoto photo = new CreativePhoto();
        String photoId = UUID.randomUUID().toString();
        photo.setId(photoId);
        photo.setProjectId(projectId);
        photo.setLabel(request.getLabel());
        String gcsPath = "projects/" + projectId + "/marketing/photos/" + photoId + "." + typeInfo.extension();
        photo.setGcsPath(gcsPath);
        photo.setContentType(normalize(contentType));
        photo.setSizeBytes(sizeBytes);
        photo.setMediaKind(mediaKind);
        photo.setWidth(needsDimensions ? request.getWidth() : null);
        photo.setHeight(needsDimensions ? request.getHeight() : null);
        photo.setDurationSeconds(durationSeconds);
        photo.setHasAudio(CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind) ? request.getHasAudio() : null);
        photo.setCodec(request.getCodec());
        photo.setSource(request.getSource());
        photo.setLicence(request.getLicence());
        photo.setAiGenerated(Boolean.TRUE.equals(request.getAiGenerated()));
        photo.setFocal(objectMapper.createObjectNode());
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photo.setCreatedBy(caller.getId());
        photo = photoRepository.save(photo);

        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, normalize(contentType), UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughUploadUrl(projectId, photoId);
        return new PhotoView(photo, null, uploadUrl, null, warningsFor(photo));
    }

    /**
     * Local-profile fallback, mirroring {@code AssetService#passthroughContentUrl}: {@code
     * StorageService} can't mint a signed upload URL on the {@code local} profile, so the client is
     * handed this URL instead of a signed bucket URL. Served by
     * {@code com.conductor.internal.CreativeContentController} ({@code @Profile("local")}, so it does
     * not exist in production). Unlike the Brand Kit image slots, the pending row already carries
     * {@code gcsPath} and {@code contentType}, so — exactly like {@code AssetService}'s own passthrough
     * — {@link #uploadContentPassthrough} needs nothing from the URL beyond the photo id.
     */
    private String passthroughUploadUrl(String projectId, String photoId) {
        return backendBaseUrl + "/internal/v1/projects/" + projectId + "/creative-photos/" + photoId + "/content";
    }

    private String passthroughPosterUploadUrl(String projectId, String photoId) {
        return backendBaseUrl + "/internal/v1/projects/" + projectId + "/creative-photos/" + photoId + "/poster-content";
    }

    /** Scope check for {@code CreativeContentController}: does this photo belong to this project? */
    @Transactional(readOnly = true)
    public boolean belongsToProject(String photoId, String projectId) {
        return photoRepository.findByIdAndProjectId(photoId, projectId).isPresent();
    }

    /**
     * Local-profile passthrough write: streams the raw body straight into the configured storage at the
     * pending row's own {@code gcsPath}/{@code contentType} — mirrors
     * {@code AssetService#uploadContentPassthrough}. No caller/membership check here; the caller-facing
     * scope check is {@link #belongsToProject}, done by the controller first.
     */
    @Transactional
    public void uploadContentPassthrough(String photoId, byte[] content) {
        CreativePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
        storageService.upload(photo.getGcsPath(), content, photo.getContentType());
    }

    /** As {@link #uploadContentPassthrough}, for a VIDEO's poster JPEG. */
    @Transactional
    public void uploadPosterContentPassthrough(String photoId, byte[] content) {
        CreativePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
        storageService.upload(posterGcsPath(photo.getProjectId(), photoId), content, "image/jpeg");
    }

    @Transactional
    public PhotoView confirmPhoto(String projectId, String photoId, Long observedSizeBytes, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        if (observedSizeBytes != null) {
            photo.setSizeBytes(requireAllowedSize(observedSizeBytes, photo.getMediaKind()));
        }
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        photo = photoRepository.save(photo);
        storageService.markCacheable(photo.getGcsPath());
        return toView(photo);
    }

    @Transactional
    public PhotoView patchPhoto(String projectId, String photoId, PatchCreativePhotoRequest request, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        if (request.getLabel() != null) {
            photo.setLabel(request.getLabel());
        }
        if (request.getSource() != null) {
            photo.setSource(request.getSource());
        }
        if (request.getLicence() != null) {
            photo.setLicence(request.getLicence());
        }
        if (request.getAiGenerated() != null) {
            photo.setAiGenerated(request.getAiGenerated());
        }
        if (request.getChecked() != null) {
            photo.setChecked(request.getChecked());
        }
        if (request.getBlocked() != null) {
            if (request.getBlocked()) {
                Set<String> readyUsers = new TreeSet<>();
                for (Creative c : creativesReferencing(projectId, photoId)) {
                    if (Creative.STATE_READY.equals(c.getState())) {
                        readyUsers.add(c.displayId());
                    }
                }
                if (!readyUsers.isEmpty()) {
                    throw new ConflictException("Creative " + String.join(", ", readyUsers)
                            + " is READY and uses this media — move it back to draft or swap the media there first");
                }
            }
            photo.setBlocked(request.getBlocked());
        }
        if (request.getBlockedReason() != null) {
            photo.setBlockedReason(request.getBlockedReason());
        }
        if (request.getFocal() != null) {
            photo.setFocal(validateAndConvertFocal(request.getFocal()));
        }
        photo = photoRepository.save(photo);
        return toView(photo);
    }

    // ── Poster (VIDEO only) ─────────────────────────────────────────────────────────────────────

    /** Mints a signed PUT for a VIDEO's JPEG poster. Refused with 422 when the photo is not a VIDEO. */
    @Transactional
    public PosterUploadTicket mintPoster(String projectId, String photoId, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        requireVideo(photo);
        String gcsPath = posterGcsPath(projectId, photoId);
        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, "image/jpeg", UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughPosterUploadUrl(projectId, photoId);
        return new PosterUploadTicket(uploadUrl, gcsPath);
    }

    /** Confirms an uploaded poster and sets it on the photo. Refused with 422 off a VIDEO or a mismatched path. */
    @Transactional
    public PhotoView confirmPoster(String projectId, String photoId, String gcsPath, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        requireVideo(photo);
        String expected = posterGcsPath(projectId, photoId);
        if (gcsPath == null || !gcsPath.equals(expected)) {
            throw new UnprocessableEntityException("gcsPath does not match this photo's poster prefix");
        }
        photo.setPosterGcsPath(gcsPath);
        photo = photoRepository.save(photo);
        storageService.markCacheable(gcsPath);
        return toView(photo);
    }

    private void requireVideo(CreativePhoto photo) {
        if (!photo.isVideo()) {
            throw new UnprocessableEntityException("Only a VIDEO photo can have a poster");
        }
    }

    private String posterGcsPath(String projectId, String photoId) {
        return "projects/" + projectId + "/marketing/photos/" + photoId + "-poster.jpg";
    }

    /**
     * Deletes a Creative photo's row and its stored object (best-effort, after the delete commits —
     * see {@link AfterCommitStorageCleanup}). Refused with 409 when any Creative in the project still
     * uses it, either as its main photo, as a sequence beat's photo, or (for a VIDEO) as a CLIP's
     * {@code clipMedia}.
     */
    @Transactional
    public void deletePhoto(String projectId, String photoId, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);

        Set<String> usedByDisplayIds = new TreeSet<>();
        for (Creative c : creativesReferencing(projectId, photoId)) {
            usedByDisplayIds.add(c.displayId());
        }
        if (!usedByDisplayIds.isEmpty()) {
            throw new ConflictException("Creative " + String.join(", ", usedByDisplayIds)
                    + " uses this photo — remove it there first");
        }

        Map<String, String> usedOnPosts = new java.util.LinkedHashMap<>();
        for (Asset a : assetRepository.findAllByMediaIdFetchWorkItem(photoId)) {
            usedOnPosts.putIfAbsent(a.getWorkItem().getId(),
                    displayIdOf(a.getWorkItem()) + " \"" + a.getWorkItem().getTitle() + "\"");
        }
        // A stale render of a Creative that no longer references this media can still be on a Post: its
        // frame reuses this media's object, so the frame link counts as a use.
        for (Asset a : assetRepository.findAllByFrameObjectPathsFetchWorkItem(mediaPaths(photo))) {
            usedOnPosts.putIfAbsent(a.getWorkItem().getId(),
                    displayIdOf(a.getWorkItem()) + " \"" + a.getWorkItem().getTitle() + "\"");
        }
        if (!usedOnPosts.isEmpty()) {
            throw new ConflictException("In use on " + String.join(", ", usedOnPosts.values())
                    + " \u2014 remove it from those posts first.");
        }

        photoRepository.delete(photo);
        // Frames (and Post uploads) can share these objects: only unreferenced ones are removed.
        storageReferences.deleteUnreferencedAfterCommit(mediaPaths(photo), log);
    }

    private static List<String> mediaPaths(CreativePhoto photo) {
        List<String> paths = new ArrayList<>();
        paths.add(photo.getGcsPath());
        if (photo.getPosterGcsPath() != null) {
            paths.add(photo.getPosterGcsPath());
        }
        return paths;
    }

    /** Every Creative in the project that uses this media: main photo, sequence beat, {@code clipMedia},
     *  CAROUSEL {@code slides}, a MOTION's background clip, or an {@code audio} track. */
    private List<Creative> creativesReferencing(String projectId, String photoId) {
        Map<String, Creative> used = new LinkedHashMap<>();
        for (Creative c : creativeRepository.findAllByPhotoId(photoId)) {
            if (projectId.equals(c.getProjectId())) {
                used.put(c.getId(), c);
            }
        }
        for (Creative c : creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId)) {
            if (sequenceReferencesPhoto(c.getSequence(), photoId) || clipMediaReferencesPhoto(c.getClipMedia(), photoId)
                    || slidesReferencePhoto(c.getSlides(), photoId)
                    || photoId.equals(trackIdOf(c.getAudio()))
                    || photoId.equals(backgroundClipIdOf(c.getMotion()))) {
                used.put(c.getId(), c);
            }
        }
        return new ArrayList<>(used.values());
    }

    /** A Creative's {@code audio.trackId}, or null when it has none. */
    private static String trackIdOf(JsonNode audio) {
        return audio != null && audio.hasNonNull("trackId") && audio.get("trackId").isTextual()
                ? audio.get("trackId").asText() : null;
    }

    /** A MOTION's {@code motion.background.clipMediaId}, or null. */
    private static String backgroundClipIdOf(JsonNode motion) {
        JsonNode background = motion != null ? motion.get("background") : null;
        return background != null && background.hasNonNull("clipMediaId") && background.get("clipMediaId").isTextual()
                ? background.get("clipMediaId").asText() : null;
    }

    /** Every visual Media id a Creative uses: main photo, sequence beats, clip media, carousel slides and a
     *  MOTION's background clip (its audio track is counted separately: see {@link #usageCounts}). */
    private Set<String> referencedPhotoIds(String photoId, JsonNode sequence, JsonNode clipMedia, JsonNode slides,
                                           JsonNode motion) {
        Set<String> ids = new HashSet<>();
        if (photoId != null) {
            ids.add(photoId);
        }
        String backgroundClip = backgroundClipIdOf(motion);
        if (backgroundClip != null) {
            ids.add(backgroundClip);
        }
        if (sequence != null && sequence.isArray()) {
            for (JsonNode beat : sequence) {
                JsonNode id = beat.get("photoId");
                if (id != null && id.isTextual()) {
                    ids.add(id.asText());
                }
            }
        }
        if (clipMedia != null && clipMedia.isObject()) {
            clipMedia.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isTextual()) {
                    ids.add(e.getValue().asText());
                }
            });
        }
        if (slides != null && slides.isObject()) {
            for (JsonNode list : slides) {
                if (list.isArray()) {
                    for (JsonNode id : list) {
                        if (id.isTextual()) {
                            ids.add(id.asText());
                        }
                    }
                }
            }
        }
        return ids;
    }

    /** Whether a CAROUSEL's {@code slides} map ({@code {"default": [id...], "<placement>": [id...]}}) names this media. */
    private boolean slidesReferencePhoto(JsonNode slides, String photoId) {
        if (slides == null || !slides.isObject()) {
            return false;
        }
        for (JsonNode list : slides) {
            if (list.isArray()) {
                for (JsonNode id : list) {
                    if (id.isTextual() && photoId.equals(id.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether any beat of a Creative's {@code sequence} JSON array names this photo. */
    private boolean sequenceReferencesPhoto(JsonNode sequence, String photoId) {
        if (sequence == null || !sequence.isArray()) {
            return false;
        }
        for (JsonNode beat : sequence) {
            JsonNode beatPhotoId = beat.get("photoId");
            if (beatPhotoId != null && beatPhotoId.isTextual() && photoId.equals(beatPhotoId.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a CLIP's {@code clipMedia} map ({@code {"default": id, "<placement>": id, ...}}) names this photo. */
    private boolean clipMediaReferencesPhoto(JsonNode clipMedia, String photoId) {
        if (clipMedia == null || !clipMedia.isObject()) {
            return false;
        }
        var it = clipMedia.fields();
        while (it.hasNext()) {
            JsonNode value = it.next().getValue();
            if (value != null && value.isTextual() && photoId.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode validateAndConvertFocal(Map<String, String> focal) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : focal.entrySet()) {
            if (!registry.hasPlacement(entry.getKey())) {
                throw new BusinessException("Unknown placement in focal: " + entry.getKey());
            }
            if (entry.getValue() == null || !FOCAL_PATTERN.matcher(entry.getValue()).matches()) {
                throw new BusinessException("focal." + entry.getKey() + " must look like \"50% 30%\"");
            }
            ordered.put(entry.getKey(), entry.getValue());
        }
        return objectMapper.valueToTree(ordered);
    }

    /**
     * IMAGE: a long edge under the 2160px bar is advisory only (never a refusal — no image pipeline
     * here). VIDEO: longer than {@link #VIDEO_DURATION_WARNING_SECONDS} is advisory only ("longer than
     * most platforms take" — no length cap here either, platform caps are enforced at publish time by
     * {@code MediaTargetValidator}). AUDIO: no warnings today.
     */
    List<String> warningsFor(CreativePhoto photo) {
        if (CreativePhoto.MEDIA_KIND_VIDEO.equals(photo.getMediaKind())) {
            BigDecimal duration = photo.getDurationSeconds();
            if (duration != null && duration.compareTo(BigDecimal.valueOf(VIDEO_DURATION_WARNING_SECONDS)) > 0) {
                return List.of("This video is " + duration.stripTrailingZeros().toPlainString()
                        + " seconds — longer than most platforms take.");
            }
            return List.of();
        }
        if (CreativePhoto.MEDIA_KIND_AUDIO.equals(photo.getMediaKind())) {
            return List.of();
        }
        Integer width = photo.getWidth();
        Integer height = photo.getHeight();
        if (width == null || height == null) {
            return List.of();
        }
        int longEdge = Math.max(width, height);
        if (longEdge < MIN_LONG_EDGE_PX) {
            return List.of("Long edge is " + longEdge + "px, under the " + MIN_LONG_EDGE_PX
                    + "px bar — a 2x export will upscale it.");
        }
        return List.of();
    }

    private PhotoView toView(CreativePhoto photo) {
        String url = photo.isUploaded() ? storageService.generateSignedUrl(photo.getGcsPath(), URL_EXPIRY_MINUTES) : null;
        String posterUrl = photo.getPosterGcsPath() != null
                ? storageService.generateSignedUrl(photo.getPosterGcsPath(), URL_EXPIRY_MINUTES) : null;
        return new PhotoView(photo, url, null, posterUrl, warningsFor(photo));
    }

    private static long requireAllowedSize(Long sizeBytes, String mediaKind) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BusinessException("Photo size must be a positive number of bytes");
        }
        long max = maxBytesFor(mediaKind);
        if (sizeBytes > max) {
            throw new BusinessException("Photo size " + sizeBytes + " bytes exceeds the " + max
                    + " byte ceiling for " + mediaKind);
        }
        return sizeBytes;
    }

    private static long maxBytesFor(String mediaKind) {
        return switch (mediaKind == null ? CreativePhoto.MEDIA_KIND_IMAGE : mediaKind) {
            case CreativePhoto.MEDIA_KIND_VIDEO -> MAX_UPLOAD_BYTES_VIDEO;
            case CreativePhoto.MEDIA_KIND_AUDIO -> MAX_UPLOAD_BYTES_AUDIO;
            default -> MAX_UPLOAD_BYTES_IMAGE;
        };
    }

    private static String normalize(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code MediaKind} query values -> the entity's stored strings, for {@link #listPhotos}. */
    public static Set<String> mediaKindValues(List<MediaKind> mediaKinds) {
        if (mediaKinds == null || mediaKinds.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new java.util.LinkedHashSet<>();
        for (MediaKind kind : mediaKinds) {
            values.add(kind.getValue());
        }
        return values;
    }

    CreativePhoto findPhoto(String projectId, String photoId) {
        return photoRepository.findByIdAndProjectId(photoId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new com.conductor.exception.ForbiddenException("Only ADMIN or CREATOR can manage Creative photos");
        }
    }
}
