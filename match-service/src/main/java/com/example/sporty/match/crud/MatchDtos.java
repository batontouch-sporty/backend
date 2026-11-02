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
            @NotNull(message = "필수입니다.") @Positive(message = "1 이상이어야 합니다.") Long serviceId,
            @NotBlank(message = "필수입니다.") @Size(max = 50, message = "50자 이하여야 합니다.") String title,
            @Size(max = 500, message = "500자 이하여야 합니다.") String description,
            @NotNull(message = "필수입니다.") LocalDateTime startAt,
            @NotNull(message = "필수입니다.") LocalDateTime endAt,
            @NotNull(message = "필수입니다.") @Min(value = 2, message = "2 이상이어야 합니다.") Integer maxParticipant,
            @NotNull(message = "필수입니다.") SkillLevel skillLevel,
            @NotNull(message = "필수입니다.") SportType sportType,
            @NotNull(message = "필수입니다.") GenderGroup genderGroup) {
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
            @NotBlank(message = "필수입니다.") @Size(max = 50, message = "50자 이하여야 합니다.") String title,
            @Size(max = 500, message = "500자 이하여야 합니다.") String description,
            @NotNull(message = "필수입니다.") LocalDateTime startAt,
            @NotNull(message = "필수입니다.") LocalDateTime endAt,
            @NotNull(message = "필수입니다.") @Min(value = 2, message = "2 이상이어야 합니다.") Integer maxParticipant,
            @NotNull(message = "필수입니다.") SkillLevel skillLevel,
            @NotNull(message = "필수입니다.") GenderGroup genderGroup) {
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
            @Positive(message = "1 이상이어야 합니다.") Long serviceId,
            @Size(max = 50, message = "50자 이하여야 합니다.") String titleKeyword,
            @Size(max = 500, message = "500자 이하여야 합니다.") String descriptionKeyword,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @Min(value = 1, message = "1 이상이어야 합니다.") Integer maxParticipant,
            SkillLevel skillLevel,
            SportType sportType,
            GenderGroup genderGroup,
            @Size(max = 20, message = "20자 이하여야 합니다.") String region,
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
