package com.conductor.repository;

import com.conductor.entity.PublishConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The standing posting consent on a Post (MKT-1). At most one row per Work Item, enforced by
 * {@code uq_publish_consent_work_item}, so the lookup is an {@link Optional} rather than a list.
 */
@Repository
public interface PublishConsentRepository extends JpaRepository<PublishConsent, String> {

    Optional<PublishConsent> findByWorkItemId(String workItemId);

    void deleteByWorkItemId(String workItemId);
}
