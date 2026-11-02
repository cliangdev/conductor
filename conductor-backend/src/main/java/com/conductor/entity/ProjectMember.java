package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
    name = "project_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"})
)
public class ProjectMember {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "member_role")
    @ColumnTransformer(write = "?::member_role")
    private MemberRole role;

    /** The role set, stored as {@code varchar[]}; read and written through {@link MemberRoles}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", nullable = false, columnDefinition = "varchar(16)[]")
    private String[] roles = new String[0];

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        joinedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    /** The primary role (legacy column): ADMIN if held, else CREATOR, else REVIEWER. */
    public MemberRole getRole() { return role; }

    /** Convenience for a member holding exactly one role. */
    public void setRole(MemberRole role) { setRoles(role == null ? Set.of() : Set.of(role)); }

    /** The stored roles (not the effective ones: ADMIN is not expanded here). */
    public Set<MemberRole> getRoles() { return MemberRoles.fromStored(roles); }

    /** Replaces the role set and keeps the legacy {@code role} column on the primary role. */
    public void setRoles(Set<MemberRole> newRoles) {
        this.roles = MemberRoles.toStored(newRoles);
        this.role = MemberRoles.primary(newRoles);
    }

    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime joinedAt) { this.joinedAt = joinedAt; }
}
