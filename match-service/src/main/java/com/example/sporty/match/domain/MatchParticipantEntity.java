package com.example.sporty.match.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** Match-owned participation. userId is a value, not a cross-database JPA relationship. */
@Entity
@Table(name = "match_participants",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_participants_match_user",
                columnNames = {"match_id", "user_id"}))
public class MatchParticipantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_participant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_match_participants_match"))
    private MatchEntity match;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", length = 20, nullable = false)
    private MatchParticipantRole role;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP(6)", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP(6)", nullable = false)
    private Instant updatedAt;

    protected MatchParticipantEntity() {}

    public MatchParticipantEntity(MatchEntity match, long userId, MatchParticipantRole role) {
        this.match = Objects.requireNonNull(match);
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        this.userId = userId;
        this.role = Objects.requireNonNull(role);
    }

    public Long getId() { return id; }
    public MatchEntity getMatch() { return match; }
    public Long getUserId() { return userId; }
    public MatchParticipantRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
