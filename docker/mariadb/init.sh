#!/bin/sh
# SQL 파일은 환경변수를 읽지 못해서 sed로 비밀번호를 넣어 실행한다.
# 비밀번호에는 sed·SQL에서 특수문자인 | & \ ' 를 쓰지 않는다.
sed \
  -e "s|\${USER_APP_PASSWORD}|${USER_APP_PASSWORD:?}|" \
  -e "s|\${MATCH_APP_PASSWORD}|${MATCH_APP_PASSWORD:?}|" \
  -e "s|\${FACILITY_APP_PASSWORD}|${FACILITY_APP_PASSWORD:?}|" \
  -e "s|\${NOTIFICATION_APP_PASSWORD}|${NOTIFICATION_APP_PASSWORD:?}|" \
  /init/init.sql | MYSQL_PWD="$MARIADB_ROOT_PASSWORD" mariadb -uroot
