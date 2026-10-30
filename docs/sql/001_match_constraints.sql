-- Run preflight queries first. Any returned rows require manual correction;
-- this migration never deletes or silently deduplicates existing records.
-- Apply while application writes are paused. MariaDB DDL commits implicitly.

SELECT match_id, user_id, COUNT(*) FROM match_participant
GROUP BY match_id, user_id HAVING COUNT(*) > 1;
SELECT profile_id, sport_type, COUNT(*) FROM sport_preference
GROUP BY profile_id, sport_type HAVING COUNT(*) > 1;
SELECT id FROM `match` WHERE title IS NULL OR CHAR_LENGTH(TRIM(title)) = 0
OR start_at IS NULL OR end_at IS NULL OR end_at <= start_at
OR max_participant IS NULL OR max_participant < 1 OR service_id IS NULL OR service_id < 1;

-- Apply each ALTER only after all three preflight queries return zero rows.
ALTER TABLE match_participant ADD CONSTRAINT uk_match_participant_match_user UNIQUE (match_id, user_id);
ALTER TABLE sport_preference ADD CONSTRAINT uk_sport_preference_profile_sport UNIQUE (profile_id, sport_type);
ALTER TABLE `match`
    MODIFY title VARCHAR(50) NOT NULL,
    MODIFY start_at DATETIME NOT NULL,
    MODIFY end_at DATETIME NOT NULL,
    MODIFY max_participant INT NOT NULL,
    MODIFY service_id BIGINT NOT NULL,
    ADD CONSTRAINT ck_match_valid_fields CHECK
        (CHAR_LENGTH(TRIM(title)) > 0 AND max_participant >= 1 AND end_at > start_at AND service_id > 0);
