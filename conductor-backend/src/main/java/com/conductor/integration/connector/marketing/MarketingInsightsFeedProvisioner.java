package com.conductor.integration.connector.marketing;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.ProjectSettings;
import com.conductor.integration.AuthType;
import com.conductor.integration.ingest.ConnectorFeedProvisioner;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every project that has both enabled the Knowledge Center and published at least one Post gets a
 * {@code conductor-marketing} connection + {@code what_works_weekly} feed automatically — there is no
 * Integrations "Connect" flow for this connector (see {@link MarketingInsightsConnector}'s javadoc),
 * so provisioning has to happen from here instead of from a user action.
 *
 * <p>Idempotent the same way {@link ConnectorFeedProvisioner#reconcileExisting()} is: safe to run
 * every tick, because {@link ConnectionService#getOrCreateSingle} and {@link
 * ConnectorFeedProvisioner#reconcile} are both no-ops once the connection/feed already exist. Runs
 * hourly plus once at startup (mirroring that same reconcile-on-boot pattern) rather than on the
 * 60-second {@code ConnectorFeedScheduler} cadence — a project crossing "has a published Post" for the
 * first time is not time-sensitive to the minute.
 */
@Component
public class MarketingInsightsFeedProvisioner {

    private static final Logger log = LoggerFactory.getLogger(MarketingInsightsFeedProvisioner.class);

    private final ProjectSettingsRepository projectSettingsRepository;
    private final PostPublishTargetRepository targetRepository;
    private final ConnectionService connectionService;
    private final ConnectorFeedProvisioner connectorFeedProvisioner;

    public MarketingInsightsFeedProvisioner(ProjectSettingsRepository projectSettingsRepository,
                                            PostPublishTargetRepository targetRepository,
                                            ConnectionService connectionService,
                                            ConnectorFeedProvisioner connectorFeedProvisioner) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.targetRepository = targetRepository;
        this.connectionService = connectionService;
        this.connectorFeedProvisioner = connectorFeedProvisioner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        provisionEligibleProjects();
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void onSchedule() {
        provisionEligibleProjects();
    }

    void provisionEligibleProjects() {
        for (ProjectSettings settings : projectSettingsRepository.findAllByKnowledgeEnabledTrue()) {
            try {
                provisionOne(settings.getProjectId());
            } catch (Exception e) {
                log.error("Failed to provision marketing insights feed for project {}: {}",
                        settings.getProjectId(), e.getMessage(), e);
            }
        }
    }

    private void provisionOne(String projectId) {
        if (!targetRepository.existsByWorkItem_Project_IdAndState(projectId, PostPublishTargetState.PUBLISHED)) {
            return;
        }
        boolean isNew = connectionService.findSingle(projectId, MarketingInsightsConnector.ID).isEmpty();
        Connection connection = connectionService.getOrCreateSingle(projectId, MarketingInsightsConnector.ID, AuthType.NONE);
        if (isNew) {
            connectionService.updateLabel(connection, "Marketing insights");
            log.info("Provisioned conductor-marketing connection for project {}", projectId);
        }
        connectorFeedProvisioner.reconcile(connection);
    }
}
