package com.example.sporty.user.admin.service;

import com.example.sporty.user.admin.domain.dto.AdminStatsResponseDto;
import com.example.sporty.user.users.domain.entity.UserStatus;
import com.example.sporty.user.users.repository.UserRepository;
import com.example.sporty.user.withdrawal.domain.entity.SagaStatus;
import com.example.sporty.user.withdrawal.repository.WithdrawalSagaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final WithdrawalSagaRepository withdrawalSagaRepository;
    private final UserRepository userRepository;

    // [A28] 탈퇴 Saga·회원 통계
    @Transactional(readOnly = true)
    public AdminStatsResponseDto getStats() {

        // 1. 상태별 건수를 한 번씩 묶어 센다(없는 상태는 0)
        Map<SagaStatus, Long> sagas = toMap(withdrawalSagaRepository.countGroupByStatus(), SagaStatus.class);
        Map<UserStatus, Long> users = toMap(userRepository.countGroupByStatus(), UserStatus.class);

        // 2. 명세 필드로 옮긴다
        long requested = sagas.values().stream().mapToLong(Long::longValue).sum();
        return AdminStatsResponseDto.builder()
                .withdrawalSaga(AdminStatsResponseDto.WithdrawalSagaStats.builder()
                        .requested(requested)
                        .succeeded(sagas.get(SagaStatus.CLEANED))
                        .rejected(sagas.get(SagaStatus.REJECTED))
                        .timedOut(sagas.get(SagaStatus.TIMED_OUT))
                        .inProgress(sagas.get(SagaStatus.PROCESSING))
                        .build())
                .users(AdminStatsResponseDto.UserStats.builder()
                        .active(users.get(UserStatus.ACTIVE))
                        .withdrawing(users.get(UserStatus.WITHDRAWING))
                        .withdrawn(users.get(UserStatus.WITHDRAWN))
                        .build())
                .build();
    }

    // [상태, 건수] 목록을 모든 상태가 들어 있는 Map으로 바꾼다.
    private static <E extends Enum<E>> Map<E, Long> toMap(List<Object[]> rows, Class<E> type) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (E status : type.getEnumConstants()) {
            counts.put(status, 0L);
        }
        for (Object[] row : rows) {
            counts.put(type.cast(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

}
