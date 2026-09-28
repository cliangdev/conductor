package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeRepository extends JpaRepository<Creative, String> {

    Optional<Creative> findByIdAndProjectId(String id, String projectId);

    List<Creative> findAllByProjectIdOrderByNumberDescVariantLetterAsc(String projectId);

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
