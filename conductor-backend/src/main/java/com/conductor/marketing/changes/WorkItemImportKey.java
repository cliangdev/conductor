package com.conductor.marketing.changes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Which Work Item an outside record became: one row per (workspace, source, record id). Importing the same
 * log again finds the row and creates nothing. Rows are written by
 * {@link WorkItemImportKeyRepository#insertIfAbsent} and leave with their Work Item (ON DELETE CASCADE). See V187.
 */
@Entity
@Table(name = "work_item_import_key")
@IdClass(WorkItemImportKey.Pk.class)
public class WorkItemImportKey {

    @Id
    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Id
    @Column(name = "source", length = 64, nullable = false, updatable = false)
    private String source;

    /** The record's id in the source log (column {@code key}). */
    @Id
    @Column(name = "key", length = 190, nullable = false, updatable = false)
    private String recordId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WorkItemImportKey() {
    }

    public String getProjectId() { return projectId; }
    public String getSource() { return source; }
    public String getRecordId() { return recordId; }
    public String getWorkItemId() { return workItemId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** The composite identity. */
    public static class Pk implements Serializable {

        private String projectId;
        private String source;
        private String recordId;

        protected Pk() {
        }

        public Pk(String projectId, String source, String recordId) {
            this.projectId = projectId;
            this.source = source;
            this.recordId = recordId;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof Pk pk && Objects.equals(projectId, pk.projectId)
                    && Objects.equals(source, pk.source) && Objects.equals(recordId, pk.recordId));
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, source, recordId);
        }
    }
}
