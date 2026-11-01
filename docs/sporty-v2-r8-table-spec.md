# Sporty v2 R8 ERD and table specification

This document points to the shared schema artifacts for issue #15. The MariaDB
DDL remains the source of truth for types, nullability, keys, checks and indexes.
Do not run the R8 install script against the legacy `sporty` database.

| Artifact | Location | Purpose |
| --- | --- | --- |
| R8 install DDL and R7-to-R8 migration | [Notion DDL task](https://app.notion.com/p/40aacf1215c081869e7ecf41c57cefe1) | New empty databases and existing R7 databases use different scripts. |
| Table specification | [Sporty team Google Sheet](https://docs.google.com/spreadsheets/d/1F15pg_r1B2P2dcYx2t711Dly_EKglfvVFggl4VnKl3I/edit) | `테이블_개요`, `컬럼_명세`, `관계_인덱스`, and migration/decision tabs. |
| ERD | [ERDCloud](https://www.erdcloud.com/d/pgng7QFGTrLtasq7t) | Service-owned databases and their relationships. |
| ERD review notes | [Notion ERD task](https://app.notion.com/p/40aacf1215c08183a9b7ece34fb114f2) | ERDCloud review progress and remaining checks. |
| Annotated ERD image | [Notion DDL task](https://app.notion.com/p/40aacf1215c081869e7ecf41c57cefe1) | Shows facility reservation URL in match snapshot example. |

## Ownership and scope

One MariaDB server contains four service-owned databases:

| Service | Database | Tables |
| --- | --- | --- |
| user-service | `user_db` | `user`, `profile`, `sport_preference`, `withdrawal_saga`, `processed_events`, `outbox_event` |
| facility-service | `facility_db` | `location`, `service` |
| match-service | `match_db` | `matches`, `match_participants`, `match_waitlist`, `processed_events`, `outbox_event` |
| notification-service | `notification_db` | `notification`, `processed_events` |

AI service has no database. IDs from another service are stored as values; no
cross-database foreign keys are created. The physical foreign keys stay within
the owning database.

## R8 details to preserve

- `user.role` is `VARCHAR(10) NOT NULL DEFAULT 'USER'`, limited to `USER` and `ADMIN`.
- `user.status` includes `WITHDRAWING`; `withdrawal_saga` starts in `PROCESSING`.
- `matches.status` is `VARCHAR(20)` with `RECRUITING`, `CLOSED`, `IN_PROGRESS`,
  `COMPLETED`, and `CANCELLED`.
- `matches` stores the facility ID and a creation/update-time snapshot of the
  facility name, location name, region, free/paid flag, and reservation link.
  `matches.url` is nullable `VARCHAR(200)` and copies `facility_db.service.url`.
- `match_waitlist` has unique `(match_id, user_id)`, a `user_id` index, and a
  same-database `match_id` foreign key with `ON DELETE CASCADE`.
  `match_participants.match_id` instead uses `ON DELETE RESTRICT`.
- Only `match_db.outbox_event.aggregate_id` has the R8 aggregate lookup index.
  The user-service outbox has no such lookup index in the current contract.
- `notification` stores one alert per `(event_id, recipient_user_id, type)`.

## Comparison performed for issue #15

The shared sheet's 15 database-qualified tables and 112 column definitions were
compared with the R8 install DDL. Column names, SQL types and nullability match.
The 56 entries in `관계_인덱스` name constraints or indexes found in that DDL;
all named DDL constraints and indexes are represented in the sheet. The sheet's
older `identity-service` ownership label was changed to the repository's
`user-service` name. This is a static document comparison; it is not a record
of applying DDL to the team database or running JPA `validate` there.

ERDCloud is a presentation of the model. Where it does not show a CHECK,
unique key, index, or `ON DELETE` action, use the DDL and the sheet's
`관계_인덱스` tab for the exact rule.
