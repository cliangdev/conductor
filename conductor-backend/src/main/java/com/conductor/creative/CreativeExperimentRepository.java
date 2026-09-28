package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativeExperimentRepository extends JpaRepository<CreativeExperiment, String> {

    Optional<CreativeExperiment> findByIdAndProjectId(String id, String projectId);

    /** The one RUNNING experiment a family may have — {@code uq_creative_experiment_running_per_family}
     *  is the DB-side guarantee this read-then-write races against; the unique index is what actually
     *  makes a concurrent double-create 409 rather than this method. */
    Optional<CreativeExperiment> findFirstByProjectIdAndParentCreativeIdAndState(
            String projectId, String parentCreativeId, String state);

    /** Batched form of the above, for {@code CreativeResponse.activeExperimentId} on a list read. */
    List<CreativeExperiment> findAllByProjectIdAndParentCreativeIdInAndState(
            String projectId, Collection<String> parentCreativeIds, String state);

    List<CreativeExperiment> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    List<CreativeExperiment> findAllByProjectIdAndStateOrderByCreatedAtDesc(String projectId, String state);

    List<CreativeExperiment> findAllByProjectIdAndParentCreativeIdOrderByCreatedAtDesc(
            String projectId, String parentCreativeId);

    List<CreativeExperiment> findAllByProjectIdAndParentCreativeIdAndStateOrderByCreatedAtDesc(
            String projectId, String parentCreativeId, String state);

    /** Every experiment (any family, any project) that names this Creative as its winner —
     *  {@code CreativeService#deleteCreative} nulls these out rather than deleting the experiment, since
     *  the decision record should outlive the winning Creative being deleted. */
    List<CreativeExperiment> findAllByWinnerCreativeId(String winnerCreativeId);

    /** Decided since a cutoff, newest first — the weekly digest's "Hook winners" section. */
    List<CreativeExperiment> findAllByProjectIdAndStateAndDecidedAtAfterOrderByDecidedAtDesc(
            String projectId, String state, OffsetDateTime cutoff);
}
