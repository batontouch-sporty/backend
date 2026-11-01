package com.example.sporty.match.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;

/**
 * A pending user. Promotion removes this row and creates a participation within one
 * match_db transaction; cancelled matches keep their historical waitlist rows.
 */
@Entity
@Table(name = "match_waitlist",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_waitlist_match_user",
                columnNames = {"match_id", "user_id"}))
public class MatchWaitlistEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_match_waitlist_match"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MatchEntity match;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "registered_at", columnDefinition = "TIMESTAMP(6)", nullable = false, updatable = false)
    private Instant registeredAt;

    protected MatchWaitlistEntity() {}

    public MatchWaitlistEntity(MatchEntity match, long userId) {
        this.match = Objects.requireNonNull(match);
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        this.userId = userId;
    }

    public Long getId() { return id; }
    public MatchEntity getMatch() { return match; }
    public Long getUserId() { return userId; }
    public Instant getRegisteredAt() { return registeredAt; }
}
