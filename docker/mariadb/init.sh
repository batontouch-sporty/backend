#!/bin/sh
# SQL 파일은 환경변수를 읽지 못해서 sed로 비밀번호를 넣어 실행한다.
# 컨테이너 진입점이 이 파일을 source할 수도 있어서 성공 경로에는 exit를 두지 않는다.
for name in USER_APP_PASSWORD MATCH_APP_PASSWORD FACILITY_APP_PASSWORD NOTIFICATION_APP_PASSWORD; do
  eval "value=\${$name:-}"
  if [ -z "$value" ]; then
    echo "$name 값이 없다" >&2
    exit 1
  fi
  case "$value" in
    *[\|\&\\\'\$\#[:space:]]*)
      echo "$name 에 쓸 수 없는 문자가 있다" >&2
      exit 1
      ;;
  esac
done
unset name value

rendered=$(mktemp) || exit 1
status=0
if sed \
  -e "s|\${USER_APP_PASSWORD}|$USER_APP_PASSWORD|" \
  -e "s|\${MATCH_APP_PASSWORD}|$MATCH_APP_PASSWORD|" \
  -e "s|\${FACILITY_APP_PASSWORD}|$FACILITY_APP_PASSWORD|" \
  -e "s|\${NOTIFICATION_APP_PASSWORD}|$NOTIFICATION_APP_PASSWORD|" \
  /init/init.sql > "$rendered" \
  && MYSQL_PWD="$MARIADB_ROOT_PASSWORD" mariadb -uroot < "$rendered" \
  && MYSQL_PWD="$MARIADB_ROOT_PASSWORD" mariadb -uroot --default-character-set=utf8mb4 < /init/schema.sql; then
  :
else
  status=$?
fi
rm -f "$rendered"
if [ "$status" -ne 0 ]; then
  exit "$status"
fi
