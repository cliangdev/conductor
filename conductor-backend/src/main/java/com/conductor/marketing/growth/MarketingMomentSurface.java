package com.conductor.marketing.growth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** One place a moment needs a change, its due date and the Work Item that recorded the change. See V195. */
@Entity
@Table(name = "marketing_moment_surface")
public class MarketingMomentSurface {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "moment_id", length = 36, nullable = false, updatable = false)
    private String momentId;

    @Column(name = "surface", length = 120, nullable = false)
    private String surface;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    /** The MARKETING_CHANGES Work Item that recorded the change; null while none is linked. */
    @Column(name = "change_work_item_id", length = 36)
    private String changeWorkItemId;

    @PrePersist
    void assign() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMomentId() { return momentId; }
    public void setMomentId(String momentId) { this.momentId = momentId; }
    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }
    public LocalDate getDueOn() { return dueOn; }
    public void setDueOn(LocalDate dueOn) { this.dueOn = dueOn; }
    public String getChangeWorkItemId() { return changeWorkItemId; }
    public void setChangeWorkItemId(String changeWorkItemId) { this.changeWorkItemId = changeWorkItemId; }
}
