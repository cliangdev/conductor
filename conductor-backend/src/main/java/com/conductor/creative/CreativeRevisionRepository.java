package com.conductor.creative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreativeRevisionRepository extends JpaRepository<CreativeRevision, String> {

    /** Newest first; pass a {@link Pageable} to cap the list. */
    List<CreativeRevision> findAllByCreativeIdOrderByRevisionNoDesc(String creativeId, Pageable pageable);

    /** The highest revision number a Creative has, or null when it has none. */
    @Query("select max(r.revisionNo) from CreativeRevision r where r.creativeId = :creativeId")
    Integer findMaxRevisionNo(@Param("creativeId") String creativeId);
}
