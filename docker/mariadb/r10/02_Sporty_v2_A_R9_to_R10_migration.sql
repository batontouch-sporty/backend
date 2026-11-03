-- Sporty v2 A안 R9 -> R10 (보증금 방식) 변경 SQL. 01_R10 신규 설치와 같은 구조가 되도록 변경분만 담았다.
-- 전제: 개발·테스트 데이터 초기화 완료(운영 데이터 없음). 아래 3개 테이블이 비어 있어야 한다.
--   user_db.point_history, match_db.matches, match_db.match_participants
--   (user, profile 등 회원 데이터는 그대로 둔다. attendance_reward_count는 기본 0으로 채워진다.)
-- 적용 전 백업 필수: 이 파일은 DDL 암묵적 커밋이라 롤백되지 않고 다시 실행할 수도 없다. 데이터가 비어 있어도 mariadb-dump로 구조를 백업해 둔다.
-- 코드 변경과 같은 PR·같은 배포 단위로 적용한다(DDL만 먼저 적용하면 기존 코드의 모임 생성·참가가 실패한다).
-- 실행: mariadb 클라이언트로 그대로 실행하고 --force 는 쓰지 않는다(오류 시 즉시 중단). DDL은 암묵적 커밋이라 롤백되지 않는다.
-- 재실행 불가: 이미 적용된 DB에서는 첫 ALTER에서 오류가 난다.

-- 0. 사전 점검: 비어 있지 않으면 중단
DELIMITER //
BEGIN NOT ATOMIC
  IF (SELECT COUNT(*) FROM user_db.point_history) > 0
     OR (SELECT COUNT(*) FROM match_db.matches) > 0
     OR (SELECT COUNT(*) FROM match_db.match_participants) > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'R10 변경 중단: point_history, matches, match_participants를 먼저 초기화하세요';
  END IF;
END //
DELIMITER ;

-- 1. user: 출석 보상 누적 횟수
ALTER TABLE user_db.`user`
  ADD COLUMN attendance_reward_count INT NOT NULL DEFAULT 0 AFTER points,
  ADD CONSTRAINT ck_user_attendance_reward_count CHECK (attendance_reward_count BETWEEN 0 AND 4);

-- 2. point_history: 요청 ID 기반 중복 방지로 교체. match_id 단독 인덱스는 R10에서 쓰지 않아 제거한다(R9 최종본에 없으면 건너뜀).
ALTER TABLE user_db.point_history
  DROP INDEX IF EXISTS uk_point_history_user_match,
  DROP INDEX IF EXISTS idx_point_history_match,
  DROP CONSTRAINT IF EXISTS ck_point_history_reason,
  DROP CONSTRAINT IF EXISTS ck_point_history_match,
  DROP CONSTRAINT IF EXISTS ck_point_history_delta,
  ADD COLUMN request_id VARCHAR(36) NULL
    COMMENT '참가·생성·승급 요청 ID(UUID). match_db의 match_participants.request_id / matches.creation_request_id와 같은 값(논리 참조; FK 없음)'
    AFTER match_id,
  ADD COLUMN request_decision_key VARCHAR(40) AS (
    CASE WHEN reason IN ('DEPOSIT_CHARGED','DEPOSIT_CANCELLED') THEN CONCAT('C:',request_id)
         WHEN reason IN ('DEPOSIT_RETURNED','NO_SHOW_FORFEITED') THEN CONCAT('S:',request_id)
    END) PERSISTENT COMMENT '요청당 차감/취소 중 1건, 반환/몰수 중 1건만 허용하기 위한 생성 컬럼',
  ADD CONSTRAINT uk_point_history_request_reason UNIQUE (request_id,reason),
  ADD CONSTRAINT uk_point_history_request_decision UNIQUE (request_decision_key),
  ADD CONSTRAINT ck_point_history_reason CHECK (reason IN ('SIGNUP','SUSPENSION_EXPIRED','DEPOSIT_CHARGED','DEPOSIT_RETURNED','DEPOSIT_CANCELLED','ATTENDANCE_REWARDED','NO_SHOW_FORFEITED')),
  ADD CONSTRAINT ck_point_history_delta CHECK (
    (reason = 'DEPOSIT_CHARGED' AND delta < 0)
    OR (reason IN ('DEPOSIT_RETURNED','ATTENDANCE_REWARDED') AND delta >= 0)
    OR (reason IN ('DEPOSIT_CANCELLED','NO_SHOW_FORFEITED') AND delta = 0)
    OR reason IN ('SIGNUP','SUSPENSION_EXPIRED')),
  ADD CONSTRAINT ck_point_history_match CHECK (
    (reason IN ('DEPOSIT_CHARGED','DEPOSIT_RETURNED','DEPOSIT_CANCELLED','ATTENDANCE_REWARDED','NO_SHOW_FORFEITED')
       AND match_id IS NOT NULL AND request_id IS NOT NULL AND source_event_id IS NOT NULL)
    OR (reason IN ('SIGNUP','SUSPENSION_EXPIRED') AND match_id IS NULL AND request_id IS NULL AND source_event_id IS NULL));

-- 3. matches: 생성 요청 ID, 생성 대기·실패 상태
ALTER TABLE match_db.matches
  DROP CONSTRAINT ck_matches_status,
  ALTER COLUMN status SET DEFAULT 'CREATION_PENDING',
  ADD COLUMN creation_request_id VARCHAR(36) NOT NULL COMMENT '모임 생성 요청 ID(UUID); match-service가 발급' AFTER status_changed_at,
  ADD CONSTRAINT uk_matches_creation_request UNIQUE (creation_request_id),
  ADD CONSTRAINT ck_matches_status CHECK (status IN ('CREATION_PENDING','CREATION_FAILED','RECRUITING','CLOSED','IN_PROGRESS','COMPLETED','CANCELLED'));

-- 4. match_participants: 요청 ID, 참가 상태 (기존 match_participant_status는 출석 결과로 유지)
ALTER TABLE match_db.match_participants
  ADD COLUMN request_id VARCHAR(36) NOT NULL COMMENT '참가·승급 요청 ID(UUID); match-service가 발급, 재신청 시 새 값으로 갱신. 방장 행은 matches.creation_request_id와 같은 값(방장 보증금 반환이 두 번 기록되지 않게)' AFTER role,
  ADD COLUMN participation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER request_id,
  ADD CONSTRAINT uk_match_participants_request UNIQUE (request_id),
  ADD CONSTRAINT ck_match_participants_participation CHECK (participation_status IN ('PENDING','CONFIRMED','FAILED','CANCELLED'));
