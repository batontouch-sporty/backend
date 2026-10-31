package com.example.sporty.user.sportpreference.domain.entity;

import com.example.sporty.user.commons.util.SportType;
import com.example.sporty.user.profiles.domain.entity.ProfileEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Entity
@Builder
@Table(name = "sport_preference", uniqueConstraints =
    @UniqueConstraint(name = "uk_sport_preference_profile_sport", columnNames = {"profile_id", "sport_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SportPreferenceEntity {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "sport_type", nullable = false, length = 15)
    private SportType sportType;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false,
            columnDefinition = "TIMESTAMP"
    )
    private Date createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false,
            columnDefinition = "TIMESTAMP"
    )
    private Date updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProfileEntity profile;

}
