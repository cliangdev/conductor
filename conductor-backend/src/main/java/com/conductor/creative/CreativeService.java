package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeState;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The Creative library (COND-24 T2): CRUD, cutting a lettered variant, and readiness — a data-driven
 * port of nexus-marketing/social/core.mjs's {@code addConcept}/{@code setConcept}/{@code readiness}.
 * Every structural rule is enforced by {@link CreativeValidator} against the Creative's {@link BrandKit};
 * this class resolves references (kit, photo, sequence photos), assigns the display number/letter, and
 * owns the optimistic-lock check that replaces nexus's sha1 store version.
 */
@Service
public class CreativeService {

    private static final int PHOTO_URL_EXPIRY_MINUTES = 15;
    private static final Logger log = LoggerFactory.getLogger(CreativeService.class);

    private final CreativeRepository creativeRepository;
    private final CreativePhotoRepository photoRepository;
    private final BrandKitRepository brandKitRepository;
    private final BrandKitService brandKitService;
    private final ProjectRepository projectRepository;
    private final CreativeRegistry registry;
    private final CreativeValidator validator;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final CreativeRenderService renderService;
    private final CreativeExperimentRepository experimentRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final CreativeRenderRepository renderRepository;
    private final AssetRepository assetRepository;

    public CreativeService(CreativeRepository creativeRepository,
                           CreativePhotoRepository photoRepository,
                           BrandKitRepository brandKitRepository,
                           BrandKitService brandKitService,
                           ProjectRepository projectRepository,
                           CreativeRegistry registry,
                           CreativeValidator validator,
                           ProjectSecurityService projectSecurityService,
                           StorageService storageService,
                           ObjectMapper objectMapper,
                           CreativeRenderService renderService,
                           CreativeExperimentRepository experimentRepository,
                           CreativeRenderFrameRepository frameRepository,
                           CreativeRenderRepository renderRepository,
                           AssetRepository assetRepository) {
        this.creativeRepository = creativeRepository;
        this.photoRepository = photoRepository;
        this.brandKitRepository = brandKitRepository;
        this.brandKitService = brandKitService;
        this.projectRepository = projectRepository;
        this.registry = registry;
        this.validator = validator;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.renderService = renderService;
        this.experimentRepository = experimentRepository;
        this.frameRepository = frameRepository;
        this.renderRepository = renderRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * A Creative plus its photo's short-lived signed GET, for convenience on both list and detail reads.
     *
     * <p>{@code latestRenderSummary} (id + thumbnail URL) is populated on every read; {@code
     * latestRenderFull} — the full render with its frames, for {@code CreativeResponse.latestRender} — is
     * populated only for a single-Creative read (COND-24 T3, contract item 6), since fetching every
     * render's every frame for a whole list would be an easy way to make that endpoint slow.
     */
    public record CreativeView(Creative creative, String photoUrl,
                               CreativeRenderService.RenderView latestRenderSummary,
                               CreativeRenderService.RenderView latestRenderFull,
                               String activeExperimentId) {
        public CreativeView(Creative creative, String photoUrl) {
            this(creative, photoUrl, null, null, null);
        }
    }

    public record ReadinessItem(String key, boolean ok, boolean blocking, String message) {
    }

    public record Readiness(boolean ready, List<ReadinessItem> items) {
    }

    @Transactional(readOnly = true)
    public List<CreativeView> listCreatives(String projectId, String state, String brandKitId, User caller) {
        requireMember(projectId, caller);
        List<Creative> creatives;
        if (state != null && brandKitId != null) {
            creatives = creativeRepository.findAllByProjectIdAndStateAndBrandKitIdOrderByNumberDescVariantLetterAsc(projectId, state, brandKitId);
        } else if (state != null) {
            creatives = creativeRepository.findAllByProjectIdAndStateOrderByNumberDescVariantLetterAsc(projectId, state);
        } else if (brandKitId != null) {
            creatives = creativeRepository.findAllByProjectIdAndBrandKitIdOrderByNumberDescVariantLetterAsc(projectId, brandKitId);
        } else {
            creatives = creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId);
        }
        Map<String, CreativePhoto> photos = loadPhotos(creatives);
        return creatives.stream().map(c -> toView(projectId, c, photos)).toList();
    }

