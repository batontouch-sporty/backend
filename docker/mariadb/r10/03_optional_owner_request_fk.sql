-- 선택 사항(01·02에 포함하지 않음): 방장 참가 행의 request_id가 그 매치의 creation_request_id와 같도록 DB가 강제한다.
-- 방장 행의 request_id가 다르면 방장 보증금 반환이 서로 다른 키로 두 번 기록될 수 있어, 코드 규칙을 DB로도 막고 싶을 때만 적용한다.
-- 01(신규) 또는 02(변경) 적용 뒤에 실행한다. 물리 FK가 7개에서 8개가 된다.
-- 방장 행이 아닌 행은 owner_request_key가 NULL이라 검사하지 않는다.
ALTER TABLE match_db.matches
  ADD CONSTRAINT uk_matches_id_creation UNIQUE (id,creation_request_id);

ALTER TABLE match_db.match_participants
  ADD COLUMN owner_request_key VARCHAR(36) AS (CASE WHEN role = 'OWNER' THEN request_id END) PERSISTENT
    COMMENT '방장 행일 때만 request_id. matches.creation_request_id와 같아야 함',
  ADD CONSTRAINT fk_participants_owner_request FOREIGN KEY (match_id,owner_request_key)
    REFERENCES match_db.matches(id,creation_request_id) ON DELETE RESTRICT ON UPDATE RESTRICT;
