package com.example.sporty.user.users.domain.entity;

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
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    // enum은 테이블 명세(R4)대로 VARCHAR에 저장한다. 지정하지 않으면 MariaDB ENUM 타입으로 만들어진다.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    // 가입은 항상 USER. 관리자는 초기 데이터로 넣는다.
    @Builder.Default
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.USER;

    @Column(name ="withdrawn_at")
    private Date withdrawnAt;

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

    // 프로필
    @OneToOne(mappedBy = "user")
    private ProfileEntity profileEntity;


    // [USR-04] 탈퇴 접수: ACTIVE → WITHDRAWING. 이 동안 로그인·토큰 재발급·매치 참가가 막힌다.
    public void startWithdrawal() {
        this.status = UserStatus.WITHDRAWING;
    }

    // [USR-04] 매치 정리 완료(CLEANED) → WITHDRAWN
    // 이미 탈퇴한 회원이면 처음 탈퇴 시각을 유지한다.
    public void completeWithdrawal() {
        if (this.status == UserStatus.WITHDRAWN) {
            return;
        }
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = new Date();
    }

    // [USR-04] 거절(REJECTED)·시간 초과(TIMED_OUT) 보상: WITHDRAWING → ACTIVE
    // 탈퇴 중일 때만 되돌린다. 이미 탈퇴한 회원을 살리지 않는다.
    public void restoreFromWithdrawing() {
        if (this.status == UserStatus.WITHDRAWING) {
            this.status = UserStatus.ACTIVE;
        }
    }

}
