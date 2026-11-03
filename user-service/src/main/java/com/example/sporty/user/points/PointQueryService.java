package com.example.sporty.user.points;

import com.example.sporty.user.commons.exception.users.UserNotFoundException;
import com.example.sporty.user.users.domain.entity.UserEntity;
import com.example.sporty.user.users.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
class PointQueryService {
    private final UserRepository users;
    private final PointHistoryRepository history;
    private final Clock clock;

    record Balance(int points, int attendanceRewardCount, boolean participationSuspended,
                   @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime suspendedUntil) {}
    record Entry(long id, Long matchId, String requestId, PointHistory.Reason reason, int beforePoints,
                 int afterPoints, int delta,
                 @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt) {
        static Entry from(PointHistory row) {
            return new Entry(row.getId(), row.getMatchId(), row.getRequestId(), row.getReason(),
                    row.getBeforePoints(), row.getAfterPoints(), row.getDelta(), row.getCreatedAt());
        }
    }
    record HistoryPage(List<Entry> content, int page, int size, long totalElements, int totalPages) {}

    Balance balance(long userId) {
        UserEntity user = users.findById(userId).orElseThrow(UserNotFoundException::new);
        LocalDateTime until = user.getSuspendedUntil();
        boolean suspended = until != null && until.isAfter(LocalDateTime.ofInstant(clock.instant(), PointSettlementService.SEOUL));
        return new Balance(user.getPoints(), user.getAttendanceRewardCount(), suspended, suspended ? until : null);
    }

    HistoryPage history(long userId, int page, int size) {
        if (!users.existsById(userId)) throw new UserNotFoundException();
        var result = history.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
        return new HistoryPage(result.map(Entry::from).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }
}
