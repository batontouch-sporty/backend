-- R10: 보증금(deposit) 방식. 참가·생성·승급 시 보증금 차감, 출석 시 반환, 5회 출석 보상, 노쇼 몰수. 점수 상한 100은 반환·보상에도 적용.
-- 신규 빈 DB 설치용. 운영 데이터는 없고 개발·테스트 데이터만 있으므로 백필은 없다.
-- 이미 R9가 설치된 DB는 02_Sporty_v2_A_R9_to_R10_migration.sql(변경 SQL)을 쓴다. 01과 02는 연속 실행하지 않는다.
-- 적용 전 백업을 받아 둔다. 코드 변경과 같은 PR·같은 배포 단위로 적용한다(DDL만 먼저 적용하면 기존 코드의 모임 생성·참가가 실패한다).
-- Sporty v2 A안 DBA/DA 설계 수정본 R10: MariaDB 서버 1개, 서비스별 DB 4개.
-- 기준: Sporty_v2 MSA 구현 계획 v2 (2026-10-30). AI 서비스는 DB가 없다.
-- 신규 빈 환경 설치용. 기존 1차 sporty DB에 직접 실행하지 않는다.
CREATE DATABASE IF NOT EXISTS user_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS facility_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS match_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE user_db.`user` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(50) NOT NULL,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(10) NOT NULL DEFAULT 'USER',
  gender VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  points INT NOT NULL DEFAULT 50,
  attendance_reward_count INT NOT NULL DEFAULT 0,
  suspended_until DATETIME(6) NULL,
  withdrawn_at DATETIME(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_user_suspended_until (suspended_until),
  CONSTRAINT uk_user_email UNIQUE (email),
  CONSTRAINT ck_user_role CHECK (role IN ('USER','ADMIN')),
  CONSTRAINT ck_user_gender CHECK (gender IN ('MALE','FEMALE')),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','WITHDRAWING','WITHDRAWN')),
  CONSTRAINT ck_user_points CHECK (points BETWEEN 0 AND 100),
  CONSTRAINT ck_user_attendance_reward_count CHECK (attendance_reward_count BETWEEN 0 AND 4)
) ENGINE=InnoDB;

CREATE TABLE user_db.point_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  match_id BIGINT NULL COMMENT 'match_db.matches.id의 논리 참조; FK 없음',
  request_id VARCHAR(36) NULL COMMENT '참가·생성·승급 요청 ID(UUID). match_db의 match_participants.request_id / matches.creation_request_id와 같은 값(논리 참조; FK 없음)',
  reason VARCHAR(30) NOT NULL,
  before_points INT NOT NULL,
  after_points INT NOT NULL,
  delta INT NOT NULL,
  source_event_id VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  request_decision_key VARCHAR(40) AS (
    CASE WHEN reason IN ('DEPOSIT_CHARGED','DEPOSIT_CANCELLED') THEN CONCAT('C:',request_id)
         WHEN reason IN ('DEPOSIT_RETURNED','NO_SHOW_FORFEITED') THEN CONCAT('S:',request_id)
    END) PERSISTENT COMMENT '요청당 차감/취소 중 1건, 반환/몰수 중 1건만 허용하기 위한 생성 컬럼',
  PRIMARY KEY (id),
  CONSTRAINT uk_point_history_request_reason UNIQUE (request_id,reason),
  CONSTRAINT uk_point_history_request_decision UNIQUE (request_decision_key),
  KEY idx_point_history_user_created (user_id,created_at),
  CONSTRAINT fk_point_history_user FOREIGN KEY (user_id) REFERENCES user_db.`user`(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_point_history_reason CHECK (reason IN ('SIGNUP','SUSPENSION_EXPIRED','DEPOSIT_CHARGED','DEPOSIT_RETURNED','DEPOSIT_CANCELLED','ATTENDANCE_REWARDED','NO_SHOW_FORFEITED')),
  CONSTRAINT ck_point_history_points CHECK (before_points BETWEEN 0 AND 100 AND after_points BETWEEN 0 AND 100 AND delta = after_points - before_points),
  CONSTRAINT ck_point_history_delta CHECK (
    (reason = 'DEPOSIT_CHARGED' AND delta < 0)
    OR (reason IN ('DEPOSIT_RETURNED','ATTENDANCE_REWARDED') AND delta >= 0)
    OR (reason IN ('DEPOSIT_CANCELLED','NO_SHOW_FORFEITED') AND delta = 0)
    OR reason IN ('SIGNUP','SUSPENSION_EXPIRED')),
  CONSTRAINT ck_point_history_match CHECK (
    (reason IN ('DEPOSIT_CHARGED','DEPOSIT_RETURNED','DEPOSIT_CANCELLED','ATTENDANCE_REWARDED','NO_SHOW_FORFEITED')
       AND match_id IS NOT NULL AND request_id IS NOT NULL AND source_event_id IS NOT NULL)
    OR (reason IN ('SIGNUP','SUSPENSION_EXPIRED') AND match_id IS NULL AND request_id IS NULL AND source_event_id IS NULL))
) ENGINE=InnoDB;

