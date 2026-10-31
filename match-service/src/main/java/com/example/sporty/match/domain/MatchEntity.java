package com.example.sporty.match.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Match data owned by match_db. Facility data is a snapshot, never a cross-service JPA relation.
 * The DBA-owned R7 DDL plus the matches.url change is the schema source of truth.
 */
@Entity
@Table(name = "matches")
public class MatchEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", length = 50, nullable = false)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "start_at", columnDefinition = "DATETIME(6)", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", columnDefinition = "DATETIME(6)", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "max_participant", nullable = false)
    private int maxParticipant;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 20, nullable = false)
    private MatchStatus status = MatchStatus.RECRUITING;

    @Column(name = "status_changed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime statusChangedAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "cancelled_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "skill_level", length = 30, nullable = false)
    private SkillLevel skillLevel = SkillLevel.BEGINNER;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sport_type", length = 15, nullable = false)
    private SportType sportType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "gender_group", length = 10, nullable = false)
    private GenderGroup genderGroup;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "facility_name", length = 200, nullable = false)
    private String facilityName;

    @Column(name = "location_name", length = 100, nullable = false)
    private String locationName;

    @Column(name = "region", length = 20, nullable = false)
    private String region;

    @Column(name = "is_free")
    private Boolean isFree;

    @Column(name = "url", length = 200)
    private String url;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP(6)", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP(6)", nullable = false)
    private Instant updatedAt;

    protected MatchEntity() {}

    public MatchEntity(Details details, FacilitySnapshot facility) {
        updateDetails(details);
        copyFacility(facility);
    }

    public void update(Details details, FacilitySnapshot facility) {
        updateDetails(details);
        copyFacility(facility);
    }

    /** Close recruitment only while the match is still in a recruitment state. */
    public void closeRecruitment(LocalDateTime changedAt) {
        requireOpenForRecruitmentChange();
        changeStatus(MatchStatus.CLOSED, changedAt);
    }

    /** Reopen recruitment only while the match is still in a recruitment state. */
    public void reopenRecruitment(LocalDateTime changedAt) {
        requireOpenForRecruitmentChange();
        changeStatus(MatchStatus.RECRUITING, changedAt);
    }

    /** Keep terminal states terminal and require cancellation metadata for CANCELLED. */
    public void changeStatus(MatchStatus next, LocalDateTime changedAt) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(changedAt);
        if (status == MatchStatus.CANCELLED || status == MatchStatus.COMPLETED) {
            throw new IllegalStateException("Cannot change status from " + status);
        }
        if (next == MatchStatus.CANCELLED && cancelledAt == null) {
            throw new IllegalStateException("Use cancel() to record cancelledAt");
        }
        status = next;
        statusChangedAt = changedAt;
    }

    /** Record the reason and time once, after checking that cancellation is still allowed. */
    public void cancel(String reason, LocalDateTime changedAt) {
        Objects.requireNonNull(changedAt);
        if (status == MatchStatus.CANCELLED || status == MatchStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel match in status " + status);
        }
        cancelReason = reason;
        cancelledAt = changedAt;
        changeStatus(MatchStatus.CANCELLED, changedAt);
    }

    private void requireOpenForRecruitmentChange() {
        if (status != MatchStatus.RECRUITING && status != MatchStatus.CLOSED) {
            throw new IllegalStateException("Cannot change recruitment in status " + status);
        }
    }

    /** Enforce the same title, capacity and time invariants as the match_db DDL. */
    private void updateDetails(Details details) {
        Objects.requireNonNull(details);
        if (details.title() == null || details.title().isBlank()
                || details.maxParticipant() < 1
                || details.startAt() == null || details.endAt() == null
                || !details.endAt().isAfter(details.startAt())) {
            throw new IllegalArgumentException("Invalid match title, capacity or time range");
        }
        title = Objects.requireNonNull(details.title());
        description = details.description();
        startAt = Objects.requireNonNull(details.startAt());
        endAt = Objects.requireNonNull(details.endAt());
        maxParticipant = details.maxParticipant();
        skillLevel = Objects.requireNonNull(details.skillLevel());
        sportType = Objects.requireNonNull(details.sportType());
        genderGroup = Objects.requireNonNull(details.genderGroup());
    }

    /** Copy facility values without introducing a cross-service JPA relation. */
    private void copyFacility(FacilitySnapshot facility) {
        Objects.requireNonNull(facility);
        if (facility.id() <= 0) throw new IllegalArgumentException("facilityId must be positive");
        facilityId = facility.id();
        facilityName = Objects.requireNonNull(facility.facilityName());
        locationName = Objects.requireNonNull(facility.locationName());
        region = Objects.requireNonNull(facility.region());
        isFree = facility.isFree();
        url = facility.url();
    }

    public record Details(String title, String description, LocalDateTime startAt,
                          LocalDateTime endAt, int maxParticipant, SkillLevel skillLevel,
                          SportType sportType, GenderGroup genderGroup) {}

    public record FacilitySnapshot(long id, String facilityName, String locationName,
                                   String region, Boolean isFree, String url) {}

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public int getMaxParticipant() { return maxParticipant; }
    public MatchStatus getStatus() { return status; }
    public LocalDateTime getStatusChangedAt() { return statusChangedAt; }
    public String getCancelReason() { return cancelReason; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public SkillLevel getSkillLevel() { return skillLevel; }
    public SportType getSportType() { return sportType; }
    public GenderGroup getGenderGroup() { return genderGroup; }
    public Long getFacilityId() { return facilityId; }
    public String getFacilityName() { return facilityName; }
    public String getLocationName() { return locationName; }
    public String getRegion() { return region; }
    public Boolean getIsFree() { return isFree; }
    public String getUrl() { return url; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
