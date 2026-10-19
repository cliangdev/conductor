package com.conductor.repository;

import com.conductor.entity.PublishConsentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** The append-only consent audit (V153). Insert and read only — nothing updates or deletes a row. */
@Repository
public interface PublishConsentEventRepository extends JpaRepository<PublishConsentEvent, String> {

    List<PublishConsentEvent> findAllByWorkItemIdOrderByCreatedAtDesc(String workItemId);
}
