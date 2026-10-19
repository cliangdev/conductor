package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TikTok creator's live profile, for the consent page and the consent write.
 *
 * <p>TikTok's guidelines ask for creator_info to be queried when the posting page renders, not read from
 * what was cached at connect time: a creator can switch off comments, change their account to private or
 * be rate-limited at any point, and the options a person is shown have to be the options TikTok will
 * accept. The call is limited to 20 per minute per token, so a result is kept per connection for
 * {@link #CACHE_TTL} — a page of twenty Posts on one account makes one call. The cache is in memory and
 * per instance on purpose: it only has to absorb one page load, and a stale entry expires on its own.
 *
 * <p>Each successful refresh also writes the non-secret facts back to the connection's cached config
 * (privacy levels, duration cap, interaction switches), so the target picker and the approval gate read
 * what this read found. The avatar URL expires after two hours and is therefore never stored.
 *
 * <p>Nothing here throws for a creator who simply cannot post: the answer carries {@code canPost=false}
 * and a reason a person can act on, so one bad account never takes down a page of good ones.
 */
@Service
public class TikTokCreatorInfoService {

    private static final Logger log = LoggerFactory.getLogger(TikTokCreatorInfoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /**
     * What a person needs to know about a creator before posting for them.
     *
     * @param reconnectRequired true when the connection's token is dead and the account must be reconnected
     */
    public record Creator(String connectionId, String nickname, String username, String avatarUrl,
                          List<String> privacyLevelOptions, boolean commentDisabled, boolean duetDisabled,
                          boolean stitchDisabled, Integer maxVideoPostDurationSec, boolean canPost,
                          String cannotPostReason, boolean reconnectRequired) {}

    /** An answer, and whether it is TikTok's (worth keeping) or our own failure (worth retrying). */
    private record Result(Creator creator, boolean cacheable) {}

    private record Cached(Creator creator, Instant fetchedAt) {}

    private final TikTokCreatorInfoSource source;
    private final ConnectionRepository connectionRepository;
    private final TransactionTemplate configTx;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public TikTokCreatorInfoService(TikTokCreatorInfoSource source, ConnectionRepository connectionRepository,
                                    PlatformTransactionManager transactionManager) {
        this(source, connectionRepository, newTransactionTemplate(transactionManager), Clock.systemUTC());
    }

    TikTokCreatorInfoService(TikTokCreatorInfoSource source, ConnectionRepository connectionRepository,
                             TransactionTemplate configTx, Clock clock) {
        this.source = source;
        this.connectionRepository = connectionRepository;
        this.configTx = configTx;
        this.clock = clock;
    }

    /**
     * The config write-back commits in a transaction of its own: callers are often read-only page loads,
     * whose transactions never flush, and a cache write must not ride on (or roll back with) them.
     */
    private static TransactionTemplate newTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** The creator behind {@code connection}: a cached answer under a minute old, otherwise a fresh query. */
    public Creator get(Connection connection) {
        Cached hit = cache.get(connection.getId());
        if (hit != null && Duration.between(hit.fetchedAt(), clock.instant()).compareTo(CACHE_TTL) < 0) {
            return hit.creator();
        }
        Result result = query(connection);
        if (result.cacheable()) {
            cache.put(connection.getId(), new Cached(result.creator(), clock.instant()));
        }
        return result.creator();
    }

    private Result query(Connection connection) {
        try {
            TikTokClient.CreatorInfo info = source.query(connection);
            if (info.canPost()) {
                writeBack(connection.getId(), info);
            }
            return new Result(new Creator(connection.getId(), info.nickname(), info.username(), info.avatarUrl(),
                    info.privacyLevelOptions() == null ? List.of() : info.privacyLevelOptions(),
                    info.commentDisabled(), info.duetDisabled(), info.stitchDisabled(),
                    info.maxVideoPostDurationSec(), info.canPost(), info.cannotPostReason(), false), true);
        } catch (OAuthReauthRequiredException e) {
            return new Result(unavailable(connection, "This TikTok account needs to be reconnected", true), true);
        } catch (RuntimeException e) {
            log.warn("creator_info refresh failed for connection {}: {}", connection.getId(), e.getMessage());
            return new Result(unavailable(connection, "Could not reach TikTok — try again in a minute", false),
                    false);
        }
    }

    private static Creator unavailable(Connection connection, String reason, boolean reconnect) {
        return new Creator(connection.getId(), null, null, null, List.of(), false, false, false, null,
                false, reason, reconnect);
    }

    /** Merges the refreshed non-secret facts into the connection's config, touching nothing else. */
    private void writeBack(String connectionId, TikTokClient.CreatorInfo info) {
        try {
            configTx.executeWithoutResult(status -> mergeIntoConfig(connectionId, info));
        } catch (Exception e) {
            // The cache is a convenience; failing to write it must not fail a page that has fresh data.
            log.warn("Could not cache creator_info on connection {}: {}", connectionId, e.getMessage());
        }
    }

    private void mergeIntoConfig(String connectionId, TikTokClient.CreatorInfo info) {
        try {
            Connection current = connectionRepository.findById(connectionId).orElse(null);
            if (current == null) {
                return;
            }
            Map<String, Object> config = new TreeMap<>();
            if (current.getConfigJson() != null && !current.getConfigJson().isBlank()) {
                config.putAll(MAPPER.readValue(current.getConfigJson(), new TypeReference<Map<String, Object>>() { }));
            }
            Map<String, Object> before = new TreeMap<>(config);
            putIfPresent(config, TikTokConnector.CONFIG_CREATOR_NICKNAME, info.nickname());
            putIfPresent(config, TikTokConnector.CONFIG_CREATOR_USERNAME, info.username());
            if (info.privacyLevelOptions() != null && !info.privacyLevelOptions().isEmpty()) {
                config.put(TikTokConnector.CONFIG_PRIVACY_LEVEL_OPTIONS, List.copyOf(info.privacyLevelOptions()));
            }
            if (info.maxVideoPostDurationSec() != null && info.maxVideoPostDurationSec() > 0) {
                config.put(TikTokConnector.CONFIG_MAX_VIDEO_DURATION_SEC, info.maxVideoPostDurationSec());
            }
            config.put(TikTokConnector.CONFIG_COMMENT_DISABLED, info.commentDisabled());
            config.put(TikTokConnector.CONFIG_DUET_DISABLED, info.duetDisabled());
            config.put(TikTokConnector.CONFIG_STITCH_DISABLED, info.stitchDisabled());
            if (MAPPER.valueToTree(config).equals(MAPPER.valueToTree(before))) {
                return;
            }
            current.setConfigJson(MAPPER.writeValueAsString(config));
            connectionRepository.save(current);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Connection config is not readable JSON", e);
        }
    }

    private static void putIfPresent(Map<String, Object> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }
}
