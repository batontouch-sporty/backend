# Specification compliance review

## Remediation status (2026-09-24)

- Finding 2 fixed: signup rejects missing/null sportTypes and null elements; an empty list is valid, and repeated sports are stored once. Regression tests use MariaDB.
- Finding 5 database constraints fixed for required match fields, duplicate match participants and duplicate preferences. The preflight-checked migration in `docs/sql/001_match_constraints.sql` was applied to the local sporty database without deleting records. Optional fields remain nullable; the blanket no-NULL specification still needs clarification.
- Finding 1 fixed using the supplied service/location definitions: match creation validates the local service catalog before writes and returns 404 SERVICE_NOT_FOUND for an absent service. Location/service entities and foreign keys were added. Migration 002 was applied locally without changing existing records. Facility import/search APIs remain separate unfinished features; the catalog has no fabricated production rows.
- The integration suite now passes 18 tests, including the exact missing-service/no-write case and direct-SQL attempts to bypass database constraints. The 34 service/controller/JWT tests also pass. The original review below is retained as the baseline.

Reviewed 2026-09-24 against the current checkout only (not other branches or pending PRs).
Sources: uploaded MINI_PROJECT requirements PDF, pages 1?12; uploaded test-case workbook, Sheet1, rows 2?63 (62 populated cases; remaining formatted rows are empty).
Method: source review of backend, frontend routes/forms, and existing tests. The previous integration run passed 11 tests against MariaDB/Redis; this review does not claim that all 62 spreadsheet cases were executed. No application code or source documents were changed.

## Main findings

1. **High ? EM-01 / TC-EM01-04: missing service-existence validation.** `src/main/java/com/example/sporty/features/exerciseMatching/service/MatchService.java:66` leaves lookup as TODO, then saves the supplied serviceId. There is no service entity/repository or FK relation. An unknown positive service ID is accepted instead of the required 404. Existing successful creation tests use an arbitrary service ID, so they do not prove this requirement.
2. **High ? signup null-list validation.** `features/users/domain/dto/UserSignUpRequestDto.java:41` has no constraint/default on sportTypes; `features/users/service/UserService.java:57` iterates it unconditionally. A request otherwise valid but omitting/nulling sportTypes reaches a null dereference instead of a controlled input-validation response. Null/duplicate list entries also lack explicit validation/deduplication. Decide whether empty preferences are legal, then validate accordingly (NFR-04).
3. **High ? frontend flow is unfinished in this checkout.** `../../frontend/src/routes/AppRoutes.jsx` contains placeholder main/login/signup/profile/detail/facility screens, and no match-list screen. The real match-create form depends on `/api/services`, which has no backend controller. Signup ? login ? facility selection ? creation ? detail is therefore not an implemented browser flow here. This is separate from passing backend tests.
4. **Medium ? EM-03 location information is missing.** `features/exerciseMatching/service/MatchService.java:172` deliberately leaves serviceName/locationName/region null. The API returns core match data and participants, but UI-03 requires location. Facility integration is needed.
5. **NFR-04 database enforcement is partial.** User email/profile nickname have uniqueness mappings, but MatchParticipantEntity has no mapped unique constraint on (match_id,user_id). Description/location-related data can be null. Actual production DDL and concurrent duplicate handling were not audited. The blanket PDF rule ?NULL values are not stored? conflicts with optional/missing-data behavior and needs clarification.

Paths beginning `features/` above are relative to `src/main/java/com/example/sporty/`.

## Requirement coverage

| Requirement | Assessment in this checkout |
|---|---|
| USR-01 signup | Partial: normal signup, hashing, profile/preferences, duplicate checks implemented; list validation and documented request naming need attention. |
| USR-02 login | Implemented core contract: active-user/password checks, JWT and Redis storage; covered by integration tests. |
| USR-03 logout | Missing backend endpoint/token invalidation. |
| USR-04 withdrawal | Missing endpoint/password verification/state transition. |
| USR-05 refresh | Missing endpoint despite token generation/storage being present. |
| PR-01 / PR-02 | Missing profile update/read endpoints. Profile persistence at signup is not equivalent. |
| EM-01 create | Partial: save + OWNER participant + validation/auth; missing service lookup/404. |
| EM-02 list/search | Backend API matches documented filters, combined AND conditions and time boundaries. Frontend UI-09 missing. |
| EM-03 detail | Partial: core fields, roles, count and 404 implemented; location/facility fields missing. |
| EM-04 edit | Missing PUT endpoint. |
| EM-05 / EM-06 / EM-07 | Delete/join/leave controller methods return 501 after authentication; business behavior missing. |
| AI-01 / AI-02 / AI-03 | No implementing controllers/services. Dependencies/security matchers are not implementations. |
| FC-01 / FC-02 | No facility controllers/services/Open API adapter or 503/fallback handling. |
| NFR-01 usability | Only source-level frontend review; no browser usability evaluation. Main flow incomplete. |
| NFR-02 <=2s | Not verified: no response-time/load assertions. Form loading/error states exist but are not system-wide evidence. |
| NFR-03 security | Partial evidence: JWT, BCrypt, environment-based configuration, safe participant DTOs. No repository-history secret audit or implemented public profile API to assess. |
| NFR-04 validation/data | Partial, with defects above; facility consistency not enforced. |
| NFR-05 collaboration | Not assessed; requires issue/PR/history review against agreed conventions. |

