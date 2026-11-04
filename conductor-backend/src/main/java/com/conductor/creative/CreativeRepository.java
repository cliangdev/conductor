package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeRepository extends JpaRepository<Creative, String> {

    Optional<Creative> findByIdAndProjectId(String id, String projectId);

    /** Row lock, so two concurrent render dispatches for one Creative run one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Creative c WHERE c.id = :id AND c.projectId = :projectId")
    Optional<Creative> findByIdAndProjectIdForUpdate(@Param("id") String id, @Param("projectId") String projectId);

    /**
     * Moves a Creative from {@code fromState} to {@code toState} WITHOUT raising its {@code version}: a bulk
     * update bypasses JPA's {@code @Version} bump. Used where a state flip is not a content change, so the
     * renders already made for this version must keep reading as current. Flushes pending changes first and
     * clears the persistence context afterwards (the loaded entity would otherwise be stale), so callers must
     * not rely on entities loaded before the call. Returns the rows changed (0 when the state had moved on).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Creative c SET c.state = :toState, c.updatedAt = :now WHERE c.id = :id AND c.state = :fromState")
    int updateStateKeepingVersion(@Param("id") String id, @Param("fromState") String fromState,
                                  @Param("toState") String toState, @Param("now") OffsetDateTime now);

    List<Creative> findAllByProjectIdOrderByNumberDescVariantLetterAsc(String projectId);

    /**
     * (id, photoId, sequence, clipMedia, slides, audio, motion) of every Creative in the project: just the
     * columns that name Media, for the library's usage counts, instead of loading whole rows.
     */
    @Query("SELECT c.id, c.photoId, c.sequence, c.clipMedia, c.slides, c.audio, c.motion FROM Creative c WHERE c.projectId = :projectId")
    List<Object[]> findMediaReferencesByProjectId(@Param("projectId") String projectId);

    List<Creative> findAllByProjectIdAndStateOrderByNumberDescVariantLetterAsc(String projectId, String state);

    List<Creative> findAllByProjectIdAndBrandKitIdOrderByNumberDescVariantLetterAsc(String projectId, String brandKitId);

    List<Creative> findAllByProjectIdAndStateAndBrandKitIdOrderByNumberDescVariantLetterAsc(
            String projectId, String state, String brandKitId);

    List<Creative> findAllByNumberAndProjectId(int number, String projectId);

    /** Every Creative whose main photo is this one — the delete-photo 409 guard's main-photo half
     *  (the sequence-beat half is a JSON scan in {@code CreativePhotoService}). */
    List<Creative> findAllByPhotoId(String photoId);

    boolean existsByProjectIdAndBrandKitId(String projectId, String brandKitId);

    /** Highest existing display number in the project, or {@code null} if it has no creatives yet. */
    @Query("SELECT MAX(c.number) FROM Creative c WHERE c.projectId = :projectId")
    Integer findMaxNumber(@Param("projectId") String projectId);

    boolean existsByProjectIdAndNumberAndVariantLetter(String projectId, int number, String variantLetter);
}
