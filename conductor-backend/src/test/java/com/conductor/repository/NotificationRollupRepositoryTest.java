package com.conductor.repository;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.RollupLine;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed coverage for {@code V155__notification_rollup.sql}: the migration applies under
 * {@code ddl-auto=validate}, the jsonb {@code lines} (including each line's stored order) round-trip, the
 * locking finder returns the row, and the row goes when its channel config does. Rides the shared
 * singleton Postgres per {@code docs/testing-guidelines.md} and isolates with random ids.
 */
@Transactional
class NotificationRollupRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private NotificationRollupRepository rollupRepository;
    @Autowired private NotificationGroupConfigRepository configRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @PersistenceContext private EntityManager entityManager;

    private NotificationGroupConfig newConfig() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Rollup Tester");
        u = userRepository.save(u);
        Project p = new Project();
        p.setName("Rollup Project");
        p.setKey("RU" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(u);
        p = projectRepository.save(p);

        NotificationGroupConfig c = new NotificationGroupConfig();
        c.setProjectId(p.getId());
        c.setChannelGroup(ChannelGroup.ISSUES);
        c.setWebhookUrl("https://discord.com/api/webhooks/1/t");
        return configRepository.saveAndFlush(c);
    }

    private NotificationRollup rollupFor(NotificationGroupConfig c) {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        NotificationRollup r = new NotificationRollup();
        r.setConfigId(c.getId());
        r.setMessageId("123456789");
        r.setStartedAt(now);
        r.setUpdatedAt(now);
        Map<String, RollupLine> lines = new LinkedHashMap<>();
        lines.put("w1", new RollupLine("w1", "RE-1", "First", "Post", "IN_REVIEW", "In Review",
                "in_progress", "http://x/1", "approved by Bryan", 0));
        lines.put("w2", new RollupLine("w2", "RE-2", "Second", "Post", "DRAFT", "Draft",
                "open", "http://x/2", null, 1));
        r.setLines(lines);
        return r;
    }

    @Test
    void rollupRoundTripsItsJsonbLinesAndLocksByConfigId() {
        NotificationGroupConfig config = newConfig();
        rollupRepository.saveAndFlush(rollupFor(config));
        entityManager.clear();

        NotificationRollup loaded = rollupRepository.findByIdForUpdate(config.getId()).orElseThrow();

        assertThat(loaded.getMessageId()).isEqualTo("123456789");
        assertThat(loaded.getLines()).containsOnlyKeys("w1", "w2");
        assertThat(loaded.getLines().get("w1").verdictNote()).isEqualTo("approved by Bryan");
        assertThat(loaded.getLines().get("w2").seq()).isEqualTo(1);
    }

    @Test
    void upsertingTheSameConfigReplacesTheLinesInPlace() {
        NotificationGroupConfig config = newConfig();
        rollupRepository.saveAndFlush(rollupFor(config));
        entityManager.clear();

        NotificationRollup loaded = rollupRepository.findByIdForUpdate(config.getId()).orElseThrow();
        Map<String, RollupLine> lines = new LinkedHashMap<>(loaded.getLines());
        lines.remove("w2");
        loaded.setLines(lines);
        loaded.setMessageId("999");
        rollupRepository.saveAndFlush(loaded);
        entityManager.clear();

        NotificationRollup again = rollupRepository.findById(config.getId()).orElseThrow();
        assertThat(again.getLines()).containsOnlyKeys("w1");
        assertThat(again.getMessageId()).isEqualTo("999");
        assertThat(rollupRepository.findByIdForUpdate("missing-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void deletingTheChannelConfigCascadesToItsRollup() {
        NotificationGroupConfig config = newConfig();
        rollupRepository.saveAndFlush(rollupFor(config));
        entityManager.flush();

        entityManager.createNativeQuery("delete from notification_group_config where id = :id")
                .setParameter("id", config.getId()).executeUpdate();
        entityManager.clear();

        assertThat(rollupRepository.findById(config.getId())).isEmpty();
    }
}
