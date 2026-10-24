package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.NotificationRollupRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Real transactions on the shared Postgres: the roll-up runs only after the caller commits, in its own
 * transaction, and a failure inside it (here a genuine lock timeout) cannot fail or undo the caller's work.
 *
 * <p>The service graph is built by hand around a mocked {@link DiscordProvider} so no extra Spring context
 * (and Hikari pool) is created; the real {@code @Transactional} proxy is applied through the container's
 * bean post-processors. Not {@code @Transactional} itself -- commits are the point -- so it isolates with
 * random ids and deletes what it makes.
 */
class NotificationRollupTransactionTest extends AbstractNoneWebIntegrationTest {

    @Autowired private AutowireCapableBeanFactory beans;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private NotificationRollupRepository rollupRepository;
    @Autowired private NotificationGroupConfigRepository configRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private DiscordProvider discord;
    private NotificationDeliveryService delivery;
    private NotificationGroupConfig config;
    private Project project;
    private User owner;
    private TransactionTemplate tx;
    private TransactionTemplate newTx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        newTx = new TransactionTemplate(txManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        discord = mock(DiscordProvider.class);
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(anyString(), anyString())).thenReturn(new DiscordProvider.PostResult("m-1", false));

        NotificationRollupService rollup = (NotificationRollupService) beans.applyBeanPostProcessorsAfterInitialization(
                new NotificationRollupService(rollupRepository, configRepository, discord), "rollupUnderTest");
        delivery = new NotificationDeliveryService(configRepository, discord, rollup);

        owner = tx.execute(s -> {
            User u = new User();
            u.setFirebaseUid("uid-" + UUID.randomUUID());
            u.setEmail(UUID.randomUUID() + "@example.com");
            u.setName("Rollup Tx Tester");
            return userRepository.save(u);
        });
        project = tx.execute(s -> {
            Project p = new Project();
            p.setName("Rollup Tx Project");
            p.setKey("RT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            p.setCreatedBy(owner);
            return projectRepository.save(p);
        });
        config = tx.execute(s -> {
            NotificationGroupConfig c = new NotificationGroupConfig();
            c.setProjectId(project.getId());
            c.setChannelGroup(ChannelGroup.ISSUES);
            c.setProvider(ProviderType.DISCORD);
            c.setWebhookUrl("https://discord.com/api/webhooks/1/t");
            c.setEnabledEventTypes(new HashSet<>(Set.of(EventType.WORK_ITEM_STATUS_CHANGED.name())));
            return configRepository.save(c);
        });
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            configRepository.deleteById(config.getId()); // cascades the rollup row
            projectRepository.deleteById(project.getId());
            userRepository.deleteById(owner.getId());
        });
    }

    private NotificationMessage event() {
        return NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, project.getId(), Map.of(
                "workItemId", "w-" + UUID.randomUUID(), "workItemTitle", "T", "noun", "Post", "displayId", "RE-1",
                "toStatus", "SCHEDULED", "toStatusLabel", "Scheduled", "toCategory", "in_progress"));
    }

    private User newUser() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Caller work");
        return userRepository.save(u);
    }

    @Test
    void theRollupHappensAfterTheCallerCommitsNotBefore() {
        tx.executeWithoutResult(s -> {
            delivery.deliver(event());
            verifyNoInteractions(discord); // still inside the caller's transaction: nothing sent, no lock taken
        });

        verify(discord).postReturningId(anyString(), anyString());
        assertThat(rollupRepository.findById(config.getId())).isPresent();
    }

    @Test
    void threeEventsInOneCallerTransactionTakeOneLockAndMakeOneDiscordCall() {
        String itemId = "w-" + UUID.randomUUID();
        Map<String, String> meta = Map.of("workItemId", itemId, "workItemTitle", "T", "noun", "Post",
                "displayId", "RE-1", "toStatus", "SCHEDULED", "toStatusLabel", "Scheduled", "toCategory", "in_progress");
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        NotificationRollupRepository spied = org.mockito.Mockito.mock(NotificationRollupRepository.class, org.mockito.AdditionalAnswers.delegatesTo(rollupRepository));
        NotificationRollupService rollup = (NotificationRollupService) beans.applyBeanPostProcessorsAfterInitialization(
                new NotificationRollupService(spied, configRepository, discord), "rollupSpied");
        NotificationDeliveryService spiedDelivery = new NotificationDeliveryService(configRepository, discord, rollup);

        tx.executeWithoutResult(s -> {
            spiedDelivery.deliver(NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, project.getId(), meta));
            spiedDelivery.deliver(NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, project.getId(), meta));
            spiedDelivery.deliver(NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, project.getId(), meta));
            verifyNoInteractions(discord);
        });

        verify(spied, times(1)).lockChannel(config.getId());
        verify(discord, times(1)).postReturningId(anyString(), anyString());
        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(rollupRepository.findById(config.getId())).isPresent();
    }

    @Test
    void aRolledBackCallerSendsNothing() {
        tx.executeWithoutResult(s -> {
            delivery.deliver(event());
            s.setRollbackOnly();
        });

        verify(discord, never()).postReturningId(anyString(), anyString());
        verify(discord, never()).send(anyString(), anyString());
        assertThat(rollupRepository.findById(config.getId())).isEmpty();
    }

    @Test
    void aFailureInsideTheRollupNeverFailsOrUndoesTheCallersWork() {
        String[] callerEmail = new String[1];

        // Another transaction holds the channel lock, so the rollup's lock_timeout (3s) genuinely fires.
        assertThatNoException().isThrownBy(() -> newTx.executeWithoutResult(holder -> {
            rollupRepository.lockChannel(config.getId());

            newTx.executeWithoutResult(caller -> {
                User saved = newUser();
                callerEmail[0] = saved.getEmail();
                delivery.deliver(event());
            });
        }));

        // The caller's own work committed...
        assertThat(userRepository.findByEmail(callerEmail[0])).isPresent();
        // ...the rollup could not take the lock, so nothing was posted as a rolling message, and it fell
        // back to the ordinary send rather than losing the notification.
        verify(discord, never()).postReturningId(anyString(), anyString());
        verify(discord).send(anyString(), anyString());
        assertThat(rollupRepository.findById(config.getId())).isEmpty();
    }
}
