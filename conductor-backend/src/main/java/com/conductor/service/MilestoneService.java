package com.conductor.service;

import com.conductor.entity.Milestone;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.MilestoneRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Milestones: create, edit, delete and how far each one has got. Access is checked by the controller
 * (via {@code ProjectSecurityService}); this service trusts the project id it is handed.
 */
@Service
public class MilestoneService {

    private static final int MAX_NAME = 120;

    private final MilestoneRepository milestoneRepository;
    private final ProjectRepository projectRepository;
    private final WorkItemRepository workItemRepository;
    private final WorkItemHierarchyService hierarchyService;

    public MilestoneService(MilestoneRepository milestoneRepository, ProjectRepository projectRepository,
                            WorkItemRepository workItemRepository, WorkItemHierarchyService hierarchyService) {
        this.milestoneRepository = milestoneRepository;
        this.projectRepository = projectRepository;
        this.workItemRepository = workItemRepository;
        this.hierarchyService = hierarchyService;
    }

    /**
     * How far a milestone has got, counted two ways.
     *
     * @param topLevelTotal items under it with no parent (the PRDs)
     * @param topLevelDone  of those, the ones in a terminal status
     * @param leafTotal     items under it with no children (the tasks, or a childless PRD)
     * @param leafDone      of those, the ones in a terminal status
     */
    public record MilestoneProgress(int topLevelTotal, int topLevelDone, int leafTotal, int leafDone) {
        public static final MilestoneProgress EMPTY = new MilestoneProgress(0, 0, 0, 0);
    }

    /** A milestone with its progress, for list and detail responses. */
    public record MilestoneView(Milestone milestone, MilestoneProgress progress) {
    }

    /** A project's milestones in display order, each with its progress, in two batched reads. */
    @Transactional(readOnly = true)
    public List<MilestoneView> list(String projectId) {
        List<Milestone> milestones = milestoneRepository.findAllByProjectIdOrderBySortOrderAscTargetOnAscNameAsc(projectId);
        Map<String, MilestoneProgress> progress = progressFor(projectId, milestones);
        return milestones.stream()
                .map(m -> new MilestoneView(m, progress.getOrDefault(m.getId(), MilestoneProgress.EMPTY)))
                .toList();
    }

    @Transactional(readOnly = true)
    public MilestoneView get(String projectId, String milestoneId) {
        Milestone milestone = find(projectId, milestoneId);
        return new MilestoneView(milestone,
                progressFor(projectId, List.of(milestone)).getOrDefault(milestoneId, MilestoneProgress.EMPTY));
    }

    @Transactional
    public MilestoneView create(String projectId, String name, String description, LocalDate targetOn,
                                String state, Integer sortOrder) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
        String cleanName = requireName(name);
        if (milestoneRepository.existsByProjectIdAndNameIgnoreCase(projectId, cleanName)) {
            throw new ConflictException("A milestone named '" + cleanName + "' already exists");
        }
        Milestone milestone = new Milestone();
        milestone.setProject(project);
        milestone.setName(cleanName);
        milestone.setDescription(description);
        milestone.setTargetOn(targetOn);
        milestone.setState(validState(state));
        if (sortOrder != null) {
            milestone.setSortOrder(sortOrder);
        }
        milestoneRepository.save(milestone);
        return new MilestoneView(milestone, MilestoneProgress.EMPTY);
    }

    /**
     * PATCH semantics: null leaves a field alone. {@code description} and {@code targetOn} are clearable with
     * an empty string (a string rather than a date for the same reason {@code dueOn} is: the generated
     * models cannot tell an omitted field from an explicit null).
     */
    @Transactional
    public MilestoneView update(String projectId, String milestoneId, String name, String description,
                                String targetOn, String state, Integer sortOrder) {
        Milestone milestone = find(projectId, milestoneId);
        if (name != null) {
            String cleanName = requireName(name);
            if (!cleanName.equalsIgnoreCase(milestone.getName())
                    && milestoneRepository.existsByProjectIdAndNameIgnoreCase(projectId, cleanName)) {
                throw new ConflictException("A milestone named '" + cleanName + "' already exists");
            }
            milestone.setName(cleanName);
        }
        if (description != null) {
            milestone.setDescription(description.isBlank() ? null : description);
        }
        if (targetOn != null) {
            milestone.setTargetOn(parseDate(targetOn));
        }
        if (state != null) {
            milestone.setState(validState(state));
        }
        if (sortOrder != null) {
            milestone.setSortOrder(sortOrder);
        }
        milestoneRepository.save(milestone);
        return get(projectId, milestoneId);
    }

    /** Deletes a milestone; its items are detached, not deleted (the foreign key is {@code ON DELETE SET NULL}). */
    @Transactional
    public void delete(String projectId, String milestoneId) {
        milestoneRepository.delete(find(projectId, milestoneId));
    }

    /** The milestone an item is being pointed at: it must exist in this project. */
    @Transactional(readOnly = true)
    public Milestone resolve(String projectId, String milestoneId) {
        return milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(
                        "Milestone " + milestoneId + " was not found in this project"));
    }

    private Milestone find(String projectId, String milestoneId) {
        return milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Milestone not found"));
    }

    private Map<String, MilestoneProgress> progressFor(String projectId, Collection<Milestone> milestones) {
        if (milestones.isEmpty()) {
            return Map.of();
        }
        List<String> milestoneIds = milestones.stream().map(Milestone::getId).toList();
        List<WorkItem> items = workItemRepository.findByMilestoneIds(milestoneIds);
        Set<String> terminal = hierarchyService.terminalIds(projectId, items);
        Set<String> parents = hierarchyService.childCounts(items.stream().map(WorkItem::getId).toList()).keySet();

        Map<String, List<WorkItem>> byMilestone = items.stream()
                .collect(Collectors.groupingBy(i -> i.getMilestone().getId()));
        Map<String, MilestoneProgress> result = new HashMap<>();
        byMilestone.forEach((id, group) -> {
            int topTotal = 0;
            int topDone = 0;
            int leafTotal = 0;
            int leafDone = 0;
            for (WorkItem item : group) {
                boolean done = terminal.contains(item.getId());
                if (item.getParentWorkItemId() == null) {
                    topTotal++;
                    if (done) {
                        topDone++;
                    }
                }
                if (!parents.contains(item.getId())) {
                    leafTotal++;
                    if (done) {
                        leafDone++;
                    }
                }
            }
            result.put(id, new MilestoneProgress(topTotal, topDone, leafTotal, leafDone));
        });
        return result;
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new BusinessException("milestone name must be 1 to " + MAX_NAME + " characters");
        }
        return trimmed;
    }

    private static String validState(String state) {
        if (state == null || state.isBlank()) {
            return Milestone.STATE_OPEN;
        }
        if (!Milestone.STATE_OPEN.equals(state) && !Milestone.STATE_CLOSED.equals(state)) {
            throw new BusinessException("milestone state must be OPEN or CLOSED");
        }
        return state;
    }

    private static LocalDate parseDate(String targetOn) {
        if (targetOn.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(targetOn.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("targetOn must be a date (yyyy-MM-dd), or empty to clear it");
        }
    }
}
