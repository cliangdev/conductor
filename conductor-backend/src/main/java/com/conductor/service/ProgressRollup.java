package com.conductor.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolls a Work Item subtree up into one progress figure: how many of its leaf items are done.
 *
 * <p>Pure: the caller resolves each item's workflow and says whether its status is terminal in its own
 * statechart ({@link Node#terminal}), so this class needs neither Spring nor a database. Only leaves
 * count -- an epic is its children, never a unit of work of its own -- and the root is never counted
 * unless it has no children at all, in which case there is nothing to roll up and the result is empty.
 */
public final class ProgressRollup {

    /** One item of the subtree: its id, its parent's id (null for the root) and whether it is finished. */
    public record Node(String id, String parentId, boolean terminal) {
    }

    /** Met and total acceptance criteria on one item. */
    public record CriteriaCount(int met, int total) {
    }

    /**
     * @param total         leaf items under the root
     * @param done          leaves in a terminal status
     * @param percent       {@code done / total} as a whole percentage, 0 when there are no leaves
     * @param criteriaTotal criteria across the leaves
     * @param criteriaMet   criteria met across the leaves
     */
    public record Progress(int total, int done, int percent, int criteriaTotal, int criteriaMet) {
        public static final Progress EMPTY = new Progress(0, 0, 0, 0, 0);
    }

    private ProgressRollup() {
    }

    /**
     * @param rootId         the item the figure is for
     * @param subtree        every item under (and optionally including) the root
     * @param criteriaCounts criteria counts keyed by item id; items absent from it have none
     */
    public static Progress of(String rootId, List<Node> subtree, Map<String, CriteriaCount> criteriaCounts) {
        Set<String> hasChildren = new HashSet<>();
        for (Node node : subtree) {
            if (node.parentId() != null) {
                hasChildren.add(node.parentId());
            }
        }
        int total = 0;
        int done = 0;
        int criteriaTotal = 0;
        int criteriaMet = 0;
        for (Node node : subtree) {
            if (node.id().equals(rootId) || hasChildren.contains(node.id())) {
                continue;
            }
            total++;
            if (node.terminal()) {
                done++;
            }
            CriteriaCount criteria = criteriaCounts.get(node.id());
            if (criteria != null) {
                criteriaTotal += criteria.total();
                criteriaMet += criteria.met();
            }
        }
        int percent = total == 0 ? 0 : Math.round(done * 100f / total);
        return new Progress(total, done, percent, criteriaTotal, criteriaMet);
    }
}
