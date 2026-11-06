package com.conductor.marketing.links;

import com.conductor.service.WorkItemService;
import com.conductor.service.publish.PublishingWorkflow;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives every new Post its campaign link: when a Work Item is created on a publishing Workflow, its tracked link is
 * made in the same transaction (see {@link TrackedLinkService#createForPost}). Without an App Store Connect
 * connection and provider token it does nothing, so a project that never set up App Store links creates Posts as
 * before.
 *
 * <p>Deliberately an in-transaction {@link EventListener}, not an after-commit one: an after-commit listener would
 * write the link on a second pooled connection while the Post's is still held, which can starve a small pool (prod
 * has five) when several Posts are created at once. Joining the Post's transaction also means a link exists
 * exactly when its Post does.
 */
@Component
public class PostTrackedLinkListener {

    private final PublishingWorkflow publishingWorkflow;
    private final TrackedLinkService trackedLinks;

    public PostTrackedLinkListener(PublishingWorkflow publishingWorkflow, TrackedLinkService trackedLinks) {
        this.publishingWorkflow = publishingWorkflow;
        this.trackedLinks = trackedLinks;
    }

    @EventListener
    public void onWorkItemCreated(WorkItemService.Created event) {
        if (publishingWorkflow.publishes(event.workItem())) {
            trackedLinks.createForPost(event.workItem());
        }
    }
}