CREATE TABLE user_db.profile (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  nickname VARCHAR(50) NOT NULL,
  district VARCHAR(30) NOT NULL,
  image_url VARCHAR(255) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_profile_user UNIQUE (user_id),
  CONSTRAINT uk_profile_nickname UNIQUE (nickname),
  CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES user_db.`user`(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE user_db.sport_preference (
  id BIGINT NOT NULL AUTO_INCREMENT,
  profile_id BIGINT NOT NULL,
  sport_type VARCHAR(15) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_sport_preference_profile_sport UNIQUE (profile_id,sport_type),
  CONSTRAINT ck_sport_preference_type CHECK (sport_type IN ('SOCCER','FUTSAL','BASKETBALL','BASEBALL','TENNIS','BADMINTON','TABLE_TENNIS','VOLLEYBALL','SWIMMING','RUNNING')),
  CONSTRAINT fk_sport_preference_profile FOREIGN KEY (profile_id) REFERENCES user_db.profile(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE user_db.withdrawal_saga (
  saga_id CHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  reason VARCHAR(200) NULL,
  requested_at DATETIME(6) NOT NULL,
  deadline_at DATETIME(6) NOT NULL,
  claim_token VARCHAR(64) NOT NULL COMMENT '탈퇴 요청 event_id; 요청 이벤트와 Saga 연결',
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (saga_id),
  CONSTRAINT uk_withdrawal_saga_claim_token UNIQUE (claim_token),
  KEY idx_withdrawal_saga_status_deadline (status,deadline_at),
  KEY idx_withdrawal_saga_user (user_id),
  CONSTRAINT fk_withdrawal_saga_user FOREIGN KEY (user_id) REFERENCES user_db.`user`(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_withdrawal_saga_status CHECK (status IN ('PROCESSING','CLEANED','REJECTED','TIMED_OUT')),
  CONSTRAINT ck_withdrawal_saga_deadline CHECK (deadline_at > requested_at),
  CONSTRAINT ck_withdrawal_saga_finished CHECK ((status = 'PROCESSING' AND finished_at IS NULL) OR (status IN ('CLEANED','REJECTED','TIMED_OUT') AND finished_at IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE user_db.processed_events (
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;

CREATE TABLE user_db.outbox_event (
  event_id VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  PRIMARY KEY (event_id),
  KEY idx_user_outbox_unpublished (published_at,created_at)
) ENGINE=InnoDB;

CREATE TABLE facility_db.location (
  id BIGINT NOT NULL AUTO_INCREMENT,
  region VARCHAR(20) NOT NULL,
  name VARCHAR(100) NOT NULL,
  facility_name VARCHAR(100) NULL,
  contact VARCHAR(100) NULL,
  latitude DECIMAL(18,15) NULL,
  longitude DECIMAL(18,15) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE facility_db.service (
  id BIGINT NOT NULL AUTO_INCREMENT,
  location_id BIGINT NOT NULL,
  service_id VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  status VARCHAR(20) NULL,
  start_time TIME NULL,
  end_time TIME NULL,
  is_free BOOLEAN NULL,
  url VARCHAR(200) NULL,
  reservation_deadline_at DATETIME(6) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_facility_service_external_id UNIQUE (service_id),
  KEY idx_facility_service_location (location_id),
  CONSTRAINT fk_facility_service_location FOREIGN KEY (location_id) REFERENCES facility_db.location(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE match_db.matches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(50) NOT NULL,
  description VARCHAR(500) NULL,
  start_at DATETIME(6) NOT NULL,
  end_at DATETIME(6) NOT NULL,
  max_participant INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CREATION_PENDING',
  status_changed_at DATETIME(6) NULL,
  creation_request_id VARCHAR(36) NOT NULL COMMENT '모임 생성 요청 ID(UUID); match-service가 발급',
  cancel_reason VARCHAR(200) NULL,
  cancelled_at DATETIME(6) NULL,
  skill_level VARCHAR(30) NOT NULL DEFAULT 'BEGINNER',
  sport_type VARCHAR(15) NOT NULL,
  gender_group VARCHAR(10) NOT NULL,
  facility_id BIGINT NOT NULL COMMENT 'facility_db.service.id의 논리 참조; FK 없음',
  facility_name VARCHAR(200) NOT NULL,
  location_name VARCHAR(100) NOT NULL,
  region VARCHAR(20) NOT NULL,
  is_free BOOLEAN NULL,
  url VARCHAR(200) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_matches_creation_request UNIQUE (creation_request_id),
  KEY idx_matches_status_start (status,start_at),
  KEY idx_matches_status_end (status,end_at),
  KEY idx_matches_sport_status_start (sport_type,status,start_at),
  KEY idx_matches_region_start (region,start_at),
  KEY idx_matches_facility (facility_id),
  CONSTRAINT ck_matches_fields CHECK (CHAR_LENGTH(TRIM(title)) > 0 AND max_participant >= 2 AND end_at > start_at AND facility_id > 0),
  CONSTRAINT ck_matches_status CHECK (status IN ('CREATION_PENDING','CREATION_FAILED','RECRUITING','CLOSED','IN_PROGRESS','COMPLETED','CANCELLED')),
  CONSTRAINT ck_matches_cancelled_at CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
  CONSTRAINT ck_matches_skill CHECK (skill_level IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
  CONSTRAINT ck_matches_sport_type CHECK (sport_type IN ('SOCCER','FUTSAL','BASKETBALL','BASEBALL','TENNIS','BADMINTON','TABLE_TENNIS','VOLLEYBALL','SWIMMING','RUNNING')),
  CONSTRAINT ck_matches_gender CHECK (gender_group IN ('MALE','FEMALE','MIXED'))
) ENGINE=InnoDB;

CREATE TABLE match_db.match_participants (
  match_participant_id BIGINT NOT NULL AUTO_INCREMENT,
  match_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL COMMENT 'user_db.user.id의 논리 참조; FK 없음',
  role VARCHAR(20) NOT NULL,
  request_id VARCHAR(36) NOT NULL COMMENT '참가·승급 요청 ID(UUID); match-service가 발급, 재신청 시 새 값으로 갱신. 방장 행은 matches.creation_request_id와 같은 값(방장 보증금 반환이 두 번 기록되지 않게)',
  participation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  match_participant_status VARCHAR(20) NOT NULL DEFAULT 'ATTENDED',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (match_participant_id),
  CONSTRAINT uk_match_participants_match_user UNIQUE (match_id,user_id),
  CONSTRAINT uk_match_participants_request UNIQUE (request_id),
  KEY idx_match_participants_user (user_id),
  CONSTRAINT fk_match_participants_match FOREIGN KEY (match_id) REFERENCES match_db.matches(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_match_participants_user_id CHECK (user_id > 0),
  CONSTRAINT ck_match_participants_role CHECK (role IN ('OWNER','PARTICIPANT')),
  CONSTRAINT ck_match_participants_participation CHECK (participation_status IN ('PENDING','CONFIRMED','FAILED','CANCELLED')),
  CONSTRAINT ck_match_participants_attendance CHECK (match_participant_status IN ('ATTENDED','NO_SHOW'))
) ENGINE=InnoDB;

CREATE TABLE match_db.match_waitlist (
  id BIGINT NOT NULL AUTO_INCREMENT,
  match_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL COMMENT 'user_db.user.id의 논리 참조; FK 없음',
  registered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_match_waitlist_match_user UNIQUE (match_id,user_id),
  KEY idx_match_waitlist_match_order (match_id,registered_at,id),
  KEY idx_match_waitlist_user (user_id),
  CONSTRAINT fk_match_waitlist_match FOREIGN KEY (match_id) REFERENCES match_db.matches(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT ck_match_waitlist_user_id CHECK (user_id > 0)
) ENGINE=InnoDB;

CREATE TABLE match_db.processed_events (
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;

CREATE TABLE match_db.outbox_event (
  event_id VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  PRIMARY KEY (event_id),
  KEY idx_outbox_event_aggregate_id (aggregate_id),
  KEY idx_match_outbox_unpublished (published_at,created_at)
) ENGINE=InnoDB;

CREATE TABLE notification_db.notification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  recipient_user_id BIGINT NOT NULL COMMENT 'user_db.user.id의 논리 참조; FK 없음',
  type VARCHAR(50) NOT NULL,
  title VARCHAR(100) NOT NULL,
  body VARCHAR(500) NOT NULL,
  target_match_id BIGINT NULL COMMENT 'match_db.matches.id의 논리 참조; FK 없음',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  read_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_event_recipient_type UNIQUE (event_id,recipient_user_id,type),
  KEY idx_notification_recipient_read_created (recipient_user_id,read_at,created_at),
  CONSTRAINT ck_notification_recipient_id CHECK (recipient_user_id > 0),
  CONSTRAINT ck_notification_match_id CHECK (target_match_id IS NULL OR target_match_id > 0)
) ENGINE=InnoDB;

CREATE TABLE notification_db.processed_events (
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;
