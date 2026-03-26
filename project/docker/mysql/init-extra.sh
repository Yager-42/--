#!/usr/bin/env bash
set -euo pipefail

MYSQL_CMD=(mysql -hmysql -uroot -proot)

echo "Configuring MySQL for Gorse and HotKey..."
"${MYSQL_CMD[@]}" -e "SET GLOBAL sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';"
"${MYSQL_CMD[@]}" -e "CREATE DATABASE IF NOT EXISTS gorse DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
"${MYSQL_CMD[@]}" -e "CREATE DATABASE IF NOT EXISTS hotkey_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

HOTKEY_SCHEMA_EXISTS=$("${MYSQL_CMD[@]}" -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'hotkey_db' AND table_name = 'hk_user';")
if [ "${HOTKEY_SCHEMA_EXISTS}" = "0" ]; then
  echo "Initializing hotkey_db schema..."
  "${MYSQL_CMD[@]}" < /schema/hotkey-schema.sql
else
  echo "hotkey_db schema already exists, skip initialization."
fi

exit 0