    @Transactional(readOnly = true)
    public CreativeView getCreative(String projectId, String creativeId, User caller) {
        requireMember(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        CreativeView view = toView(projectId, creative, loadPhotos(List.of(creative)));
        // Only the single-Creative read pays for the full render + its frames (contract item 6).
        CreativeRenderService.RenderView full = renderService.latestSucceededRender(creative.getId()).orElse(null);
        return new CreativeView(view.creative(), view.photoUrl(), view.latestRenderSummary(), full, view.activeExperimentId());
    }

    @Transactional
    public CreativeView createCreative(String projectId, CreateCreativeRequest request, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = resolveKit(projectId, request.getBrandKitId());

        String layout = request.getLayout() != null ? request.getLayout() : registry.defaultLayout();
        String theme = enumValue(request.getTheme(), Creative.THEME_DARK);
        String state = enumValue(request.getState(), Creative.STATE_DRAFT);
        String sequenceKind = enumValue(request.getSequenceKind(), null);
        String lockup = enumValue(request.getLockup(), Creative.LOCKUP_PLAIN);
        String kind = enumValue(request.getKind(), Creative.KIND_STILL);
        List<String> placements = request.getPlacements() != null ? request.getPlacements() : List.of();
        List<SequenceBeat> sequence = request.getSequence() != null ? request.getSequence() : List.of();
        CreativeLayoutOverrides layoutOverrides = request.getLayoutOverrides();
        Map<String, String> clipMedia = request.getClipMedia();

        PhotoResolution mainPhoto = resolvePhoto(projectId, request.getPhotoId());
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(projectId, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(projectId, clipMedia);
        MotionResolution motionRes = resolveMotion(projectId, kind, request.getMotion(), request.getAudio());

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, request.getHeadline(), request.getBody(), request.getCaption(),
                state, request.getPhotoId(), mainPhoto.resolvable(), mainPhoto.photo() != null,
                mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(),
                sequenceKind, toValidatorBeats(sequence), request.getCarouselRatio(), sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides), kind, clipMediaEntries,
                motionRes.input()));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        Creative creative = new Creative();
        creative.setProjectId(projectId);
        creative.setBrandKitId(kit.getId());
        creative.setVariantLetter("a");
        creative.setParentCreativeId(null);
        creative.setName(request.getName());
        creative.setState(state);
        creative.setLayout(layout);
        creative.setTheme(theme);
        creative.setPhotoId(request.getPhotoId());
        creative.setFocalOverride(request.getFocalOverride() != null ? objectMapper.valueToTree(request.getFocalOverride()) : null);
        creative.setHeadline(request.getHeadline());
        creative.setBody(request.getBody());
        creative.setCaption(request.getCaption());
        creative.setAltText(request.getAltText());
        creative.setPlacements(objectMapper.valueToTree(placements));
        creative.setSequenceKind(sequenceKind);
        creative.setSequence(objectMapper.valueToTree(sequence));
        creative.setCarouselRatio(request.getCarouselRatio());
        creative.setTypeOverrides(objectMapper.createObjectNode());
        creative.setLayoutOverrides(layoutOverrides != null ? objectMapper.valueToTree(layoutOverrides) : null);
        creative.setLockup(lockup);
        creative.setKind(kind);
        creative.setClipMedia(clipMedia != null && !clipMedia.isEmpty() ? objectMapper.valueToTree(clipMedia) : null);
        creative.setMotion(motionRes.motion() != null ? objectMapper.valueToTree(motionRes.motion()) : null);
        creative.setAudio(motionRes.audio() != null ? objectMapper.valueToTree(motionRes.audio()) : null);
        creative.setCreatedBy(caller.getId());

