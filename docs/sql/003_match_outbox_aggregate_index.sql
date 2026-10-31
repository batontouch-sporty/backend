-- R7 match_db supplement for #34. Apply once as DBA/migrator after R7 table creation.
-- Do not execute as match_app; the application never performs schema changes.
-- Keep this index when incorporating the supplement into the team's canonical R7 DDL.
USE match_db;
CREATE INDEX idx_outbox_event_aggregate_id ON outbox_event (aggregate_id);
