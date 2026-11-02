package com.example.sporty.match.crud;

import com.example.sporty.match.domain.*;
import com.example.sporty.match.integration.MatchFacilityIntegration;
import com.example.sporty.match.integration.MatchRemoteChecks;
import com.example.sporty.match.participation.ParticipationEvents;
import com.example.sporty.match.repository.*;
import com.example.sporty.match.withdrawal.MemberLocks;

import jakarta.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Match CRUD using the shared R8 model, participant locks and Outbox. */
@Service
@Transactional(readOnly = true)
public class MatchCrudService {
    private static final int MAX_SEARCH_RESULTS = 500;
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchFacilityIntegration facility;
    private final MatchRemoteChecks checks;
    private final MemberLocks locks;
    private final ParticipationEvents events;
    private final TransactionTemplate transactions;

    public MatchCrudService(
            MatchRepository matches,
            MatchParticipantRepository participants,
            MatchFacilityIntegration facility,
            MatchRemoteChecks checks,
            @Qualifier("participationMemberLocks") MemberLocks locks,
            ParticipationEvents events,
            PlatformTransactionManager transactionManager) {
        this.matches = matches;
        this.participants = participants;
        this.facility = facility;
        this.checks = checks;
        this.locks = locks;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Long create(Long userId, MatchDtos.Create request) {
        validId(userId);
        validCapacity(request.maxParticipant());
        validPeriod(request.startAt(), request.endAt());
        locks.lock(userId);
        var match = facility.create(userId, request.details(), request.serviceId());
        // Remote checks can take time; never commit a newly created match already in the past.
        validPeriod(request.startAt(), request.endAt());
        matches.saveAndFlush(match);
        participants.saveAndFlush(
                new MatchParticipantEntity(match, userId, MatchParticipantRole.OWNER));
        return match.getId();
    }

    public List<MatchDtos.Summary> search(MatchDtos.Search q) {
        if (q.startAt() != null && q.endAt() != null && q.endAt().isBefore(q.startAt()))
            throw problem(400, "INVALID_INPUT", "검색 종료 시각은 시작 시각보다 빠를 수 없습니다.");
        var rows =
                matches.findAll(
                        (root, query, cb) -> {
                            var filters = new ArrayList<Predicate>();
                            if (q.serviceId() != null)
                                filters.add(cb.equal(root.get("facilityId"), q.serviceId()));
                            if (q.titleKeyword() != null)
                                filters.add(
                                        cb.like(
                                                cb.lower(root.get("title")),
                                                like(q.titleKeyword().toLowerCase(Locale.ROOT)),
                                                '!'));
                            if (q.descriptionKeyword() != null)
                                filters.add(
                                        cb.like(
                                                cb.lower(root.get("description")),
                                                like(q.descriptionKeyword().toLowerCase(Locale.ROOT)),
                                                '!'));
                            if (q.startAt() != null)
                                filters.add(
                                        cb.greaterThanOrEqualTo(root.get("startAt"), q.startAt()));
                            if (q.endAt() != null)
                                filters.add(cb.lessThanOrEqualTo(root.get("endAt"), q.endAt()));
                            if (q.maxParticipant() != null)
                                filters.add(cb.le(root.get("maxParticipant"), q.maxParticipant()));
                            if (q.skillLevel() != null)
                                filters.add(cb.equal(root.get("skillLevel"), q.skillLevel()));
                            if (q.sportType() != null)
                                filters.add(cb.equal(root.get("sportType"), q.sportType()));
                            if (q.genderGroup() != null)
                                filters.add(cb.equal(root.get("genderGroup"), q.genderGroup()));
                            if (q.region() != null)
                                filters.add(
                                        cb.like(
                                                cb.lower(root.get("region")),
                                                like(q.region().toLowerCase(Locale.ROOT)),
                                                '!'));
                            if (q.isFree() != null)
                                filters.add(cb.equal(root.get("isFree"), q.isFree()));
                            if (q.status() != null)
                                filters.add(cb.equal(root.get("status"), q.status()));
                            else
                                filters.add(
                                        root.get("status")
                                                .in(
                                                        MatchStatus.RECRUITING,
                                                        MatchStatus.CLOSED,
                                                        MatchStatus.IN_PROGRESS));
                            return cb.and(filters.toArray(Predicate[]::new));
                        },
                        PageRequest.of(
                                0,
                                MAX_SEARCH_RESULTS,
                                Sort.by("startAt").ascending().and(Sort.by("id"))))
                        .getContent();
        var counts = counts(rows.stream().map(MatchEntity::getId).toList());
        return rows.stream()
                .map(
                        m ->
                                new MatchDtos.Summary(
                                        m.getId(),
                                        m.getTitle(),
                                        m.getDescription(),
                                        m.getStartAt(),
                                        m.getEndAt(),
                                        counts.getOrDefault(m.getId(), 0L),
                                        m.getMaxParticipant(),
                                        null,
                                        m.getStatus(),
                                        m.getSkillLevel(),
                                        m.getSportType(),
                                        m.getGenderGroup(),
                                        m.getRegion(),
                                        m.getIsFree()))
                .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MatchDtos.Detail detail(Long matchId, Long viewer) {
        validId(matchId);
        return toDetail(matches.findById(matchId).orElseThrow(MatchCrudService::notFound), viewer);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MatchDtos.Detail update(Long matchId, Long userId, MatchDtos.Update request) {
        validId(matchId);
        validId(userId);
        validCapacity(request.maxParticipant());
        // The facility call may take seconds. Finish it before holding the match row lock.
        var currentMatch = matches.findById(matchId).orElseThrow(MatchCrudService::notFound);
        owner(matchId, userId, "MATCH_MODIFY_FORBIDDEN");
        requireEditable(currentMatch);
        validPeriod(request.startAt(), request.endAt());
        if (request.maxParticipant() < participants.countByMatch_Id(matchId))
            throw problem(400, "MATCH_CAPACITY_BELOW_CURRENT", "정원을 현재 참가 인원보다 줄일 수 없습니다.");
        var facilityId = currentMatch.getFacilityId();
        var snapshot =
                checks.checkFacility(facilityId, request.startAt(), request.endAt())
                        .toEntitySnapshot();
        transactions.executeWithoutResult(
                tx -> {
                    locks.lock(userId);
                    var match = locked(matchId);
                    owner(matchId, userId, "MATCH_MODIFY_FORBIDDEN");
                    if (!facilityId.equals(match.getFacilityId()))
                        throw problem(409, "MATCH_CHANGED", "시설 정보가 변경되었습니다. 다시 시도해주세요.");
                    requireEditable(match);
                    validPeriod(request.startAt(), request.endAt());
                    long current = participants.countByMatch_Id(matchId);
                    if (request.maxParticipant() < current)
                        throw problem(400, "MATCH_CAPACITY_BELOW_CURRENT", "정원을 현재 참가 인원보다 줄일 수 없습니다.");
                    var from = match.getStatus();
                    match.update(request.details(match.getSportType()), snapshot);
                    var next =
                            current >= request.maxParticipant()
                                    ? MatchStatus.CLOSED
                                    : MatchStatus.RECRUITING;
                    if (from != next) {
                        if (next == MatchStatus.CLOSED) match.closeRecruitment(now());
                        else match.reopenRecruitment(now());
                        events.statusChanged(match, from, "정원 변경으로 모집 상태 변경");
                    }
                    matches.flush();
                });
        // Profile I2 is display-only and must not run while holding the row lock.
        return detail(matchId, userId);
    }

    @Transactional
    public void delete(Long matchId, Long userId) {
        validId(matchId);
        validId(userId);
        locks.lock(userId);
        var match = locked(matchId);
        owner(matchId, userId, "MATCH_DELETE_FORBIDDEN");
        if (!Set.of(MatchStatus.RECRUITING, MatchStatus.CLOSED).contains(match.getStatus())
                || !match.getStartAt().isAfter(now()))
            throw problem(400, "MATCH_DELETE_NOT_ALLOWED", "시작 전 모집 중이거나 마감된 매치만 삭제할 수 있습니다.");
        participants.deleteAllByMatch_Id(matchId);
        participants.flush();
        matches.deleteHardById(matchId); // R8 restricts participants and cascades waitlist rows.
    }

    public List<MatchDtos.Mine> mine(Long userId) {
        validId(userId);
        var rows = participants.findAllByUserIdOrderByMatch_StartAtDescMatch_IdDesc(userId);
        var counts = counts(rows.stream().map(p -> p.getMatch().getId()).toList());
        return rows.stream()
                .map(
                        p -> {
                            var m = p.getMatch();
                            return new MatchDtos.Mine(
                                    m.getId(),
                                    m.getTitle(),
                                    m.getStartAt(),
                                    m.getEndAt(),
                                    counts.getOrDefault(m.getId(), 0L),
                                    m.getMaxParticipant(),
                                    m.getStatus(),
                                    m.getSportType(),
                                    m.getRegion(),
                                    m.getFacilityName(),
                                    m.getIsFree(),
                                    p.getRole());
                        })
                .toList();
    }

    private MatchDtos.Detail toDetail(MatchEntity m, Long viewer) {
        var rows = participants.findAllByMatch_IdOrderByIdAsc(m.getId());
        var profiles =
                checks.profiles(rows.stream().map(MatchParticipantEntity::getUserId).toList());
        var people =
                rows.stream()
                        .map(
                                row -> {
                                    var p = profiles.get(row.getUserId());
                                    return new MatchDtos.Participant(
                                            p == null ? null : p.profileId(),
                                            p == null ? "알 수 없음" : p.nickname(),
                                            p == null ? null : p.imageUrl(),
                                            row.getRole());
                                })
                        .toList();
        return new MatchDtos.Detail(
                m.getId(),
                m.getTitle(),
                m.getDescription(),
                m.getStartAt(),
                m.getEndAt(),
                m.getMaxParticipant(),
                rows.size(),
                m.getStatus(),
                m.getSkillLevel(),
                m.getSportType(),
                m.getGenderGroup(),
                m.getFacilityId(),
                m.getFacilityName(),
                m.getLocationName(),
                m.getRegion(),
                people,
                viewer != null
                        && rows.stream()
                                .anyMatch(
                                        p ->
                                                p.getUserId().equals(viewer)
                                                        && p.getRole()
                                                                == MatchParticipantRole.OWNER),
                viewer != null && rows.stream().anyMatch(p -> p.getUserId().equals(viewer)));
    }

    private Map<Long, Long> counts(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return matches.countParticipantsByMatchIds(ids).stream()
                .collect(
                        Collectors.toMap(
                                MatchRepository.ParticipantCount::getMatchId,
                                MatchRepository.ParticipantCount::getParticipantCount));
    }

    private MatchEntity locked(Long id) {
        return matches.findByIdForUpdate(id).orElseThrow(MatchCrudService::notFound);
    }

    private void owner(Long matchId, Long userId, String code) {
        if (participants
                .findByMatch_IdAndUserId(matchId, userId)
                .filter(p -> p.getRole() == MatchParticipantRole.OWNER)
                .isEmpty()) throw problem(403, code, "방장만 처리할 수 있습니다.");
    }

    private static void requireEditable(MatchEntity match) {
        if (match.getStatus() == MatchStatus.CANCELLED)
            throw problem(400, "MATCH_MODIFY_NOT_ALLOWED", "취소된 매치는 수정할 수 없습니다.");
        if (match.getStatus() == MatchStatus.IN_PROGRESS
                || match.getStatus() == MatchStatus.COMPLETED
                || !match.getStartAt().isAfter(now()))
            throw problem(400, "MATCH_ALREADY_STARTED", "이미 시작된 매치는 수정할 수 없습니다.");
    }

    private static String like(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    private static void validPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start) || !start.isAfter(now()))
            throw problem(400, "INVALID_INPUT", "미래의 시작 시각과 시작 이후의 종료 시각을 입력해주세요.");
    }

    private static void validCapacity(Integer capacity) {
        if (capacity == null || capacity < 2)
            throw problem(400, "INVALID_INPUT", "정원은 2명 이상이어야 합니다.");
    }

    private static void validId(Long id) {
        if (id == null || id <= 0) throw problem(400, "INVALID_INPUT", "ID를 확인해주세요.");
    }

    private static MatchCrudProblem notFound() {
        return problem(404, "MATCH_NOT_FOUND", "매치를 찾을 수 없습니다.");
    }

    private static MatchCrudProblem problem(int status, String code, String message) {
        return new MatchCrudProblem(status, code, message);
    }
}
