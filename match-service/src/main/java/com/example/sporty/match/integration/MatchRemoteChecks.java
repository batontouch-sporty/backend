package com.example.sporty.match.integration;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** Match-side policies shared by create/modify/join. No other service's DB access. */
@Service
public class MatchRemoteChecks {
    private static final Logger log = LoggerFactory.getLogger(MatchRemoteChecks.class);
    private final MemberClient members;
    private final FacilityClient facilities;

    public MatchRemoteChecks(MemberClient members, FacilityClient facilities) {
        this.members = members;
        this.facilities = facilities;
    }

    public record FacilitySnapshot(
            Long facilityId,
            String facilityName,
            String locationName,
            String region,
            Boolean isFree,
            String url) {
        public com.example.sporty.match.domain.MatchEntity.FacilitySnapshot toEntitySnapshot() {
            return new com.example.sporty.match.domain.MatchEntity.FacilitySnapshot(
                    facilityId, facilityName, locationName, region, isFree, url);
        }
    }

    public record CreationContext(MemberClient.Member member, FacilitySnapshot facility) {}

    public record ProfileView(Long userId, Long profileId, String nickname, String imageUrl) {}

    /** A9 maps non-active members to its legacy creation error; A14 keeps USER_NOT_ACTIVE. */
    public CreationContext checkCreation(
            Long userId, Long facilityId, LocalDateTime start, LocalDateTime end) {
        validatePeriod(start, end);
        MemberClient.Member member;
        try {
            member = activeMember(userId);
        } catch (MatchDependencyException ex) {
            if ("USER_NOT_ACTIVE".equals(ex.code()))
                throw problem(401, "USER_WITHDRAWN", "탈퇴 또는 탈퇴 처리 중인 회원입니다.");
            throw ex;
        }
        return new CreationContext(member, checkFacility(facilityId, start, end));
    }

    /** A14 fails closed when user-service is unavailable or membership is inactive. */
    public MemberClient.Member checkParticipant(Long userId, String genderGroup) {
        if (!Set.of("MALE", "FEMALE", "MIXED")
                .contains(Objects.requireNonNullElse(genderGroup, "")))
            throw problem(400, "INVALID_INPUT", "성별 구성을 확인해주세요.");
        var member = activeMember(userId);
        if (!"MIXED".equals(genderGroup) && !genderGroup.equals(member.gender()))
            throw problem(400, "MATCH_GENDER_NOT_ALLOWED", "성별 조건이 맞지 않습니다.");
        return member;
    }

    public MemberClient.Member activeMember(Long userId) {
        validId(userId);
        MemberClient.Member member;
        try {
            member = members.get(userId);
        } catch (RuntimeException ex) {
            throw dependencyFailure(ex, true);
        }
        if (member == null
                || !userId.equals(member.id())
                || !Set.of("ACTIVE", "WITHDRAWING", "WITHDRAWN")
                        .contains(Objects.requireNonNullElse(member.status(), ""))
                || !Set.of("MALE", "FEMALE")
                        .contains(Objects.requireNonNullElse(member.gender(), "")))
            throw unavailable(true);
        if (!"ACTIVE".equals(member.status()))
            throw problem(400, "USER_NOT_ACTIVE", "활동 가능한 회원이 아닙니다.");
        return member;
    }

    /** A9/A12 return a validated snapshot for the caller's own match_db transaction. */
    public FacilitySnapshot checkFacility(Long facilityId, LocalDateTime start, LocalDateTime end) {
        validId(facilityId);
        validatePeriod(start, end);
        FacilityClient.Facility facility;
        try {
            facility = facilities.get(facilityId);
        } catch (RuntimeException ex) {
            throw dependencyFailure(ex, false);
        }
        if (facility == null
                || !facilityId.equals(facility.id())
                || facility.active() == null
                || blank(facility.name())
                || blank(facility.locationName())
                || blank(facility.region())) throw unavailable(false);
        if (!facility.active()) throw problem(400, "FACILITY_INACTIVE", "비활성 시설입니다.");
        validateHours(start, end, facility.startTime(), facility.endTime());
        return new FacilitySnapshot(
                facility.id(),
                facility.name(),
                facility.locationName(),
                facility.region(),
                facility.isFree(),
                facility.url());
    }

    /** Profile failure is display-only: every requested ID gets a safe fallback. */
    public Map<Long, ProfileView> profiles(List<Long> userIds) {
        if (userIds == null) throw problem(400, "INVALID_INPUT", "회원 목록을 확인해주세요.");
        var ids = userIds.stream().peek(MatchRemoteChecks::validId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, ProfileView> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, new ProfileView(id, null, "알 수 없음", null)));
        try {
            var profiles = members.profiles(ids);
            if (profiles != null)
                for (var row : profiles)
                    if (row != null && result.containsKey(row.id()))
                        result.put(
                                row.id(),
                                new ProfileView(
                                        row.id(),
                                        row.profileId(),
                                        blank(row.nickname()) ? "알 수 없음" : row.nickname(),
                                        row.imageUrl()));
        } catch (RuntimeException ex) {
            log.warn("회원 프로필 조회 실패로 대체 정보를 표시합니다. requestedCount={}", ids.size(), ex);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Overnight windows belong to their opening date; unknown/equal hours are not assumed 24h. */
    private static void validateHours(
            LocalDateTime start, LocalDateTime end, LocalTime open, LocalTime close) {
        if (open == null || close == null || open.equals(close))
            throw problem(400, "FACILITY_HOURS_MISMATCH", "시설 운영시간 확인이 필요합니다.");
        var windowStart = start.toLocalDate().atTime(open);
        var windowEnd = start.toLocalDate().atTime(close);
        if (close.isBefore(open)) {
            if (start.toLocalTime().isBefore(close)) windowStart = windowStart.minusDays(1);
            else windowEnd = windowEnd.plusDays(1);
        }
        if (start.isBefore(windowStart) || end.isAfter(windowEnd))
            throw problem(400, "FACILITY_HOURS_MISMATCH", "시설 운영시간 밖입니다.");
    }

    private static void validatePeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start))
            throw problem(400, "INVALID_INPUT", "일정을 확인해주세요.");
    }

    private static void validId(Long id) {
        if (id == null || id <= 0) throw problem(400, "INVALID_INPUT", "ID를 확인해주세요.");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Unwrap Feign's circuit-breaker exceptions; only confirmed 404 is an absent entity. */
    private static MatchDependencyException dependencyFailure(
            RuntimeException exception, boolean user) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof FeignException response && response.status() == 404)
                return problem(
                        user ? 401 : 404,
                        user ? "MATCH_USER_NOT_FOUND" : "SERVICE_NOT_FOUND",
                        "대상을 찾을 수 없습니다.");
            if (cause.getCause() == cause) break;
        }
        return unavailable(user);
    }

    private static MatchDependencyException unavailable(boolean user) {
        return problem(
                503,
                user ? "USER_SERVICE_UNAVAILABLE" : "FACILITY_SERVICE_UNAVAILABLE",
                user ? "회원 확인 불가, 잠시 후 다시" : "시설 확인 불가, 잠시 후 다시");
    }

    private static MatchDependencyException problem(int status, String code, String message) {
        return new MatchDependencyException(status, code, message);
    }
}
