package com.conductor.repository;

import com.conductor.entity.PublishConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The standing posting consent on a Post (MKT-1). At most one row per Work Item, enforced by
 * {@code uq_publish_consent_work_item}, so the lookup is an {@link Optional} rather than a list.
 */
@Repository
public interface PublishConsentRepository extends JpaRepository<PublishConsent, String> {

    Optional<PublishConsent> findByWorkItemId(String workItemId);

    /** {@code [workItemId, consentHash]} rows, without loading the entity or its consenting user. */
    @Query("SELECT c.workItem.id, c.consentHash FROM PublishConsent c WHERE c.workItem.id IN :workItemIds")
    List<Object[]> findHashesByWorkItemIdIn(@Param("workItemIds") Collection<String> workItemIds);

    void deleteByWorkItemId(String workItemId);
}
