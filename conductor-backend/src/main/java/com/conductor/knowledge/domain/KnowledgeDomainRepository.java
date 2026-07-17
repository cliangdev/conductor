package com.conductor.knowledge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDomainRepository extends JpaRepository<KnowledgeDomain, String> {

    Optional<KnowledgeDomain> findByProjectIdAndSlug(String projectId, String slug);

    List<KnowledgeDomain> findByProjectIdOrderBySlugAsc(String projectId);

    List<KnowledgeDomain> findByProjectIdAndState(String projectId, KnowledgeDomainState state);
}