## Spec inconsistencies to resolve

- Workbook USR-001 uses `region=??` and `sports=["??","????"]`; code accepts `district` (e.g. GANGNAM) and `sportTypes` (e.g. FUTSAL). PDF p8 mixes sports/sportTypes. Pick one canonical payload; do not call the literal workbook sample passing.
- PDF p7 UI-09 requires region/sport/service-status filters; PDF pp9?10 EM-02 API lists sportType but neither region nor status. Backend follows the API subsection, not the whole screen requirement.
- PDF uses Integer for some IDs, code uses Long. Agree and update the spec; widening IDs is not inherently a functional error.
- PDF withdrawal completion says account deletion; workbook expects WITHDRAWN status. Define soft-delete semantics and related-data handling.
- PDF contains party CRUD and a PR-03 reference without corresponding functional/API requirements.
- PDF requires facility consistency and also manual input on external-service failure; define how fallback entries are represented/validated.
- Workbook lacks EM-03 and EM-04 cases and a standalone successful-delete case; all 62 case statuses are Not Run. Existing code tests do not automatically update these statuses.

## Test-case traceability

?Implemented / source? means the code appears to provide the behavior, not that this exact spreadsheet scenario was rerun. ?Related tests? may combine cases or use different fixture inputs. Missing features can still have an authentication guard without implementing the endpoint contract.

