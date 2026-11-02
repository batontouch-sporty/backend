-- Existing R8 match_db only. Fresh installations use docker/mariadb/schema.sql.
-- Deploy the application validation (@Min(2)) first, then apply this DDL with a
-- migration account. Pause match writes while applying this migration.
-- Run the whole file with the MariaDB CLI (supports DELIMITER); do not use --force.
-- Run the SELECT first. If it returns any rows, stop and inspect the status
-- counts. Agree on a separate, approved correction for every affected match
-- (including CANCELLED/COMPLETED); do not silently raise capacity or delete rows.
SELECT id, max_participant, status
FROM match_db.matches
WHERE max_participant < 2;

SELECT status, COUNT(*) AS invalid_match_count
FROM match_db.matches
WHERE max_participant < 2
GROUP BY status;

-- Recheck at the execution boundary: invalid rows never enter the ALTER branch.
-- This anonymous block creates no stored routine. NOT ATOMIC is not a rollback
-- guarantee; concurrent writes must remain paused until verification finishes.
DELIMITER //
BEGIN NOT ATOMIC
  IF EXISTS (SELECT 1 FROM match_db.matches WHERE max_participant < 2) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Migration stopped: matches with capacity below 2 exist; ALTER was not executed.';
  ELSE
    ALTER TABLE match_db.matches
      DROP CONSTRAINT ck_matches_fields,
      ADD CONSTRAINT ck_matches_fields CHECK (
        CHAR_LENGTH(TRIM(title)) > 0
        AND max_participant >= 2
        AND end_at > start_at
        AND facility_id > 0
      );
  END IF;
END//
DELIMITER ;

-- Verify the installed definition and check for invalid rows.
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'match_db'
  AND TABLE_NAME = 'matches'
  AND CONSTRAINT_NAME = 'ck_matches_fields';
SELECT COUNT(*) AS invalid_match_count
FROM match_db.matches
WHERE max_participant < 2;
