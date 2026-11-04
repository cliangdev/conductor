package com.conductor.creative;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeRevisionRepository extends JpaRepository<CreativeRevision, String> {

    /** Newest first; pass a {@link Pageable} to cap the list. */
    List<CreativeRevision> findAllByCreativeIdOrderByRevisionNoDesc(String creativeId, Pageable pageable);

    /** The highest revision number a Creative has, or null when it has none. */
    @Query("select max(r.revisionNo) from CreativeRevision r where r.creativeId = :creativeId")
    Integer findMaxRevisionNo(@Param("creativeId") String creativeId);

    Optional<CreativeRevision> findByIdAndCreativeId(String id, String creativeId);

    /** The Creative's one PENDING revision (the partial unique index allows at most one). */
    Optional<CreativeRevision> findFirstByCreativeIdAndStatus(String creativeId, String status);

    Optional<CreativeRevision> findFirstByCreativeIdOrderByRevisionNoDesc(String creativeId);

    Optional<CreativeRevision> findFirstByCreativeIdAndStatusOrderByRevisionNoDesc(String creativeId, String status);

    /**
     * The APPLIED slide-text edit (one with a {@code base_version}) at {@code creativeVersion} that no render has
     * been linked to yet, newest first: the revision a render of that version completes.
     */
    @Query("select r from CreativeRevision r where r.creativeId = :creativeId and r.status = 'APPLIED' "
            + "and r.baseVersion is not null and r.creativeVersion = :creativeVersion and r.renderId is null "
            + "order by r.revisionNo desc")
    List<CreativeRevision> findUnrenderedEdits(@Param("creativeId") String creativeId,
                                               @Param("creativeVersion") int creativeVersion, Pageable pageable);
}
