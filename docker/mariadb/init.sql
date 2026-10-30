-- ${...} 자리는 init.sh가 환경변수로 채운다.
CREATE DATABASE IF NOT EXISTS `user_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `match_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `facility_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `notification_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'user_app'@'%' IDENTIFIED BY '${USER_APP_PASSWORD}';
CREATE USER IF NOT EXISTS 'match_app'@'%' IDENTIFIED BY '${MATCH_APP_PASSWORD}';
CREATE USER IF NOT EXISTS 'facility_app'@'%' IDENTIFIED BY '${FACILITY_APP_PASSWORD}';
CREATE USER IF NOT EXISTS 'notification_app'@'%' IDENTIFIED BY '${NOTIFICATION_APP_PASSWORD}';

GRANT ALL PRIVILEGES ON `user_db`.* TO 'user_app'@'%';
GRANT ALL PRIVILEGES ON `match_db`.* TO 'match_app'@'%';
GRANT ALL PRIVILEGES ON `facility_db`.* TO 'facility_app'@'%';
GRANT ALL PRIVILEGES ON `notification_db`.* TO 'notification_app'@'%';
