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


    // [USR-04] 회원탈퇴
    // 이미 탈퇴한 회원이 남은 Access Token으로 다시 요청해도 처음 탈퇴 시각을 유지한다.
    public void withdraw() {
        if (this.status == UserStatus.WITHDRAWN) {
            return;
        }
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = new Date();
    }

}
