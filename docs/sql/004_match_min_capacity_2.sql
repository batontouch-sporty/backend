-- Existing R8 match_db only. Fresh installations use docker/mariadb/schema.sql.
-- Deploy the application validation (@Min(2)) first, then apply this DDL with a
-- migration account. Stop execution on any SQL error; do not use mysql --force.
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

-- Apply only after the queries above return zero rows. The new CHECK also rejects
-- existing invalid rows, so an unexpected row causes the ALTER to fail.
ALTER TABLE match_db.matches
  DROP CONSTRAINT ck_matches_fields,
  ADD CONSTRAINT ck_matches_fields CHECK (
    CHAR_LENGTH(TRIM(title)) > 0
    AND max_participant >= 2
    AND end_at > start_at
    AND facility_id > 0
  );

-- Verify the installed definition and check for invalid rows.
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'match_db'
  AND TABLE_NAME = 'matches'
  AND CONSTRAINT_NAME = 'ck_matches_fields';
SELECT COUNT(*) AS invalid_match_count
FROM match_db.matches
WHERE max_participant < 2;
