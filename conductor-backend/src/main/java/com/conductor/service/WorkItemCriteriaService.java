package com.conductor.service;

import com.conductor.entity.WorkItemCriterion;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemCriterionRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Acceptance criteria on a Work Item: the whole ordered list is replaced in one call, and one criterion at a
 * time is then ticked off with its evidence.
 *
 * <p>{@code kind} is {@code auto} (a test or an agent can verify it) or {@code manual} (a person checks); it
 * defaults to {@code auto} when omitted, matching the schema. Replacing the list keeps the progress already
 * made on a criterion that is sent again -- matched by {@code externalRef} when it has one, otherwise by
 * identical text -- so re-asserting the same list is harmless and never un-ticks anything.
 */
@Service
public class WorkItemCriteriaService {

    /** The most criteria one Work Item may carry. */
    public static final int MAX_CRITERIA = 100;

    private final WorkItemRepository workItemRepository;
    private final WorkItemCriterionRepository criterionRepository;
    private final Clock clock;

    @Autowired
    public WorkItemCriteriaService(WorkItemRepository workItemRepository,
                                   WorkItemCriterionRepository criterionRepository) {
        this(workItemRepository, criterionRepository, Clock.systemUTC());
    }

    WorkItemCriteriaService(WorkItemRepository workItemRepository,
                            WorkItemCriterionRepository criterionRepository, Clock clock) {
        this.workItemRepository = workItemRepository;
        this.criterionRepository = criterionRepository;
        this.clock = clock;
    }

    /** A criterion as a caller supplies it; {@code kind} null means {@code auto}. */
    public record CriterionInput(String text, String kind, String externalRef) {
    }

    /** A criterion after a patch, with the item's totals so the caller needs no second read. */
    public record PatchResult(WorkItemCriterion criterion, int criteriaMet, int criteriaTotal) {
    }

    @Transactional(readOnly = true)
    public List<WorkItemCriterion> list(String projectId, String itemId) {
        requireItem(projectId, itemId);
        return criterionRepository.findByWorkItemIdOrderByPositionAscCreatedAtAsc(itemId);
    }

    /**
     * Makes {@code inputs} the item's criteria, in that order (positions are reassigned from 0). A criterion
     * that was already on the item keeps its met state and evidence; ones not sent are removed.
     */
    @Transactional
    public List<WorkItemCriterion> replaceAll(String projectId, String itemId, List<CriterionInput> inputs) {
        requireItem(projectId, itemId);
        List<CriterionInput> wanted = inputs == null ? List.of() : inputs;
        if (wanted.size() > MAX_CRITERIA) {
            throw new BusinessException("A Work Item can carry at most " + MAX_CRITERIA + " criteria");
        }
        List<WorkItemCriterion> existing = criterionRepository.findByWorkItemIdOrderByPositionAscCreatedAtAsc(itemId);
        Map<String, WorkItemCriterion> available = new HashMap<>();
        for (WorkItemCriterion c : existing) {
            available.putIfAbsent(matchKey(c.getExternalRef(), c.getText()), c);
        }

        List<WorkItemCriterion> result = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            CriterionInput in = wanted.get(i);
            String text = in.text() == null ? "" : in.text().strip();
            if (text.isEmpty()) {
                throw new BusinessException("Criterion " + (i + 1) + " has no text");
            }
            String ref = blankToNull(in.externalRef());
            if (ref != null && ref.length() > 32) {
                throw new BusinessException("externalRef must be at most 32 characters");
            }
            WorkItemCriterion row = available.remove(matchKey(ref, text));
            if (row == null) {
                row = new WorkItemCriterion();
                row.setProjectId(projectId);
                row.setWorkItemId(itemId);
            }
            row.setText(text);
            row.setKind(normaliseKind(in.kind()));
            row.setExternalRef(ref);
            row.setPosition(i);
            result.add(row);
        }
        // Whatever was not matched is gone from the new list.
        criterionRepository.deleteAll(new ArrayList<>(available.values()));
        return criterionRepository.saveAll(result);
    }

    /**
     * Changes one criterion; every argument is optional and null leaves it alone. {@code met=true} stamps
     * {@code metAt} and {@code metByLabel} from {@code actor}; {@code met=false} clears both.
     */
    @Transactional
    public PatchResult patch(String projectId, String itemId, String criterionId, String text, String kind,
                             Boolean met, String evidence, ProjectActor actor) {
        requireItem(projectId, itemId);
        WorkItemCriterion criterion = criterionRepository.findById(criterionId)
                .filter(c -> itemId.equals(c.getWorkItemId()))
                .orElseThrow(() -> new EntityNotFoundException("Criterion not found"));
        if (text != null) {
            if (text.isBlank()) {
                throw new BusinessException("text cannot be empty");
            }
            criterion.setText(text.strip());
        }
        if (kind != null) {
            criterion.setKind(normaliseKind(kind));
        }
        if (evidence != null) {
            criterion.setEvidence(evidence.isBlank() ? null : evidence);
        }
        if (met != null) {
            if (met && !criterion.isMet()) {
                criterion.setMet(true);
                criterion.setMetAt(OffsetDateTime.now(clock));
                criterion.setMetByLabel(actor == null ? null : actor.displayName());
            } else if (!met) {
                criterion.setMet(false);
                criterion.setMetAt(null);
                criterion.setMetByLabel(null);
            }
        }
        criterionRepository.save(criterion);
        List<WorkItemCriterion> all = criterionRepository.findByWorkItemIdOrderByPositionAscCreatedAtAsc(itemId);
        int metCount = (int) all.stream().filter(WorkItemCriterion::isMet).count();
        return new PatchResult(criterion, metCount, all.size());
    }

    private void requireItem(String projectId, String itemId) {
        boolean found = workItemRepository.findById(itemId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .isPresent();
        if (!found) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }

    private static String normaliseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return WorkItemCriterion.KIND_AUTO;
        }
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if (!WorkItemCriterion.KIND_AUTO.equals(k) && !WorkItemCriterion.KIND_MANUAL.equals(k)) {
            throw new BusinessException("kind must be 'auto' or 'manual', got '" + kind + "'");
        }
        return k;
    }

    private static String matchKey(String externalRef, String text) {
        return externalRef != null ? "ref:" + externalRef : "text:" + text.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
