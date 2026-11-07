package com.conductor.marketing.growth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** A keyword whose App Store search position is recorded weekly for every watched app. See V195. */
@Entity
@Table(name = "marketing_tracked_keyword")
public class MarketingTrackedKeyword {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "keyword", length = 100, nullable = false)
    private String keyword;

    @Column(name = "country", length = 2, nullable = false)
    private String country = "us";

    @PrePersist
    void assign() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
}
