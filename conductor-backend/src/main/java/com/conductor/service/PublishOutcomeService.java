package com.conductor.service;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What became of one {@code (post, target)} publish (COND-23 T6.1): the single place a platform's answer
 * is turned into durable state. Both lanes end here — {@code PostPublishScheduler} for APP_MANAGED and
 * {@code NativeHandoffService} for the platform's own confirmation — so "published" means the same thing,
 * and produces the same evidence, however the post got out.
 *
 * <h2>A published destination is an Asset</h2>
 * A success is not just a state change: it records one typed Asset on the Post whose {@code ref} is the
 * permalink and whose {@code type} names the destination platform ({@code facebook_post},
 * {@code instagram_post}, {@code youtube_video}, {@code tiktok_post} — exactly the four the MARKETING
 * workflow declares in its {@code asset_types}). That is COND-19's model: a Post going to three accounts
 * produces three Assets, each one a link a human can click, rather than one blob of publish metadata.
 * Recording goes through {@link AssetService#recordAsset} — the system path, which takes no caller and is
 * idempotent on {@code (workItem, type, ref)}, so re-applying a result never doubles an Asset.
 *
 * <h2>A failure is a message a human has to read</h2>
 * The platform's error text is stored <b>verbatim</b>: "(#100) The parameter image_url is required" is
 * what lets someone fix the post, and no amount of rewording improves it. When the failure is a permanent
 * auth/permission problem rather than a transient one, the connection is additionally marked unhealthy via
 * {@link ConnectionHealthService#reportPublishAuthFailure} so it surfaces on the Integrations page instead
 * of quietly failing again at the next fire time. Transient failures — rate limits, 5xx, timeouts — never
 * cost a connection its health; see {@link #isPermanentAuthFailure}.
 *
 * <h2>Terminal states are never overwritten</h2>
 * {@code REVOKED} and {@code PUBLISHED} are respected: a late failure never buries a success, and a
 * revocation is never undone by an outcome that was already in flight when it landed. Every entry point
 * returns whether <em>this</em> call is what moved the row, which is what the roll-up and retry work
 * (T6.2) builds on — it is deliberately not computed here.
 *
 * <h2>Transactions</h2>
 * Every entry point runs {@code REQUIRES_NEW}. The platform side effect has already happened by the time
 * this service is called; the record of it must not roll back with whatever the caller does next.
 */
@Service
public class PublishOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PublishOutcomeService.class);

    /**
     * How one platform's outcome is read and filed: the Asset type a published destination becomes, the
     * output key that platform reports its post id under (the connectors' own shipped vocabulary — see
     * {@code meta.json}, {@code youtube.json}, {@code tiktok.json}), and the name to fall back to when a
     * target does not carry an account label.
     */
    record OutcomePlatform(String assetType, String postIdOutputKey, String displayName) {}

    /** Keyed by the {@code post_publish_target.platform} vocabulary. */
    static final Map<String, OutcomePlatform> PLATFORMS = Map.of(
            "facebook", new OutcomePlatform("facebook_post", "post_id", "Facebook"),
            "instagram", new OutcomePlatform("instagram_post", "media_id", "Instagram"),
            "youtube", new OutcomePlatform("youtube_video", "video_id", "YouTube"),
            "tiktok", new OutcomePlatform("tiktok_post", "post_id", "TikTok"));

    /** The output key every publish action reports its public URL under. */
    private static final String PERMALINK_OUTPUT_KEY = "permalink";

    private static final String NO_RESULT_MESSAGE = "The publish action returned no result";

    /**
     * Failures that mean "this connection's credentials will not work again until a human reconnects",
     * as opposed to "try later". Matched against the platform's own error text because that is all an
     * {@link ActionResult} carries — a caller that knows better passes the verdict explicitly to
     * {@link #recordFailure(String, String, boolean)}.
     *
     * <p>Deliberately conservative: a rate limit, a 5xx and a timeout must all fall through, because
     * wrongly marking a connection unhealthy sends someone to re-authorize an account that was fine.
     */
    private static final Pattern PERMANENT_AUTH_FAILURE = Pattern.compile(
            "\\b(401|403)\\b"
                    + "|unauthori[sz]ed|forbidden"
                    + "|invalid[ _-]?(grant|token|credential|oauth)"
                    + "|oauth ?exception"
                    + "|(access[ _-]?token|credential|session|grant|permission|authorization)s?"
                    + "[^.;\\n]{0,40}?(expired|revoked|invalid|denied)"
                    + "|expired[ _-]?(access[ _-]?)?token"
                    + "|re-?authenticat|re-?authori[sz]|log ?in again"
                    + "|insufficient[ _-]?(authentication[ _-]?)?(permission|scope|privilege|credential)"
                    + "|not[ _-]?granted[^.;\\n]{0,40}?permission"
                    + "|missing[ _-]?(required[ _-]?)?(scope|permission)"
                    + "|authentication[ _-]?fail",
            Pattern.CASE_INSENSITIVE);

    private final PostPublishTargetRepository targetRepository;
    private final AssetService assetService;
    private final ConnectionHealthService connectionHealthService;

    public PublishOutcomeService(PostPublishTargetRepository targetRepository,
                                 AssetService assetService,
                                 ConnectionHealthService connectionHealthService) {
        this.targetRepository = targetRepository;
        this.assetService = assetService;
        this.connectionHealthService = connectionHealthService;
    }

    /**
     * Applies a publish invocation's result to the target it was invoked for: the success path when the
     * platform accepted the post, the failure path otherwise (a {@code null} result included — a publish
     * that reported nothing back is a failure, never a silent no-op).
     *
     * @return true when this call is what moved the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordOutcome(String targetId, ActionResult result) {
        PostPublishTarget target = find(targetId);
        if (target == null) {
            return false;
        }
        if (result == null || !result.success()) {
            String message = result == null ? NO_RESULT_MESSAGE : result.message();
            return applyFailure(target, message, isPermanentAuthFailure(message));
        }
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        OutcomePlatform platform = platformFor(target.getPlatform());
        String platformPostId = platform == null ? null : stringValue(output, platform.postIdOutputKey());
        return applySuccess(target, platformPostId, stringValue(output, PERMALINK_OUTPUT_KEY));
    }

    /**
     * The platform published this target: stores what it created, moves the row to {@code PUBLISHED} and
     * records the destination as a typed Asset on the Post.
     *
     * <p>A blank {@code permalink} still publishes the row — the post really did go out — but records no
     * Asset, because an Asset with no link is not evidence of anything.
     *
     * @return true when this call is what moved the row; false when it was already published, was revoked,
     *         or no longer exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSuccess(String targetId, String platformPostId, String permalink) {
        PostPublishTarget target = find(targetId);
        return target != null && applySuccess(target, platformPostId, permalink);
    }

    /**
     * The platform refused this target, classifying the failure from its own error text. Equivalent to
     * {@link #recordFailure(String, String, boolean)} with {@link #isPermanentAuthFailure}'s verdict.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String targetId, String errorMessage) {
        PostPublishTarget target = find(targetId);
        return target != null && applyFailure(target, errorMessage, isPermanentAuthFailure(errorMessage));
    }

    /**
     * The platform refused this target: moves the row to {@code FAILED}, stores {@code errorMessage}
     * verbatim, and bumps the attempt count.
     *
     * @param permanentAuthFailure true only when the platform rejected our identity or permissions for
     *                             good — the connection is then marked unhealthy. A rate limit, a 5xx or
     *                             a timeout is not one, and must not cost the connection its health.
     * @return true when this call is what moved the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String targetId, String errorMessage, boolean permanentAuthFailure) {
        PostPublishTarget target = find(targetId);
        return target != null && applyFailure(target, errorMessage, permanentAuthFailure);
    }

    /** The Asset type a published destination on {@code platform} becomes, or null if unrecognised. */
    static String assetTypeFor(String platform) {
        OutcomePlatform outcome = platformFor(platform);
        return outcome == null ? null : outcome.assetType();
    }

    /**
     * Whether a platform's error text describes a permanent auth/permission failure — an expired token, a
     * revoked grant, a missing scope — rather than something worth trying again. Conservative by design:
     * anything it does not recognise is treated as transient and leaves the connection's health alone.
     */
    static boolean isPermanentAuthFailure(String errorMessage) {
        return errorMessage != null && PERMANENT_AUTH_FAILURE.matcher(errorMessage).find();
    }

    private boolean applySuccess(PostPublishTarget target, String platformPostId, String permalink) {
        if (target.getState() == PostPublishTargetState.REVOKED) {
            log.warn("Publish succeeded for target {} but it was already revoked; not recording it as published",
                    target.getId());
            return false;
        }
        if (target.getState() == PostPublishTargetState.PUBLISHED) {
            // A duplicate result. The Asset write below is idempotent on (workItem, type, ref), so
            // re-running it converges rather than doubling; the row itself is left untouched.
            recordDestinationAsset(target, permalink);
            log.debug("Target {} is already PUBLISHED; outcome re-applied without moving the row", target.getId());
            return false;
        }

        target.setState(PostPublishTargetState.PUBLISHED);
        if (platformPostId != null && !platformPostId.isBlank()) {
            target.setPlatformPostId(platformPostId);
        }
        if (permalink != null && !permalink.isBlank()) {
            target.setPermalink(permalink);
        }
        target.setErrorMessage(null);
        targetRepository.save(target);

        recordDestinationAsset(target, permalink);
        log.info("Target {} published on {} (platform post {})",
                target.getId(), target.getPlatform(), target.getPlatformPostId());
        return true;
    }

    private boolean applyFailure(PostPublishTarget target, String errorMessage, boolean permanentAuthFailure) {
        if (target.getState() == PostPublishTargetState.REVOKED) {
            log.debug("Target {} was revoked; a late failure does not overwrite it", target.getId());
            return false;
        }
        if (target.getState() == PostPublishTargetState.PUBLISHED) {
            log.warn("Target {} is already PUBLISHED; ignoring a late failure: {}", target.getId(), errorMessage);
            return false;
        }

        target.setState(PostPublishTargetState.FAILED);
        target.setErrorMessage(errorMessage);
        target.setAttempts(target.getAttempts() + 1);
        targetRepository.save(target);

        log.warn("Target {} failed to publish on {}: {}", target.getId(), target.getPlatform(), errorMessage);
        if (permanentAuthFailure) {
            connectionHealthService.reportPublishAuthFailure(target.getConnectionId(), errorMessage);
        }
        return true;
    }

    /**
     * One typed Asset per published destination, named for the account it went to. Delegates the
     * {@code (workItem, type, ref)} idempotency guard to {@link AssetService#recordAsset} rather than
     * re-implementing it, so a duplicate outcome is a no-op wherever it comes from.
     */
    private void recordDestinationAsset(PostPublishTarget target, String permalink) {
        if (permalink == null || permalink.isBlank()) {
            log.warn("Target {} published on {} without a permalink; no Asset recorded",
                    target.getId(), target.getPlatform());
            return;
        }
        OutcomePlatform platform = platformFor(target.getPlatform());
        if (platform == null) {
            log.warn("Target {} names platform '{}', which has no Asset type; no Asset recorded",
                    target.getId(), target.getPlatform());
            return;
        }
        WorkItem post = target.getWorkItem();
        if (post == null) {
            log.warn("Target {} has no owning Post; no Asset recorded", target.getId());
            return;
        }
        assetService.recordAsset(post, platform.assetType(), permalink,
                accountLabel(target, platform), AssetService.KIND_LINK);
    }

    /** The account a human would recognise this destination by, falling back to the platform's name. */
    private static String accountLabel(PostPublishTarget target, OutcomePlatform platform) {
        String label = target.getPlatformAccountLabel();
        return label != null && !label.isBlank() ? label : platform.displayName();
    }

    private static OutcomePlatform platformFor(String platform) {
        return platform == null ? null : PLATFORMS.get(platform.trim().toLowerCase(Locale.ROOT));
    }

    private static String stringValue(Map<String, Object> output, String key) {
        Object value = output.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private PostPublishTarget find(String targetId) {
        PostPublishTarget target = targetRepository.findById(targetId).orElse(null);
        if (target == null) {
            log.warn("Publish outcome for target {} discarded — no such target", targetId);
        }
        return target;
    }
}