| Sheet row | Case | Scenario | Assessment |
|---|---|---|---|
| 2 | USR-001 | 정상 회원가입 | Partial: core signup tested; literal workbook payload differs from DTO |
| 3 | USR-002 | 이메일 중복 회원가입 | Implemented; related ApiIntegrationTest coverage |
| 4 | USR-03 | 닉네임 중복 회원가입 | Implemented; related ApiIntegrationTest coverage |
| 5 | USR-004 | 정상 회원탈퇴 | Missing implementation |
| 6 | USR-005 | 회원탈퇴 비밀번호 불일치 | Missing implementation |
| 7 | AUTH-001 | 정상 로그인 | Implemented; related ApiIntegrationTest coverage |
| 8 | AUTH-002 | 비밀번호 불일치 | Implemented; related ApiIntegrationTest coverage |
| 9 | AUTH-003 | 존재하지 않는 이메일 로그인 | Implemented; related ApiIntegrationTest coverage |
| 10 | AUTH-004 | 정상 로그아웃 | Missing implementation |
| 11 | AUTH-005 | 정상 Access Token 재발급 | Missing implementation |
| 12 | AUTH-006 | 만료된 Refresh Token 사용 | Missing implementation |
| 13 | PR-001 | 정상 프로필 수정 | Missing implementation |
| 14 | PR-002 | 일부 필드만 프로필 수정 | Missing implementation |
| 15 | PR-003 | 중복된 닉네임으로 프로필 수정 | Missing implementation |
| 16 | PR-004 | 정상 프로필 조회 | Missing implementation |
| 17 | PR-005 | 존재하지 않는 프로필 조회 | Missing implementation |
| 18 | TC-EM01-01 | 운동 매칭 정상 생성 | Partial: persistence/OWNER tested; facility prerequisite not enforced |
| 19 | TC-EM01-02 | 운동 매칭 생성 입력값 오류 | Implemented validation; exact four variants and no-write assertions not all covered |
| 20 | TC-EM01-03 | 운동 매칭 생성 인증 실패 | Implemented auth guard; related API/JWT tests, exact expired-token/no-write scenario not fully covered |
| 21 | TC-EM01-04 | 존재하지 않는 체육서비스로 운동 매칭 생성 | Mismatch: service lookup absent, required 404 not implemented |
| 22 | TC-EM02-01 | 전체 운동 매칭 목록 조회 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 23 | TC-EM02-02 | 조건별 운동 매칭 목록 조회 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 24 | TC-EM02-03 | 제목 키워드 검색 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 25 | TC-EM02-04 | 설명 키워드 검색 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 26 | TC-EM02-05 | 매칭 시작·종료 시간 조건 조회 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 27 | TC-EM02-06 | 복수 검색 조건 조합 조회 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 28 | TC-EM02-07 | 검색 결과가 없는 경우 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 29 | TC-EM02-08 | 잘못된 검색 조건 입력 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 30 | TC-EM02-09 | 유효하지 않은 시간 범위 입력 | Implemented / source; related search integration coverage, not one-to-one case execution |
| 31 | EM05-002 | 존재하지 않는 매치 삭제 | Missing implementation |
| 32 | EM05-003 | 다른 사용자의 매치 삭제 차단 | Missing implementation |
| 33 | EM05-004 | 비로그인 상태에서 매치 삭제 차단 | Authentication guard present / source; endpoint business logic is still a stub |
| 34 | EM05-005 | 참가자가 있는 매치 삭제 | Missing implementation |
| 35 | EM06-001 | 운동 매칭 정상 참여 | Missing implementation |
| 36 | EM06-002 | 중복 참여 차단 | Missing implementation |
| 37 | EM06-003 | 생성자의 재참여 차단 | Missing implementation |
| 38 | EM06-004 | 정원이 가득 찬 매치 참여 차단 | Missing implementation |
| 39 | EM06-005 | 모집 마감된 매치 참여 차단 | Missing implementation |
| 40 | EM06-006 | 존재하지 않는 매치 참여 | Missing implementation |
| 41 | EM06-007 | 비로그인 상태에서 참여 차단 | Authentication guard present / source; endpoint business logic is still a stub |
| 42 | EM07-001 | 운동 매칭 정상 탈퇴 | Missing implementation |
| 43 | EM07-002 | 경기 시작 이후 일반 참가자 탈퇴 | Missing implementation |
| 44 | EM07-003 | 생성자 탈퇴 차단 | Missing implementation |
| 45 | EM07-004 | 미참여 사용자의 탈퇴 | Missing implementation |
| 46 | EM07-005 | 존재하지 않는 매치 탈퇴 | Missing implementation |
| 47 | EM07-006 | 비로그인 상태에서 탈퇴 차단 | Authentication guard present / source; endpoint business logic is still a stub |
| 48 | FC01-02 | 조건별 체육시설 목록 조회 | Missing implementation |
| 49 | FC01-03 | 조건에 부합하는 체육시설이 없는 경우 | Missing implementation |
| 50 | FC01-04 | 잘못된 형식의 검색 조건 입력 | Missing implementation |
| 51 | FC01-05 | 공공데이터 Open API 연동 오류 시 목록 조회 | Missing implementation |
| 52 | FC02-01 | 체육시설 상세 정상 조회 | Missing implementation |
| 53 | FC02-02 | 잘못된 형식의 serviceId 요청 | Missing implementation |
| 54 | FC02-03 | 존재하지 않는 체육시설 상세 조회 | Missing implementation |
| 55 | FC02-04 | 공공데이터 Open API 연동 오류 시 상세 조회 | Missing implementation |
| 56 | FC02-05 | 일부 정보 누락된 체육시설 상세 조회 | Missing implementation |
| 57 | AI-001 | 정상 운동 매칭 생성 | Missing implementation |
| 58 | AI-002 | 불완전한 자연어를 통한 매칭 생성 | Missing implementation |
| 59 | AI-003 | 정상 운동 매칭 검색 | Missing implementation |
| 60 | AI-004 | 검색 결과가 없는 조건 검색 | Missing implementation |
| 61 | AI-005 | 사용자 정보 기반 운동 매칭 추천 | Missing implementation |
| 62 | AI-006 | 사용자 정보 일부가 없는 경우 추천 | Missing implementation |
| 63 | AI-007 | 추천 가능한 매칭이 없는 경우 | Missing implementation |

## Recommended next work

1. Fix facility validation (or explicitly defer that dependency in the agreed MVP) and signup list handling.
2. Align canonical request fields, UI search filters, nullability and ID types with the team.
3. Add exact spreadsheet-case tests for implemented behavior: each invalid-create variant, nonexistent service, unfiltered/empty searches, malformed dates/enums/numbers, and no-write assertions on rejection.
4. Implement the missing endpoints/screens before claiming complete MVP or browser E2E coverage.

The 11 passing integration tests establish selected implemented behavior, not whole-spec conformance. No new test run was performed for this document review.
