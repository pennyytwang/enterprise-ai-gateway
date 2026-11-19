#!/bin/bash
# 建立 Gateway 與 ERP 兩個資料庫，以及應用/查詢帳號。
# 密碼由 docker-compose 的環境變數注入；僅供本機示範使用。
set -e

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
APP_USER="${DB_USER:?DB_USER is required}"
APP_PASS="${DB_PASSWORD:?DB_PASSWORD is required}"
RO_USER="${MYDB_USER:?MYDB_USER is required}"
RO_PASS="${MYDB_PASSWORD:?MYDB_PASSWORD is required}"

case "$APP_USER" in
  *[!A-Za-z0-9_]*|'') echo "invalid DB_USER" >&2; exit 1 ;;
esac
case "$RO_USER" in
  *[!A-Za-z0-9_]*|'') echo "invalid MYDB_USER" >&2; exit 1 ;;
esac

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
CREATE DATABASE IF NOT EXISTS aigateway CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mydb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASS}';
ALTER USER '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASS}';
GRANT ALL PRIVILEGES ON aigateway.* TO '${APP_USER}'@'%';
CREATE USER IF NOT EXISTS '${RO_USER}'@'%' IDENTIFIED BY '${RO_PASS}';
ALTER USER '${RO_USER}'@'%' IDENTIFIED BY '${RO_PASS}';
GRANT SELECT, INSERT, DELETE ON mydb.* TO '${RO_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
