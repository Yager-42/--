#!/usr/bin/env bash
set -euo pipefail

MYSQL_CMD=(mysql -hmysql -uroot -proot)

echo "Configuring MySQL for Gorse..."
"${MYSQL_CMD[@]}" -e "SET GLOBAL sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';"
"${MYSQL_CMD[@]}" nexus_social < /schema/nexus-final-schema.sql
if [ -f /schema/20260409_01_drop_legacy_reaction_tables.sql ]; then
  echo "Dropping legacy reaction tables..."
  "${MYSQL_CMD[@]}" nexus_social < /schema/20260409_01_drop_legacy_reaction_tables.sql
fi
"${MYSQL_CMD[@]}" -e "CREATE DATABASE IF NOT EXISTS gorse DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

exit 0
