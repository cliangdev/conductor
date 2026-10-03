package com.conductor.creative;

import com.conductor.creative.CreativeValidationException.Violation;
import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeDraftSpecRequest;
import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.generated.v2.model.CreativeState;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.LocalMediaInfo;
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
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
    private final CreativeSpecBuilder specBuilder;
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
                           CreativeSpecBuilder specBuilder,
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
        this.specBuilder = specBuilder;
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

    /** A draft Creative's render spec and readiness — built from a request, never saved. */
    public record DraftSpec(CreativeRenderSpec spec, Readiness readiness) {
    }

    /** {@link CreativeRenderSpec#getRenderId()} of a draft: nothing was recorded, so there is no render. */
    static final String DRAFT_RENDER_ID = "draft";

    /** A {@code local:<key>} media reference's key (draft spec only). */
    static final Pattern LOCAL_MEDIA_KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

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
        Creative creative = prepareCreate(projectId, request, new MediaResolver(projectId, null), false).creative();
        creative.setCreatedBy(caller.getId());

        creative = saveWithNextNumber(creative);
        return toView(projectId, creative, loadPhotos(List.of(creative)));
    }

    /** A validated, not-yet-saved Creative, plus what was resolved while validating it. */
    private record PreparedCreate(BrandKit kit, Creative creative, MotionResolution motion) {
    }

    /**
     * Everything {@link #createCreative} does short of saving: resolves the kit, applies the create
     * defaults, resolves every media reference, runs {@link CreativeValidator#validate} and — when it
     * passes — returns the new, <b>unsaved</b> {@link Creative}. A draft spec runs exactly this too, so a
     * draft is judged by the very rules create enforces; {@code draft} only changes how the default Brand
     * Kit is read (never seeded) — no repository write happens here either way.
     *
     * @throws CreativeValidationException every violation found, including {@code media}'s own
     */
    private PreparedCreate prepareCreate(String projectId, CreateCreativeRequest request, MediaResolver media,
                                         boolean draft) {
        BrandKit kit = request.getBrandKitId() != null ? requireKit(projectId, request.getBrandKitId())
                : (draft ? brandKitService.peekDefault(projectId) : brandKitService.resolveDefault(projectId));

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

        PhotoResolution mainPhoto = resolvePhoto(media, "photoId", request.getPhotoId(), CreativePhoto.MEDIA_KIND_IMAGE);
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(media, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(media, clipMedia);
        MotionResolution motionRes = resolveMotion(media, kind, request.getMotion(), request.getAudio());

        // A draft is a render, so it is validated the way requestRender validates one: READY-level
        // checks forced whatever state was asked for. Otherwise a preview could pass (a still with no
        // photo, say) and the render then refuse the very Creative the person approved.
        String validationState = draft ? Creative.STATE_READY : state;
        List<Violation> violations = new ArrayList<>(validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, request.getHeadline(), request.getBody(), request.getCaption(),
                validationState, request.getPhotoId(), mainPhoto.resolvable(), mainPhoto.photo() != null,
                mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(),
                sequenceKind, toValidatorBeats(sequence), request.getCarouselRatio(), sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides), kind, clipMediaEntries,
                motionRes.input())));
        violations.addAll(validator.validateTypeOverrides(request.getTypeOverrides()));
        violations.addAll(0, media.violations());
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
        creative.setTypeOverrides(request.getTypeOverrides() != null
                ? objectMapper.valueToTree(request.getTypeOverrides()) : objectMapper.createObjectNode());
        creative.setLayoutOverrides(layoutOverrides != null ? objectMapper.valueToTree(layoutOverrides) : null);
        creative.setLockup(lockup);
        creative.setKind(kind);
        creative.setClipMedia(clipMedia != null && !clipMedia.isEmpty() ? objectMapper.valueToTree(clipMedia) : null);
        creative.setMotion(motionRes.motion() != null ? objectMapper.valueToTree(motionRes.motion()) : null);
        creative.setAudio(motionRes.audio() != null ? objectMapper.valueToTree(motionRes.audio()) : null);
        return new PreparedCreate(kit, creative, motionRes);
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

        MediaResolver media = new MediaResolver(projectId, null);
        PhotoResolution mainPhoto = resolvePhoto(media, "photoId", photoId, CreativePhoto.MEDIA_KIND_IMAGE);
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(media, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(media, clipMedia);
        MotionResolution motionRes = resolveMotion(media, kind, motionRequest, audioRequest);

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, headline, body, caption, state, photoId, mainPhoto.resolvable(),
                mainPhoto.photo() != null, mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(), sequenceKind,
                toValidatorBeats(sequence), carouselRatio, sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides), kind, clipMediaEntries,
                motionRes.input()));
        violations.addAll(validator.validateTypeOverrides(request.getTypeOverrides()));
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

        MediaResolver media = new MediaResolver(projectId, null);
        PhotoResolution mainPhoto = resolvePhoto(media, "photoId", root.getPhotoId(), CreativePhoto.MEDIA_KIND_IMAGE);
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(media, sequence);
        List<CreativeValidator.ClipMediaEntry> clipMediaEntries = resolveClipMedia(media, toClipMediaMap(root.getClipMedia()));
        MotionResolution motionRes = resolveMotion(media, root.getKind(), toMotion(root.getMotion()), toAudio(root.getAudio()));

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
        return computeReadiness(creative, new MediaResolver(projectId, null));
    }

    /**
     * The readiness checklist of a Creative — saved, or (a draft spec) merely built — judged on the media
     * {@code media} resolves. A {@code local:<key>} file resolves to a {@link MediaFacts#local} one: not yet
     * checked, no source or licence, so the checklist reports what it still needs once uploaded.
     */
    private Readiness computeReadiness(Creative creative, MediaResolver media) {
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
            addClipReadinessItems(media, creative, items);
        } else if (isMotion) {
            addMotionReadinessItems(media, creative, items);
        } else {
            addPhotoReadinessItems(media, creative, items);
        }

        boolean notDraft = !Creative.STATE_DRAFT.equals(creative.getState());
        items.add(new ReadinessItem("state", notDraft, true,
                notDraft ? "not a draft" : "Creative is still a draft"));

        boolean ready = items.stream().noneMatch(i -> i.blocking() && !i.ok());
        return new Readiness(ready, items);
    }

    private void addPhotoReadinessItems(MediaResolver media, Creative creative, List<ReadinessItem> items) {
        MediaFacts photo = media.find("photoId", creative.getPhotoId(), CreativePhoto.MEDIA_KIND_IMAGE);
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
    private void addMotionReadinessItems(MediaResolver media, Creative creative, List<ReadinessItem> items) {
        CreativeMotion motion = toMotion(creative.getMotion());
        CreativeMotionBackground background = motion != null ? motion.getBackground() : null;
        String backgroundSource = background != null ? background.getSource() : null;

        if ("clip".equals(backgroundSource)) {
            String clipMediaId = background.getClipMediaId();
            boolean hasClip = notBlank(clipMediaId);
            items.add(new ReadinessItem("motionBackground", hasClip, true,
                    hasClip ? "a background clip is chosen" : "no background clip chosen"));
            MediaFacts clipMedia = hasClip
                    ? media.find("motion.background.clipMediaId", clipMediaId, CreativePhoto.MEDIA_KIND_VIDEO) : null;
            if (clipMedia != null) {
                boolean provenanced = notBlank(clipMedia.getSource()) && notBlank(clipMedia.getLicence());
                items.add(new ReadinessItem("clipProvenance", provenanced, true,
                        provenanced ? "the background clip has source and licence"
                                : "the background clip is missing source and/or licence"));
            }
        } else {
            addPhotoReadinessItems(media, creative, items);
        }

        CreativeAudio audio = toAudio(creative.getAudio());
        if (audio != null && "track".equals(audio.getSource())) {
            String trackId = audio.getTrackId();
            MediaFacts track = notBlank(trackId)
                    ? media.find("audio.trackId", trackId, CreativePhoto.MEDIA_KIND_AUDIO) : null;
            boolean provenanced = track != null && notBlank(track.getSource()) && notBlank(track.getLicence());
            items.add(new ReadinessItem("audioProvenance", provenanced, true,
                    provenanced ? "the audio track has source and licence" : "the audio track is missing source and/or licence"));
        }
    }

    /** Readiness for CLIP (COND-24 PR1): caption (above), alt text (advisory), the chosen clip's media
     *  source/licence, and its AI-disclosure flag (informational — mirrors {@link #addPhotoReadinessItems}). */
    private void addClipReadinessItems(MediaResolver media, Creative creative, List<ReadinessItem> items) {
        Map<String, String> clipMedia = toClipMediaMap(creative.getClipMedia());
        boolean hasClip = !clipMedia.isEmpty();
        items.add(new ReadinessItem("clip", hasClip, true,
                hasClip ? "a clip is chosen" : "no clip chosen: pick at least one video for this Creative"));

        MediaFacts clip = representativeClipMedia(media, clipMedia);
        if (clip != null) {
            boolean provenanced = notBlank(clip.getSource()) && notBlank(clip.getLicence());
            items.add(new ReadinessItem("mediaProvenance", provenanced, true,
                    provenanced ? "the clip has source and licence" : "the clip is missing source and/or licence"));
        }
        boolean aiGenerated = clip != null && clip.isAiGenerated();
        items.add(new ReadinessItem("aiDisclosure", !aiGenerated, false,
                aiGenerated ? "the clip is AI-generated: tick the AI-disclosure toggle on upload" : "not AI-generated"));
    }

    /** The {@code "default"} entry's media, or the first entry's if there is no default — the one clip
     *  {@link #addClipReadinessItems} reads provenance/AI-disclosure off. */
    private MediaFacts representativeClipMedia(MediaResolver media, Map<String, String> clipMedia) {
        if (clipMedia.isEmpty()) {
            return null;
        }
        String mediaId = clipMedia.containsKey("default") ? clipMedia.get("default") : clipMedia.values().iterator().next();
        return media.findLibrary(mediaId);
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

    // ── Draft spec (preview-first) ────────────────────────────────────────

    /**
     * The render spec — and readiness — of a Creative that has <b>not</b> been saved, so a client can render
     * a draft locally before anything is uploaded. Judged by exactly the rules {@link #createCreative}
     * enforces ({@link #prepareCreate}), but persists nothing: no Creative, no render, no media row, no
     * storage write (read-only URL signing aside), and no default Brand Kit is seeded. The Creative is built
     * in memory only and is never handed to a repository, so no entity of the draft becomes managed.
     *
     * <p>Any media field may name {@code local:<key>} instead of a library id, declared in
     * {@code request.localMedia}; those are never looked up, and the spec carries them as the literal
     * {@code local:<key>} for the local job to resolve. With {@code baseCreativeId}, the saved Creative's
     * fields are the starting point and every field present on the request overlays them.
     */
    @Transactional(readOnly = true)
    public DraftSpec buildDraftSpec(String projectId, CreativeDraftSpecRequest request, User caller) {
        requireEditor(projectId, caller);
        Creative base = request.getBaseCreativeId() != null ? findCreative(projectId, request.getBaseCreativeId()) : null;
        CreateCreativeRequest effective = overlayOnBase(base, request);

        if (Creative.KIND_CLIP.equals(enumValue(effective.getKind(), Creative.KIND_STILL))) {
            // A CLIP is assembled from its media, not rendered, so there is no spec to preview (cf. the
            // preview-only refusal on a CLIP render).
            throw new CreativeValidationException(List.of(new Violation("kind", "clipNoDraft",
                    "a CLIP is assembled from its chosen media, not rendered: there is no draft spec to build")));
        }

        MediaResolver media = new MediaResolver(projectId,
                request.getLocalMedia() != null ? request.getLocalMedia() : Map.of());
        PreparedCreate prepared = prepareCreate(projectId, effective, media, true);
        BrandKit kit = prepared.kit();
        Creative creative = prepared.creative();
        if (base != null && request.getTypeOverrides() == null) {
            boolean headlineChanging = request.getHeadline() != null && !Objects.equals(request.getHeadline(), base.getHeadline());
            boolean layoutChanging = request.getLayout() != null && !Objects.equals(request.getLayout(), base.getLayout());
            // Same rule as patch: hand-tuned type sizes belong to the headline/layout they were tuned for
            // (unless the draft pins its own typeOverrides, which prepareCreate has already applied).
            creative.setTypeOverrides(headlineChanging || layoutChanging
                    ? objectMapper.createObjectNode() : base.getTypeOverrides());
        }

        MediaFacts mainPhoto = media.find("photoId", creative.getPhotoId(), CreativePhoto.MEDIA_KIND_IMAGE);
        Map<String, MediaFacts> beatPhotos = new LinkedHashMap<>();
        List<SequenceBeat> sequence = toSequenceBeats(creative.getSequence());
        for (int i = 0; i < sequence.size(); i++) {
            String photoId = sequence.get(i).getPhotoId();
            MediaFacts beatPhoto = media.find("sequence[" + i + "].photoId", photoId, CreativePhoto.MEDIA_KIND_IMAGE);
            if (beatPhoto != null) {
                beatPhotos.put(photoId, beatPhoto);
            }
        }
        CreativeSpecBuilder.MotionMedia motionMedia = Creative.KIND_MOTION.equals(creative.getKind())
                ? new CreativeSpecBuilder.MotionMedia(prepared.motion().motion(), prepared.motion().audio(),
                        prepared.motion().clip(), prepared.motion().track())
                : null;

        CreativeRenderSpec spec = specBuilder.build(DRAFT_RENDER_ID, !Boolean.FALSE.equals(request.getPreviewOnly()),
                creative, kit, mainPhoto, beatPhotos, specBuilder.resolvePlacements(kit, creative), motionMedia);
        return new DraftSpec(spec, computeReadiness(creative, media));
    }

    /**
     * The create request a draft stands for: the saved {@code base} Creative's fields (when there is one),
     * with every field the draft request carries laid over them. A field is "present" when it is non-null;
     * a list is present when it is non-empty (the generated model cannot tell an omitted list from an empty
     * one), so a draft cannot clear a base Creative's placements or sequence — only replace them.
     */
    private CreateCreativeRequest overlayOnBase(Creative base, CreativeDraftSpecRequest draft) {
        CreateCreativeRequest merged = new CreateCreativeRequest();
        if (base != null) {
            merged.brandKitId(base.getBrandKitId())
                    .name(base.getName())
                    .state(CreativeState.fromValue(base.getState()))
                    .layout(base.getLayout())
                    .theme(CreativeTheme.fromValue(base.getTheme()))
                    .photoId(base.getPhotoId())
                    .focalOverride(base.getFocalOverride() != null ? toStringMap(base.getFocalOverride()) : null)
                    .headline(base.getHeadline())
                    .body(base.getBody())
                    .caption(base.getCaption())
                    .altText(base.getAltText())
                    .placements(new ArrayList<>(toStringList(base.getPlacements())))
                    .sequenceKind(base.getSequenceKind() != null ? SequenceKind.fromValue(base.getSequenceKind()) : null)
                    .sequence(new ArrayList<>(toSequenceBeats(base.getSequence())))
                    .carouselRatio(base.getCarouselRatio())
                    .lockup(CreativeLockup.fromValue(base.getLockup()))
                    .kind(CreativeKind.fromValue(base.getKind()))
                    .clipMedia(base.getClipMedia() != null ? new HashMap<>(toClipMediaMap(base.getClipMedia())) : null)
                    .motion(toMotion(base.getMotion()))
                    .audio(toAudio(base.getAudio()))
                    .layoutOverrides(base.getLayoutOverrides() != null
                            ? objectMapper.convertValue(base.getLayoutOverrides(), CreativeLayoutOverrides.class) : null);
        }
        if (draft.getBrandKitId() != null) merged.setBrandKitId(draft.getBrandKitId());
        if (draft.getName() != null) merged.setName(draft.getName());
        if (draft.getState() != null) merged.setState(draft.getState());
        if (draft.getLayout() != null) merged.setLayout(draft.getLayout());
        if (draft.getTheme() != null) merged.setTheme(draft.getTheme());
        if (draft.getPhotoId() != null) merged.setPhotoId(draft.getPhotoId());
        if (draft.getFocalOverride() != null) merged.setFocalOverride(draft.getFocalOverride());
        if (draft.getHeadline() != null) merged.setHeadline(draft.getHeadline());
        if (draft.getBody() != null) merged.setBody(draft.getBody());
        if (draft.getCaption() != null) merged.setCaption(draft.getCaption());
        if (draft.getAltText() != null) merged.setAltText(draft.getAltText());
        if (draft.getPlacements() != null && !draft.getPlacements().isEmpty()) merged.setPlacements(draft.getPlacements());
        if (draft.getSequenceKind() != null) merged.setSequenceKind(draft.getSequenceKind());
        if (draft.getSequence() != null && !draft.getSequence().isEmpty()) merged.setSequence(draft.getSequence());
        if (draft.getCarouselRatio() != null) merged.setCarouselRatio(draft.getCarouselRatio());
        if (draft.getLockup() != null) merged.setLockup(draft.getLockup());
        if (draft.getKind() != null) merged.setKind(draft.getKind());
        if (draft.getClipMedia() != null) merged.setClipMedia(draft.getClipMedia());
        if (draft.getMotion() != null) merged.setMotion(draft.getMotion());
        if (draft.getAudio() != null) merged.setAudio(draft.getAudio());
        if (draft.getLayoutOverrides() != null) merged.setLayoutOverrides(draft.getLayoutOverrides());
        // The base's pinned typeOverrides are deliberately not copied in (see buildDraftSpec): they are kept
        // or cleared there by the headline/layout rule; only a draft's own typeOverrides pass through here.
        if (draft.getTypeOverrides() != null) merged.setTypeOverrides(draft.getTypeOverrides());
        return merged;
    }

    /**
     * Resolves the media references of one request: a library id against the project's library, and — only
     * when {@code localMedia} was supplied (a draft spec) — a {@code local:<key>} reference against that
     * declaration. A local reference is never looked up, so it is neither "not found" nor "blocked"; but it
     * must be a well-formed key, declared, and of the kind its field takes, else {@link #violations()}
     * names the field. An invalid local reference still resolves (to a stand-in of the expected kind), so
     * the validator does not pile a second, misleading violation on top of the real one.
     *
     * <p>Results are cached for the request, so the validator, the spec and the readiness checklist all see
     * one lookup and a violation is reported once.
     */
    private final class MediaResolver {
        private final String projectId;
        /** Null when local references are not accepted (every write other than a draft spec). */
        private final Map<String, LocalMediaInfo> localMedia;
        private final Map<String, MediaFacts> resolved = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private final List<Violation> violations = new ArrayList<>();

        MediaResolver(String projectId, Map<String, LocalMediaInfo> localMedia) {
            this.projectId = projectId;
            this.localMedia = localMedia;
            if (localMedia != null) {
                for (String key : new TreeSet<>(localMedia.keySet())) {
                    if (!LOCAL_MEDIA_KEY.matcher(key).matches()) {
                        report("localMedia", "localMediaKey", "localMedia key \"" + key + "\" must match "
                                + LOCAL_MEDIA_KEY.pattern());
                    }
                }
            }
        }

        String projectId() {
            return projectId;
        }

        List<Violation> violations() {
            return violations;
        }

        boolean isLocalRef(String ref) {
            return localMedia != null && ref != null && ref.startsWith(MediaFacts.LOCAL_PREFIX);
        }

        /** The media {@code ref} names, or null when it names nothing. {@code field} and {@code expectedKind}
         *  matter only to a local reference, which is checked against what its field takes. */
        MediaFacts find(String field, String ref, String expectedKind) {
            if (ref == null) {
                return null;
            }
            return isLocalRef(ref) ? findLocal(field, ref, expectedKind) : findLibrary(ref);
        }

        MediaFacts findLibrary(String id) {
            if (id == null || missing.contains(id)) {
                return null;
            }
            MediaFacts cached = resolved.get(id);
            if (cached != null) {
                return cached;
            }
            MediaFacts found = photoRepository.findByIdAndProjectId(id, projectId).map(MediaFacts::of).orElse(null);
            if (found == null) {
                missing.add(id);
            } else {
                resolved.put(id, found);
            }
            return found;
        }

        private MediaFacts findLocal(String field, String ref, String expectedKind) {
            String key = ref.substring(MediaFacts.LOCAL_PREFIX.length());
            LocalMediaInfo declared = localMedia.get(key);
            LocalMediaInfo usable = null;
            if (!LOCAL_MEDIA_KEY.matcher(key).matches()) {
                report(field, "localMediaKey", field + " \"" + ref + "\": the key must match " + LOCAL_MEDIA_KEY.pattern());
            } else if (declared == null) {
                report(field, "localMediaUndeclared", field + " \"" + ref + "\" is not declared in localMedia");
            } else if (declared.getKind() == null || !expectedKind.equals(declared.getKind().getValue())) {
                report(field, "localMediaKind", field + " \"" + ref + "\" is declared as "
                        + (declared.getKind() != null ? declared.getKind().getValue() : "no kind")
                        + " but this field takes a " + expectedKind);
            } else {
                usable = declared;
            }
            return usable != null
                    ? MediaFacts.local(key, expectedKind, usable.getDurationSeconds(), usable.getHasAudio(),
                            usable.getWidth(), usable.getHeight())
                    : MediaFacts.local(key, expectedKind, null, null, null, null);
        }

        private void report(String field, String ruleId, String message) {
            Violation violation = new Violation(field, ruleId, message);
            if (!violations.contains(violation)) {
                violations.add(violation);
            }
        }
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

    private record PhotoResolution(MediaFacts photo, boolean resolvable) {
    }

    private PhotoResolution resolvePhoto(MediaResolver media, String field, String photoId, String expectedKind) {
        if (photoId == null) {
            return new PhotoResolution(null, true);
        }
        MediaFacts photo = media.find(field, photoId, expectedKind);
        return new PhotoResolution(photo, photo != null);
    }

    private record SequencePhotoResolution(Set<String> unknownIds) {
    }

    private SequencePhotoResolution resolveSequencePhotos(MediaResolver media, List<SequenceBeat> sequence) {
        Set<String> referenced = new HashSet<>();
        for (int i = 0; i < sequence.size(); i++) {
            String photoId = sequence.get(i).getPhotoId();
            if (photoId == null) {
                continue;
            }
            if (media.isLocalRef(photoId)) {
                // Not in the library, so never "unknown": declared and the right kind, or a violation of its own.
                media.find("sequence[" + i + "].photoId", photoId, CreativePhoto.MEDIA_KIND_IMAGE);
            } else {
                referenced.add(photoId);
            }
        }
        if (referenced.isEmpty()) {
            return new SequencePhotoResolution(Set.of());
        }
        Set<String> existing = new HashSet<>();
        for (CreativePhoto photo : photoRepository.findAllByIdIn(referenced)) {
            if (media.projectId().equals(photo.getProjectId())) {
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
    private List<CreativeValidator.ClipMediaEntry> resolveClipMedia(MediaResolver media, Map<String, String> clipMedia) {
        if (clipMedia == null || clipMedia.isEmpty()) {
            return List.of();
        }
        List<CreativeValidator.ClipMediaEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : clipMedia.entrySet()) {
            String mediaId = entry.getValue();
            MediaFacts clip = media.findLibrary(mediaId);
            entries.add(new CreativeValidator.ClipMediaEntry(entry.getKey(), mediaId, clip != null,
                    clip != null && clip.isVideo(), clip != null && clip.isUploaded(),
                    clip != null && clip.isBlocked()));
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

    /** {@code motion}/{@code audio}, defaults already applied, the media they reference, and the resolved
     *  {@link CreativeValidator.MotionInput} to validate against. {@code motion}/{@code input} are null for a
     *  non-MOTION kind — {@code creative.motion}/{@code audio} stay whatever was passed through untouched. */
    private record MotionResolution(CreativeMotion motion, CreativeAudio audio, MediaFacts clip, MediaFacts track,
                                    CreativeValidator.MotionInput input) {
    }

    /**
     * Applies MOTION's write-time defaults (contract: preset {@code fade-up}, {@code durationSec} 8,
     * background photo {@code zoom-in}, {@code endCard} true, audio defaulting to {@code clip} when the
     * background is a clip with sound else {@code none}) and resolves its media references (background
     * clip, audio track) against the project's library, mirroring {@link #resolveClipMedia}. A non-MOTION
     * kind passes {@code motionRequest}/{@code audioRequest} through untouched with no validator input.
     */
    private MotionResolution resolveMotion(MediaResolver media, String kind, CreativeMotion motionRequest,
                                           CreativeAudio audioRequest) {
        if (!Creative.KIND_MOTION.equals(kind)) {
            return new MotionResolution(motionRequest, audioRequest, null, null, null);
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

        MediaFacts clipMedia = resolvePhoto(media, "motion.background.clipMediaId", background.getClipMediaId(),
                CreativePhoto.MEDIA_KIND_VIDEO).photo();

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

        MediaFacts trackMedia = resolvePhoto(media, "audio.trackId", audio.getTrackId(),
                CreativePhoto.MEDIA_KIND_AUDIO).photo();

        return new MotionResolution(motion, audio, clipMedia, trackMedia,
                CreativeValidator.MotionInput.resolve(motion, audio, clipMedia, trackMedia));
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

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
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
