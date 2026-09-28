package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
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
        List<String> placements = request.getPlacements() != null ? request.getPlacements() : List.of();
        List<SequenceBeat> sequence = request.getSequence() != null ? request.getSequence() : List.of();
        CreativeLayoutOverrides layoutOverrides = request.getLayoutOverrides();

        PhotoResolution mainPhoto = resolvePhoto(projectId, request.getPhotoId());
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(projectId, sequence);

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, request.getHeadline(), request.getBody(), request.getCaption(),
                state, request.getPhotoId(), mainPhoto.resolvable(), mainPhoto.photo() != null,
                mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(),
                sequenceKind, toValidatorBeats(sequence), request.getCarouselRatio(), sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides)));
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
        List<String> placements = request.getPlacements() != null ? request.getPlacements() : toStringList(current.getPlacements());
        List<SequenceBeat> sequence = request.getSequence() != null ? request.getSequence() : toSequenceBeats(current.getSequence());
        CreativeLayoutOverrides layoutOverridesRequest = request.getLayoutOverrides();
        JsonNode layoutOverrides = layoutOverridesRequest != null
                ? objectMapper.valueToTree(layoutOverridesRequest) : current.getLayoutOverrides();

        PhotoResolution mainPhoto = resolvePhoto(projectId, photoId);
        SequencePhotoResolution sequencePhotos = resolveSequencePhotos(projectId, sequence);

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                layout, theme, placements, headline, body, caption, state, photoId, mainPhoto.resolvable(),
                mainPhoto.photo() != null, mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(), sequenceKind,
                toValidatorBeats(sequence), carouselRatio, sequencePhotos.unknownIds(),
                overrideBand(layoutOverrides), overridePadBottom(layoutOverrides)));
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

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                root.getLayout(), root.getTheme(), placements, headline, root.getBody(), root.getCaption(),
                Creative.STATE_DRAFT, root.getPhotoId(), mainPhoto.resolvable(), mainPhoto.photo() != null,
                mainPhoto.photo() != null && mainPhoto.photo().isUploaded(),
                mainPhoto.photo() != null && mainPhoto.photo().isBlocked(),
                root.getSequenceKind(), toValidatorBeats(sequence), root.getCarouselRatio(), sequencePhotos.unknownIds(),
                overrideBand(root.getLayoutOverrides()), overridePadBottom(root.getLayoutOverrides())));
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
        CreativePhoto photo = creative.getPhotoId() != null
                ? photoRepository.findByIdAndProjectId(creative.getPhotoId(), projectId).orElse(null)
                : null;

        List<ReadinessItem> items = new ArrayList<>();
        items.add(new ReadinessItem("caption", notBlank(creative.getCaption()), true,
                notBlank(creative.getCaption()) ? "caption is set" : "caption is missing: the post needs primary text"));
        items.add(new ReadinessItem("altText", notBlank(creative.getAltText()), true,
                notBlank(creative.getAltText()) ? "alt text is set" : "alt text is missing: describe the photo for the upload"));
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
        boolean notDraft = !Creative.STATE_DRAFT.equals(creative.getState());
        items.add(new ReadinessItem("state", notDraft, true,
                notDraft ? "not a draft" : "Creative is still a draft"));
        boolean aiGenerated = photo != null && photo.isAiGenerated();
        items.add(new ReadinessItem("aiDisclosure", !aiGenerated, false,
                aiGenerated ? "photo is AI-generated: tick the AI-disclosure toggle on upload" : "not AI-generated"));

        boolean ready = items.stream().noneMatch(i -> i.blocking() && !i.ok());
        return new Readiness(ready, items);
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
