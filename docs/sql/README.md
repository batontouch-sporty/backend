# Database constraint migration

`001_match_constraints.sql` upgrades an existing MariaDB schema without deleting data.
The JPA mappings contain the same constraints for newly created schemas. JPA `validate`
does not install constraints on existing tables, so the SQL migration is required there.

1. Pause application writes and take your normal database backup.
2. Run the three SELECT preflight queries. Stop if any returns rows; correct those records
   explicitly rather than deleting/deduplicating them automatically.
3. Run the three ALTER statements once. MariaDB DDL commits implicitly and cannot be
   rolled back with the integration-test transaction.
4. Run `./gradlew.bat integrationTest`. The suite uses direct SQL to check enforcement.

Alternatively, `scripts/ApplyConstraintMigration.java` runs these preflight queries and
applies the fixed SQL file only with `--apply`. It reads DB_URL, DB_USERNAME and DB_PASSWORD
from environment variables or `.env`, checks that the selected database is `sporty`, and
requires Java 17 and the MariaDB JDBC driver on the classpath. Without `--apply` it is read-only.
This is a one-time migration, not an idempotent startup hook. Do not reapply it after success.

Constraints:

- A user can have only one participant row per match.
- A profile can have only one preference row per sport.
- A match requires title, service ID, start/end timestamps and capacity.
- Title must contain non-whitespace text, capacity/service ID must be positive, and end
  must be after start.

Optional description, image URL and withdrawal timestamp remain nullable. A blanket
NOT NULL rule would change their existing meaning; it is not applied by this migration.
Signup requires an explicit sportTypes array; [] means no preferences. Null arrays and
null elements return HTTP 400. Duplicate sports are stored once.

Local execution: the migration was applied to `sporty` on 2026-09-24 after preflight passed.

## Facility catalog

`002_facility_catalog.sql` adds the supplied location/service schema and the match-to-service
foreign key. `scripts/ApplyFacilityMigration.java` checks for existing matches without a
real catalog record and stops rather than inventing records or deleting matches. Run it
without arguments for preflight, then with `--apply` once to install the migration.
The migration was applied locally on 2026-09-24 without modifying existing records.

Compatibility choices: IDs use BIGINT/AUTO_INCREMENT to match existing Long/identity mappings.
The supplied `longtitude` spelling is retained, with DECIMAL(11,8) so values near 127 fit.
No unrelated USER/profile tables are recreated from the draft DDL (which also contains
unsized VARCHAR/CHAR and ENUM declarations without values).

Match creation now requires a real row in `service` and returns 404 SERVICE_NOT_FOUND
otherwise. Facility import/search endpoints remain separate work: no production catalog
rows are fabricated or seeded. Integration tests create and roll back their own catalog
fixtures. Identity counters may advance during tests even though records roll back.

The existing dev profile still uses create-drop; use a validate/no-DDL profile when keeping
catalog data across application restarts. These integration tests explicitly use validate.

## Match minimum capacity (R8)

`004_match_min_capacity_2.sql` changes the CHECK on an existing `match_db.matches`
table to require at least two participants, including the owner. Apply it after the
application accepts only capacity 2 or greater and after checking for existing
capacity-1 rows. Stop on SQL errors. Fresh Compose installations use
`docker/mariadb/schema.sql` and do not need 004. The earlier 001/002 scripts target
the original `sporty` database and must not be run against `match_db`.

Run the entire 004 file with the MariaDB CLI in batch mode (for example, log in
with `mariadb --abort-source-on-error --host=<host> --user=<migration-user> --password`
and run `SOURCE docs/sql/004_match_min_capacity_2.sql;`). Do not use `--force` or
split the file on semicolons: the anonymous `BEGIN NOT ATOMIC` block requires
`DELIMITER` support. It rechecks for capacity-1 rows and raises SQLSTATE 45000
without executing ALTER if any exist. The block creates no stored procedure.
Pause match writes through verification; the preflight and DDL are not one
atomic transaction. A nonzero exit/error is a failed migration, not success.

Verified in a disposable MariaDB 11.4 instance: an existing capacity-1 row raises
45000 and preserves the old CHECK; a SELECT-only account gets the same guard
error (the ALTER branch is not executed). With no invalid rows, application and
reapplication succeed; capacity 1 is rejected by CHECK and capacity 2 is accepted.
Anonymous-block syntax: [MariaDB documentation](https://mariadb.com/docs/server/reference/sql-statements/programmatic-compound-statements/using-compound-statements-outside-of-stored-programs).
