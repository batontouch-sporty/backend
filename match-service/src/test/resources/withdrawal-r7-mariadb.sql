CREATE TABLE matches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(50) NOT NULL,
  description VARCHAR(500) NULL,
  start_at DATETIME(6) NOT NULL,
  end_at DATETIME(6) NOT NULL,
  max_participant INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RECRUITING',
  status_changed_at DATETIME(6) NULL,
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
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_matches_status_start (status,start_at),
  KEY idx_matches_status_end (status,end_at),
  KEY idx_matches_sport_status_start (sport_type,status,start_at),
  KEY idx_matches_region_start (region,start_at),
  KEY idx_matches_facility (facility_id),
  CONSTRAINT ck_matches_fields CHECK (CHAR_LENGTH(TRIM(title)) > 0 AND max_participant >= 1 AND end_at > start_at AND facility_id > 0),
  CONSTRAINT ck_matches_status CHECK (status IN ('RECRUITING','CLOSED','IN_PROGRESS','COMPLETED','CANCELLED')),
  CONSTRAINT ck_matches_cancelled_at CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
  CONSTRAINT ck_matches_skill CHECK (skill_level IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
  CONSTRAINT ck_matches_sport_type CHECK (sport_type IN ('SOCCER','FUTSAL','BASKETBALL','BASEBALL','TENNIS','BADMINTON','TABLE_TENNIS','VOLLEYBALL','SWIMMING','RUNNING')),
  CONSTRAINT ck_matches_gender CHECK (gender_group IN ('MALE','FEMALE','MIXED'))
) ENGINE=InnoDB;
CREATE TABLE match_participants (
  match_participant_id BIGINT NOT NULL AUTO_INCREMENT,
  match_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL COMMENT 'user_db.user.id의 논리 참조; FK 없음',
  role VARCHAR(20) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (match_participant_id),
  CONSTRAINT uk_match_participants_match_user UNIQUE (match_id,user_id),
  KEY idx_match_participants_user (user_id),
  CONSTRAINT fk_match_participants_match FOREIGN KEY (match_id) REFERENCES matches(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_match_participants_user_id CHECK (user_id > 0),
  CONSTRAINT ck_match_participants_role CHECK (role IN ('OWNER','PARTICIPANT'))
) ENGINE=InnoDB;
CREATE TABLE processed_events (
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;
CREATE TABLE outbox_event (
  event_id VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  PRIMARY KEY (event_id),
  KEY idx_match_outbox_unpublished (published_at,created_at)
) ENGINE=InnoDB;
CREATE INDEX idx_outbox_event_aggregate_id ON outbox_event (aggregate_id);

-- R8 waitlist supplement, required by withdrawal cleanup.
CREATE TABLE match_waitlist (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 match_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 registered_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uk_match_waitlist_match_user UNIQUE(match_id,user_id),
 CONSTRAINT fk_match_waitlist_match FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
);
