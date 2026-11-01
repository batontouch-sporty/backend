package com.example.sporty.match.crud;

import com.example.sporty.match.domain.*;

import jakarta.validation.constraints.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

/** External names follow A9–A13/A16; serviceId is the facility service's internal PK. */
public final class MatchDtos {
    private MatchDtos() {}

    public record Create(
            @NotNull @Positive Long serviceId,
            @NotBlank @Size(max = 50) String title,
            @Size(max = 500) String description,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            @NotNull @Min(1) Integer maxParticipant,
            @NotNull SkillLevel skillLevel,
            @NotNull SportType sportType,
            @NotNull GenderGroup genderGroup) {
        public MatchEntity.Details details() {
            return new MatchEntity.Details(
                    title,
                    description,
                    startAt,
                    endAt,
                    maxParticipant,
                    skillLevel,
                    sportType,
                    genderGroup);
        }
    }

    public record Update(
            @NotBlank @Size(max = 50) String title,
            @Size(max = 500) String description,
            @NotNull LocalDateTime startAt,
            @NotNull LocalDateTime endAt,
            @NotNull @Min(1) Integer maxParticipant,
            @NotNull SkillLevel skillLevel,
            @NotNull GenderGroup genderGroup) {
        public MatchEntity.Details details(SportType sport) {
            return new MatchEntity.Details(
                    title,
                    description,
                    startAt,
                    endAt,
                    maxParticipant,
                    skillLevel,
                    sport,
                    genderGroup);
        }
    }

    public record Search(
            @Positive Long serviceId,
            @Size(max = 50) String titleKeyword,
            @Size(max = 500) String descriptionKeyword,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @Min(1) Integer maxParticipant,
            SkillLevel skillLevel,
            SportType sportType,
            GenderGroup genderGroup,
            @Size(max = 20) String region,
            MatchStatus status,
            Boolean isFree) {}

    public record Summary(
            Long matchId,
            String title,
            String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            long numCurrentParticipant,
            int maxParticipant,
            Double distance,
            MatchStatus status,
            SkillLevel skillLevel,
            SportType sportType,
            GenderGroup genderGroup,
            String region,
            Boolean isFree) {}

    public record Participant(
            Long profileId, String nickname, String imageUrl, MatchParticipantRole role) {}

    public record Detail(
            Long matchId,
            String title,
            String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int maxParticipant,
            long currentParticipantCount,
            MatchStatus status,
            SkillLevel skillLevel,
            SportType sportType,
            GenderGroup genderGroup,
            Long serviceId,
            String serviceName,
            String locationName,
            String region,
            List<Participant> participants,
            boolean isOwner,
            boolean isParticipant) {}

    public record Mine(
            Long matchId,
            String title,
            LocalDateTime startAt,
            LocalDateTime endAt,
            long numCurrentParticipant,
            int maxParticipant,
            MatchStatus status,
            SportType sportType,
            String region,
            String facilityName,
            Boolean isFree,
            MatchParticipantRole role) {}
}
