package com.conductor.creative;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Ties a successful render to the slide-text revision it shows, and announces it: sets the revision's
 * {@code render_id} and publishes {@link CreativeRevisionRendered} once the surrounding transaction commits.
 * Joins the caller's transaction (it has none of its own).
 */
@Component
class CreativeRevisionRenderLinker {

    private static final Logger log = LoggerFactory.getLogger(CreativeRevisionRenderLinker.class);

    private final CreativeRevisionRepository revisionRepository;
    private final ApplicationEventPublisher publisher;

    CreativeRevisionRenderLinker(CreativeRevisionRepository revisionRepository, ApplicationEventPublisher publisher) {
        this.revisionRepository = revisionRepository;
        this.publisher = publisher;
    }

    /**
     * A render of a Creative just SUCCEEDED: if it is a render of the version an applied slide-text edit produced,
     * and no render was linked to that edit yet, link it. Any other render (a person re-rendering, a later
     * version) is none of a revision's business.
     */
    void onRenderSucceeded(CreativeRender render) {
        List<CreativeRevision> pending = revisionRepository.findUnrenderedEdits(render.getCreativeId(),
                render.getCreativeVersion(), PageRequest.of(0, 1));
        if (!pending.isEmpty()) {
            link(pending.get(0), render);
        }
    }

    void link(CreativeRevision revision, CreativeRender render) {
        revision.setRenderId(render.getId());
        revisionRepository.save(revision);
        CreativeRevisionRendered event = new CreativeRevisionRendered(revision.getProjectId(),
                revision.getCreativeId(), revision.getId(), render.getId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(CreativeRevisionRendered event) {
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException e) {
            // The render is committed and the revision linked: a failing listener must not surface from the commit.
            log.warn("A listener of CreativeRevisionRendered for revision {} failed", event.revisionId(), e);
        }
    }
}