        creative = saveWithNextNumber(creative);
        return toView(projectId, creative, loadPhotos(List.of(creative)));
    }

    @Transactional
    public CreativeView patchCreative(String projectId, String creativeId, PatchCreativeRequest request, User caller) {
        requireEditor(projectId, caller);
        Creative current = findCreative(projectId, creativeId);
        if (request.getVersion() == null || request.getVersion() != current.getVersion()) {
            throw new ConflictException("Creative " + current.displayId() + " has changed since it was read"
                    + " (current version " + current.getVersion() + ", expected " + request.getVersion() + ")");
        }

        BrandKit kit = request.getBrandKitId() != null ? resolveKit(projectId, request.getBrandKitId())
                : requireKit(projectId, current.getBrandKitId());

        String layout = request.getLayout() != null ? request.getLayout() : current.getLayout();
        String theme = request.getTheme() != null ? request.getTheme().getValue() : current.getTheme();
        String state = request.getState() != null ? request.getState().getValue() : current.getState();
        String headline = request.getHeadline() != null ? request.getHeadline() : current.getHeadline();
        String body = request.getBody() != null ? request.getBody() : current.getBody();
        String caption = request.getCaption() != null ? request.getCaption() : current.getCaption();
        String altText = request.getAltText() != null ? request.getAltText() : current.getAltText();
        String photoId = request.getPhotoId() != null ? request.getPhotoId() : current.getPhotoId();
        String carouselRatio = request.getCarouselRatio() != null ? request.getCarouselRatio() : current.getCarouselRatio();
        String sequenceKind = request.getSequenceKind() != null ? request.getSequenceKind().getValue() : current.getSequenceKind();
        String lockup = request.getLockup() != null ? request.getLockup().getValue() : current.getLockup();
        String kind = request.getKind() != null ? request.getKind().getValue() : current.getKind();
        List<String> placements = request.getPlacements() != null ? request.getPlacements() : toStringList(current.getPlacements());
        List<SequenceBeat> sequence = request.getSequence() != null ? request.getSequence() : toSequenceBeats(current.getSequence());
        CreativeLayoutOverrides layoutOverridesRequest = request.getLayoutOverrides();
        JsonNode layoutOverrides = layoutOverridesRequest != null
                ? objectMapper.valueToTree(layoutOverridesRequest) : current.getLayoutOverrides();
        Map<String, String> clipMedia = request.getClipMedia() != null
                ? request.getClipMedia() : toClipMediaMap(current.getClipMedia());
        CreativeMotion motionRequest = request.getMotion() != null ? request.getMotion() : toMotion(current.getMotion());
        CreativeAudio audioRequest = request.getAudio() != null ? request.getAudio() : toAudio(current.getAudio());

        PhotoResolution mainPhoto = resolvePhoto(projectId, photoId);
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(projectId, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(projectId, clipMedia);
        MotionResolution motionRes = resolveMotion(projectId, kind, motionRequest, audioRequest);

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, headline, body, caption, state, photoId, mainPhoto.resolvable(),
                mainPhoto.photo() != null, mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(), sequenceKind,
                toValidatorBeats(sequence), carouselRatio, sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides), kind, clipMediaEntries,
                motionRes.input()));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        boolean headlineChanging = request.getHeadline() != null && !Objects.equals(request.getHeadline(), current.getHeadline());
        boolean layoutChanging = request.getLayout() != null && !Objects.equals(request.getLayout(), current.getLayout());
        JsonNode typeOverrides;
        if (request.getTypeOverrides() != null) {
            typeOverrides = objectMapper.valueToTree(request.getTypeOverrides());
        } else if (headlineChanging || layoutChanging) {
            typeOverrides = objectMapper.createObjectNode();
        } else {
            typeOverrides = current.getTypeOverrides();
        }

        current.setBrandKitId(kit.getId());
        current.setName(request.getName() != null ? request.getName() : current.getName());
        current.setState(state);
        current.setLayout(layout);
        current.setTheme(theme);
        current.setPhotoId(photoId);
        if (request.getFocalOverride() != null) {
            current.setFocalOverride(objectMapper.valueToTree(request.getFocalOverride()));
        }
        current.setHeadline(headline);
        current.setBody(body);
        current.setCaption(caption);
        current.setAltText(altText);
        current.setPlacements(objectMapper.valueToTree(placements));
        current.setSequenceKind(sequenceKind);
        current.setSequence(objectMapper.valueToTree(sequence));
        current.setCarouselRatio(carouselRatio);
        current.setTypeOverrides(typeOverrides);
        current.setLayoutOverrides(layoutOverrides);
        current.setLockup(lockup);
        current.setKind(kind);
        current.setClipMedia(clipMedia.isEmpty() ? null : objectMapper.valueToTree(clipMedia));
        current.setMotion(motionRes.motion() != null ? objectMapper.valueToTree(motionRes.motion()) : null);
        current.setAudio(motionRes.audio() != null ? objectMapper.valueToTree(motionRes.audio()) : null);

        current = creativeRepository.save(current);
        return toView(projectId, current, loadPhotos(List.of(current)));
    }

    @Transactional
    public CreativeView createVariant(String projectId, String creativeId, CreateCreativeVariantRequest request, User caller) {
        requireEditor(projectId, caller);
        Creative source = findCreative(projectId, creativeId);
        Creative root = source.getParentCreativeId() != null
                ? findCreative(projectId, source.getParentCreativeId())
                : source;

        BrandKit kit = requireKit(projectId, root.getBrandKitId());
        String headline = request != null && request.getHeadline() != null ? request.getHeadline() : source.getHeadline();
        List<SequenceBeat> sequence = toSequenceBeats(root.getSequence());
        List<String> placements = toStringList(root.getPlacements());

        PhotoResolution mainPhoto = resolvePhoto(projectId, root.getPhotoId());
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(projectId, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(projectId, toClipMediaMap(root.getClipMedia()));
        MotionResolution motionRes = resolveMotion(projectId, root.getKind(), toMotion(root.getMotion()), toAudio(root.getAudio()));

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                root.getLayout(), root.getTheme(), placements, headline, root.getBody(), root.getCaption(),
                Creative.STATE_DRAFT, root.getPhotoId(), mainPhoto.resolvable(), mainPhoto.photo() != null,
                mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(),
                root.getSequenceKind(), toValidatorBeats(sequence), root.getCarouselRatio(), sequencePhotos.unknownIds(),
                overrideBand(root.getLayoutOverrides()), overridePadBottom(root.getLayoutOverrides()),
                root.getKind(), clipMediaEntries, motionRes.input()));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        Creative variant = new Creative();
        variant.setProjectId(projectId);
        variant.setBrandKitId(kit.getId());
        variant.setNumber(root.getNumber());
        variant.setParentCreativeId(root.getId());
        variant.setName(request != null && request.getName() != null ? request.getName() : source.getName());
        variant.setState(Creative.STATE_DRAFT);
        variant.setLayout(root.getLayout());
        variant.setTheme(root.getTheme());
        variant.setPhotoId(root.getPhotoId());
        variant.setFocalOverride(root.getFocalOverride());
        variant.setHeadline(headline);
        variant.setBody(root.getBody());
        variant.setCaption(root.getCaption());
        variant.setAltText(root.getAltText());
        variant.setPlacements(root.getPlacements());
        variant.setSequenceKind(root.getSequenceKind());
        variant.setSequence(root.getSequence());
        variant.setCarouselRatio(root.getCarouselRatio());
        variant.setKind(root.getKind());
        variant.setClipMedia(root.getClipMedia());
        variant.setMotion(motionRes.motion() != null ? objectMapper.valueToTree(motionRes.motion()) : null);
        variant.setAudio(motionRes.audio() != null ? objectMapper.valueToTree(motionRes.audio()) : null);
        variant.setTypeOverrides(objectMapper.createObjectNode());
        variant.setLayoutOverrides(root.getLayoutOverrides());
        variant.setLockup(root.getLockup());
        variant.setCreatedBy(caller.getId());

        variant = saveWithNextLetter(variant);
        return toView(projectId, variant, loadPhotos(List.of(variant)));
    }

    @Transactional(readOnly = true)
    public Readiness readiness(String projectId, String creativeId, User caller) {
        requireMember(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        boolean isClip = Creative.KIND_CLIP.equals(creative.getKind());
        boolean isMotion = Creative.KIND_MOTION.equals(creative.getKind());

        List<ReadinessItem> items = new ArrayList<>();
        items.add(new ReadinessItem("caption", notBlank(creative.getCaption()), true,
                notBlank(creative.getCaption()) ? "caption is set" : "caption is missing: the post needs primary text"));
        // Alt text describes a still photo for the upload; for a CLIP or MOTION (video), it is advisory
        // only — a missing alt text never blocks a video the way it blocks a photo (contract: "optional ->
        // warning only for video").
        boolean isVideoKind = isClip || isMotion;
        items.add(new ReadinessItem("altText", notBlank(creative.getAltText()), !isVideoKind,
                notBlank(creative.getAltText()) ? "alt text is set" : "alt text is missing: describe the photo for the upload"));

        if (isClip) {
            addClipReadinessItems(projectId, creative, items);
        } else if (isMotion) {
            addMotionReadinessItems(projectId, creative, items);
        } else {
            addPhotoReadinessItems(projectId, creative, items);
        }

        boolean notDraft = !Creative.STATE_DRAFT.equals(creative.getState());
        items.add(new ReadinessItem("state", notDraft, true,
                notDraft ? "not a draft" : "Creative is still a draft"));

        boolean ready = items.stream().noneMatch(i -> i.blocking() && !i.ok());
        return new Readiness(ready, items);
    }

    private void addPhotoReadinessItems(String projectId, Creative creative, List<ReadinessItem> items) {
        CreativePhoto photo = creative.getPhotoId() != null
                ? photoRepository.findByIdAndProjectId(creative.getPhotoId(), projectId).orElse(null)
                : null;
        boolean photoChecked = photo != null && photo.isChecked();
        items.add(new ReadinessItem("photoChecked", photoChecked, true,
                photo == null ? "no photo chosen"
                        : photoChecked ? "photo has been checked" : "photo has not been checked for burned-in text"));
        // With no photo chosen, "photoChecked" above already carries that message once — this item is
        // only meaningful once there is a photo to have source/licence on.
        if (photo != null) {
            boolean provenanced = notBlank(photo.getSource()) && notBlank(photo.getLicence());
            items.add(new ReadinessItem("photoProvenance", provenanced, true,
                    provenanced ? "photo has source and licence" : "photo is missing source and/or licence"));
        }
        boolean aiGenerated = photo != null && photo.isAiGenerated();
        items.add(new ReadinessItem("aiDisclosure", !aiGenerated, false,
                aiGenerated ? "photo is AI-generated: tick the AI-disclosure toggle on upload" : "not AI-generated"));
    }

    /**
     * Readiness for MOTION (COND-24 PR2): STILL's photo-verdict items when the background is a photo, or
     * the background clip's own provenance when it is a clip; plus the audio track's provenance when
     * {@code audio.source} is {@code track} (contract: "STILL items + track source/licence when
     * audio.source=track; clip provenance when background is a clip").
     */
    private void addMotionReadinessItems(String projectId, Creative creative, List<ReadinessItem> items) {
        CreativeMotion motion = toMotion(creative.getMotion());
        CreativeMotionBackground background = motion != null ? motion.getBackground() : null;
        String backgroundSource = background != null ? background.getSource() : null;

        if ("clip".equals(backgroundSource)) {
            String clipMediaId = background.getClipMediaId();
            boolean hasClip = notBlank(clipMediaId);
            items.add(new ReadinessItem("motionBackground", hasClip, true,
                    hasClip ? "a background clip is chosen" : "no background clip chosen"));
            CreativePhoto clipMedia = hasClip ? photoRepository.findByIdAndProjectId(clipMediaId, projectId).orElse(null) : null;
            if (clipMedia != null) {
                boolean provenanced = notBlank(clipMedia.getSource()) && notBlank(clipMedia.getLicence());
                items.add(new ReadinessItem("clipProvenance", provenanced, true,
                        provenanced ? "the background clip has source and licence"
                                : "the background clip is missing source and/or licence"));
            }
        } else {
            addPhotoReadinessItems(projectId, creative, items);
        }

        CreativeAudio audio = toAudio(creative.getAudio());
        if (audio != null && "track".equals(audio.getSource())) {
            String trackId = audio.getTrackId();
            CreativePhoto track = notBlank(trackId) ? photoRepository.findByIdAndProjectId(trackId, projectId).orElse(null) : null;
            boolean provenanced = track != null && notBlank(track.getSource()) && notBlank(track.getLicence());
            items.add(new ReadinessItem("audioProvenance", provenanced, true,
                    provenanced ? "the audio track has source and licence" : "the audio track is missing source and/or licence"));
        }
    }

    /** Readiness for CLIP (COND-24 PR1): caption (above), alt text (advisory), the chosen clip's media
     *  source/licence, and its AI-disclosure flag (informational — mirrors {@link #addPhotoReadinessItems}). */
    private void addClipReadinessItems(String projectId, Creative creative, List<ReadinessItem> items) {
        Map<String, String> clipMedia = toClipMediaMap(creative.getClipMedia());
        boolean hasClip = !clipMedia.isEmpty();
        items.add(new ReadinessItem("clip", hasClip, true,
                hasClip ? "a clip is chosen" : "no clip chosen: pick at least one video for this Creative"));

        CreativePhoto media = representativeClipMedia(projectId, clipMedia);
        if (media != null) {
            boolean provenanced = notBlank(media.getSource()) && notBlank(media.getLicence());
            items.add(new ReadinessItem("mediaProvenance", provenanced, true,
                    provenanced ? "the clip has source and licence" : "the clip is missing source and/or licence"));
        }
        boolean aiGenerated = media != null && media.isAiGenerated();
        items.add(new ReadinessItem("aiDisclosure", !aiGenerated, false,
                aiGenerated ? "the clip is AI-generated: tick the AI-disclosure toggle on upload" : "not AI-generated"));
    }

    /** The {@code "default"} entry's media, or the first entry's if there is no default — the one clip
     *  {@link #addClipReadinessItems} reads provenance/AI-disclosure off. */
    private CreativePhoto representativeClipMedia(String projectId, Map<String, String> clipMedia) {
        if (clipMedia.isEmpty()) {
            return null;
        }
        String mediaId = clipMedia.containsKey("default") ? clipMedia.get("default") : clipMedia.values().iterator().next();
        return mediaId != null ? photoRepository.findByIdAndProjectId(mediaId, projectId).orElse(null) : null;
    }

    /**
     * Deletes a Creative, in one transaction: its hook experiments (where it is the family root; any
     * experiment elsewhere naming it as winner is nulled out, not deleted, so the decision record
     * outlives it), its render frames and renders, then the Creative itself. Refused with 409 when a
     * render frame is still on a Post ({@code assets.creative_frame_id}), or when this is a family root
     * with other lettered variants still present. The frame objects a SUCCEEDED render wrote to storage
     * are removed best-effort once the delete commits — see {@link AfterCommitStorageCleanup}.
     */
    @Transactional
    public void deleteCreative(String projectId, String creativeId, User caller) {
        requireEditor(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);

        if (creative.getParentCreativeId() == null) {
            List<Creative> siblings = creativeRepository.findAllByNumberAndProjectId(creative.getNumber(), projectId)
                    .stream()
                    .filter(c -> !c.getId().equals(creative.getId()))
                    .sorted(Comparator.comparing(Creative::getVariantLetter))
                    .toList();
            if (!siblings.isEmpty()) {
                String names = siblings.stream().map(Creative::displayId).collect(Collectors.joining(", "));
                throw new ConflictException("Delete its variants first: " + names);
            }
        }

        List<CreativeRenderFrame> frames = frameRepository.findAllByCreativeId(creative.getId());
        if (!frames.isEmpty()) {
            List<String> frameIds = frames.stream().map(CreativeRenderFrame::getId).toList();
            List<Asset> referencing = assetRepository.findAllByCreativeFrameIdIn(frameIds);
            if (!referencing.isEmpty()) {
                Set<String> postDisplayIds = new TreeSet<>();
                for (Asset asset : referencing) {
                    postDisplayIds.add(asset.getWorkItem().getProject().getKey() + "-" + asset.getWorkItem().getSequenceNumber());
                }
                String noun = postDisplayIds.size() > 1 ? "Posts" : "Post";
                throw new ConflictException("Remove it from the " + noun + " first (" + String.join(", ", postDisplayIds) + ")");
            }
        }

        List<CreativeExperiment> asFamilyRoot = experimentRepository
                .findAllByProjectIdAndParentCreativeIdOrderByCreatedAtDesc(projectId, creative.getId());
        if (!asFamilyRoot.isEmpty()) {
            experimentRepository.deleteAll(asFamilyRoot);
        }

        List<CreativeExperiment> asWinnerElsewhere = experimentRepository.findAllByWinnerCreativeId(creative.getId());
        if (!asWinnerElsewhere.isEmpty()) {
            asWinnerElsewhere.forEach(e -> e.setWinnerCreativeId(null));
            experimentRepository.saveAll(asWinnerElsewhere);
        }

        List<String> gcsPaths = frames.stream().map(CreativeRenderFrame::getGcsPath).toList();
        if (!frames.isEmpty()) {
            frameRepository.deleteAllByCreativeId(creative.getId());
        }
        if (!renderRepository.findAllByCreativeId(creative.getId()).isEmpty()) {
            renderRepository.deleteAllByCreativeId(creative.getId());
        }

        creativeRepository.delete(creative);

        AfterCommitStorageCleanup.deleteAfterCommit(storageService, gcsPaths, log);
    }

    // ── Number/letter assignment ──────────────────────────────────────────

    /**
     * Serialises display-number allocation per project on the {@code projects} row, the same lock
     * {@code WorkItemService#createWorkItem} takes for its {@code sequenceNumber} — two concurrent
     * creates that read {@code MAX(number)} without it would compute the same next number and the
     * second would die on {@code uq_creative_project_number_letter}.
     */
    private Creative saveWithNextNumber(Creative creative) {
        projectRepository.lockForSequence(creative.getProjectId());
        Integer max = creativeRepository.findMaxNumber(creative.getProjectId());
        creative.setNumber(max == null ? 1 : max + 1);
        return creativeRepository.save(creative);
    }

    /** Same lock as {@link #saveWithNextNumber}, serialising the next-free-letter read within a number. */
    private Creative saveWithNextLetter(Creative variant) {
        projectRepository.lockForSequence(variant.getProjectId());
        variant.setVariantLetter(nextLetter(variant.getProjectId(), variant.getNumber()));
        return creativeRepository.save(variant);
    }

    private String nextLetter(String projectId, int number) {
        Set<String> used = new HashSet<>();
        for (Creative c : creativeRepository.findAllByNumberAndProjectId(number, projectId)) {
            used.add(c.getVariantLetter());
        }
        char letter = 'a';
        while (used.contains(String.valueOf(letter))) {
            letter++;
        }
        return String.valueOf(letter);
    }

    // ── Reference resolution ──────────────────────────────────────────────

    private record PhotoResolution(CreativePhoto photo, boolean resolvable) {
    }

    private PhotoResolution resolvePhoto(String projectId, String photoId) {
        if (photoId == null) {
            return new PhotoResolution(null, true);
        }
        CreativePhoto photo = photoRepository.findByIdAndProjectId(photoId, projectId).orElse(null);
        return new PhotoResolution(photo, photo != null);
    }

    private record SequencePhotoResolution(Set<String> unknownIds) {
    }

    private SequencePhotoResolution resolveSequencePhotos(String projectId, List<SequenceBeat> sequence) {
        Set<String> referenced = new HashSet<>();
        for (SequenceBeat beat : sequence) {
            if (beat.getPhotoId() != null) {
                referenced.add(beat.getPhotoId());
            }
        }
        if (referenced.isEmpty()) {
            return new SequencePhotoResolution(Set.of());
        }
        Set<String> existing = new HashSet<>();
        for (CreativePhoto photo : photoRepository.findAllByIdIn(referenced)) {
            if (projectId.equals(photo.getProjectId())) {
                existing.add(photo.getId());
            }
        }
        Set<String> unknown = new HashSet<>(referenced);
        unknown.removeAll(existing);
        return new SequencePhotoResolution(unknown);
    }

    /**
     * Resolves a CLIP creative's {@code clipMedia} map ({@code "default"}/placement key -> media id)
     * against the project's media library, for {@link CreativeValidator#validate}. A null or empty map
     * resolves to no entries (a CLIP may be a draft with no clip chosen yet, exactly like a STILL Creative
     * may be a draft with no photo).
     */
    private List<CreativeValidator.ClipMediaEntry> resolveClipMedia(String projectId, Map<String, String> clipMedia) {
        if (clipMedia == null || clipMedia.isEmpty()) {
            return List.of();
        }
        List<CreativeValidator.ClipMediaEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : clipMedia.entrySet()) {
            String mediaId = entry.getValue();
            CreativePhoto media = mediaId != null ? photoRepository.findByIdAndProjectId(mediaId, projectId).orElse(null) : null;
            entries.add(new CreativeValidator.ClipMediaEntry(entry.getKey(), mediaId, media != null,
                    media != null && media.isVideo(), media != null && media.isUploaded(),
                    media != null && media.isBlocked()));
        }
        return entries;
    }

    /** {@code creative.clip_media} JSON -&gt; a typed map, or an empty map when unset. */
    private Map<String, String> toClipMediaMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, String>>() {
        });
    }

    // ── MOTION (COND-24 PR2) ────────────────────────────────────────────────

    /** {@code motion}/{@code audio}, defaults already applied, plus the resolved {@link
     *  CreativeValidator.MotionInput} to validate against. {@code motion}/{@code input} are null for a
     *  non-MOTION kind — {@code creative.motion}/{@code audio} stay whatever was passed through untouched. */
    private record MotionResolution(CreativeMotion motion, CreativeAudio audio, CreativeValidator.MotionInput input) {
    }

    /**
     * Applies MOTION's write-time defaults (contract: preset {@code fade-up}, {@code durationSec} 8,
     * background photo {@code zoom-in}, {@code endCard} true, audio defaulting to {@code clip} when the
     * background is a clip with sound else {@code none}) and resolves its media references (background
     * clip, audio track) against the project's library, mirroring {@link #resolveClipMedia}. A non-MOTION
     * kind passes {@code motionRequest}/{@code audioRequest} through untouched with no validator input.
     */
    private MotionResolution resolveMotion(String projectId, String kind, CreativeMotion motionRequest, CreativeAudio audioRequest) {
        if (!Creative.KIND_MOTION.equals(kind)) {
            return new MotionResolution(motionRequest, audioRequest, null);
        }
        CreativeMotion motion = motionRequest != null ? motionRequest : new CreativeMotion();
        if (!notBlank(motion.getPreset())) {
            motion.setPreset("fade-up");
        }
        if (motion.getDurationSec() == null) {
            motion.setDurationSec(BigDecimal.valueOf(8));
        }
        CreativeMotionBackground background = motion.getBackground() != null ? motion.getBackground() : new CreativeMotionBackground();
        if (!notBlank(background.getSource())) {
            background.setSource("photo");
        }
        if ("photo".equals(background.getSource()) && !notBlank(background.getMotion())) {
            background.setMotion("zoom-in");
        }
        motion.setBackground(background);
        if (motion.getEndCard() == null) {
            motion.setEndCard(true);
        }

        PhotoResolution clipRes = resolvePhoto(projectId, background.getClipMediaId());
        CreativePhoto clipMedia = clipRes.photo();

        CreativeAudio audio = audioRequest != null ? audioRequest : new CreativeAudio();
        if (!notBlank(audio.getSource())) {
            boolean clipHasSound = "clip".equals(background.getSource())
                    && clipMedia != null && Boolean.TRUE.equals(clipMedia.getHasAudio());
            audio.setSource(clipHasSound ? "clip" : "none");
        }
        if (audio.getVolume() == null) {
            audio.setVolume(BigDecimal.valueOf(0.8));
        }
        if (audio.getFadeOutSec() == null) {
            audio.setFadeOutSec(BigDecimal.ONE);
        }

        PhotoResolution trackRes = resolvePhoto(projectId, audio.getTrackId());
        CreativePhoto trackMedia = trackRes.photo();

        CreativeValidator.MotionInput input = new CreativeValidator.MotionInput(
                motion.getPreset(),
                motion.getDurationSec() != null ? motion.getDurationSec().doubleValue() : null,
                background.getSource(),
                background.getMotion(),
                background.getClipMediaId(),
                clipRes.resolvable(),
                clipMedia != null && clipMedia.isVideo(),
                clipMedia != null && clipMedia.isUploaded(),
                clipMedia != null && clipMedia.isBlocked(),
                clipMedia != null && clipMedia.getDurationSeconds() != null ? clipMedia.getDurationSeconds().doubleValue() : null,
                clipMedia != null ? clipMedia.getHasAudio() : null,
                background.getClipStartSec() != null ? background.getClipStartSec().doubleValue() : null,
                motion.getEndCard(),
                audio.getSource(),
                audio.getTrackId(),
                trackRes.resolvable(),
                trackMedia != null && trackMedia.isAudio(),
                trackMedia != null && trackMedia.isUploaded(),
                trackMedia != null && trackMedia.isBlocked(),
                audio.getVolume() != null ? audio.getVolume().doubleValue() : null,
                audio.getFadeOutSec() != null ? audio.getFadeOutSec().doubleValue() : null);

        return new MotionResolution(motion, audio, input);
    }

    /** {@code creative.motion} JSON -&gt; the typed DTO, or null when unset. */
    private CreativeMotion toMotion(JsonNode node) {
        return node != null && !node.isNull() ? objectMapper.convertValue(node, CreativeMotion.class) : null;
    }

    /** {@code creative.audio} JSON -&gt; the typed DTO, or null when unset. */
    private CreativeAudio toAudio(JsonNode node) {
        return node != null && !node.isNull() ? objectMapper.convertValue(node, CreativeAudio.class) : null;
    }

    private BrandKit resolveKit(String projectId, String brandKitId) {
        return brandKitId != null ? requireKit(projectId, brandKitId) : brandKitService.resolveDefault(projectId);
    }

    private BrandKit requireKit(String projectId, String brandKitId) {
        return brandKitRepository.findByIdAndProjectId(brandKitId, projectId)
                .orElseThrow(() -> new BusinessException("No Brand Kit with id " + brandKitId + " in this project"));
    }

    // ── View assembly ──────────────────────────────────────────────────────

    private Map<String, CreativePhoto> loadPhotos(List<Creative> creatives) {
        Set<String> ids = new HashSet<>();
        for (Creative c : creatives) {
            if (c.getPhotoId() != null) {
                ids.add(c.getPhotoId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, CreativePhoto> map = new HashMap<>();
        for (CreativePhoto photo : photoRepository.findAllByIdIn(ids)) {
            map.put(photo.getId(), photo);
        }
        return map;
    }

    private CreativeView toView(String projectId, Creative creative, Map<String, CreativePhoto> photos) {
        CreativePhoto photo = creative.getPhotoId() != null ? photos.get(creative.getPhotoId()) : null;
        String url = photo != null && photo.isUploaded()
                ? storageService.generateSignedUrl(photo.getGcsPath(), PHOTO_URL_EXPIRY_MINUTES)
                : null;
        CreativeRenderService.RenderView summary = renderService.latestSucceededRender(creative.getId()).orElse(null);
        return new CreativeView(creative, url, summary, null, activeExperimentId(projectId, creative));
    }

    /** The family's RUNNING experiment id, if any — no extra fetch needed since the root id is either
     *  {@code creative.parentCreativeId} or the creative's own id. */
    private String activeExperimentId(String projectId, Creative creative) {
        String rootId = creative.getParentCreativeId() != null ? creative.getParentCreativeId() : creative.getId();
        return experimentRepository.findFirstByProjectIdAndParentCreativeIdAndState(
                        projectId, rootId, CreativeExperiment.STATE_RUNNING)
                .map(CreativeExperiment::getId)
                .orElse(null);
    }

    // ── JSON <-> typed helpers ───────────────────────────────────────────

    private List<String> toStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<String>>() {
        });
    }

    private List<SequenceBeat> toSequenceBeats(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<SequenceBeat>>() {
        });
    }

    private List<CreativeValidator.SequenceBeat> toValidatorBeats(List<SequenceBeat> beats) {
        List<CreativeValidator.SequenceBeat> result = new ArrayList<>();
        for (SequenceBeat beat : beats) {
            result.add(new CreativeValidator.SequenceBeat(beat.getHeadline(), beat.getBody(), beat.getPhotoId()));
        }
        return result;
    }

    private static String enumValue(CreativeTheme theme, String fallback) {
        return theme != null ? theme.getValue() : fallback;
    }

    private static String enumValue(CreativeState state, String fallback) {
        return state != null ? state.getValue() : fallback;
    }

    private static String enumValue(SequenceKind kind, String fallback) {
        return kind != null ? kind.getValue() : fallback;
    }

    private static String enumValue(CreativeLockup lockup, String fallback) {
        return lockup != null ? lockup.getValue() : fallback;
    }

    private static String enumValue(CreativeKind kind, String fallback) {
        return kind != null ? kind.getValue() : fallback;
    }

    /** {@code layoutOverrides.band}, straight off the request DTO — used on create/variant, before it is ever
     * persisted as JSON. */
    private Map<String, Integer> overrideBand(CreativeLayoutOverrides layoutOverrides) {
        return layoutOverrides != null && layoutOverrides.getBand() != null ? layoutOverrides.getBand() : Map.of();
    }

    private Map<String, Integer> overridePadBottom(CreativeLayoutOverrides layoutOverrides) {
        return layoutOverrides != null && layoutOverrides.getPadBottom() != null ? layoutOverrides.getPadBottom() : Map.of();
    }

    /** Same, off the persisted/merged {@link JsonNode} — used on patch, where the effective value may be the
     * current Creative's stored overrides rather than anything on the request DTO. */
    private Map<String, Integer> overrideBand(JsonNode layoutOverrides) {
        return extractOverrideInts(layoutOverrides, "band");
    }

    private Map<String, Integer> overridePadBottom(JsonNode layoutOverrides) {
        return extractOverrideInts(layoutOverrides, "padBottom");
    }

    private Map<String, Integer> extractOverrideInts(JsonNode layoutOverrides, String key) {
        if (layoutOverrides == null || !layoutOverrides.hasNonNull(key)) {
            return Map.of();
        }
        return objectMapper.convertValue(layoutOverrides.get(key), new TypeReference<Map<String, Integer>>() {
        });
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    Creative findCreative(String projectId, String creativeId) {
        return creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage Creatives");
        }
    }
}
