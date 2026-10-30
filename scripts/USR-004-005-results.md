# USR-004 / USR-005 live API test

Test account userId=7; login HTTP 200; initial status=ACTIVE, withdrawn_at=NULL

USR-005 PASS: HTTP 400 body={"message":"비밀번호가 일치하지 않습니다.","code":"PASSWORD_MISMATCH"}; status=ACTIVE, withdrawn_at=null; status/date/updated_at unchanged=true

USR-004 PASS: HTTP 204; status=WITHDRAWN, withdrawn_at=2026-09-29 13:02:23.300000

