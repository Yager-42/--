#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

queue_messages() {
  curl --silent --fail -u guest:guest \
    "http://127.0.0.1:15672/api/queues/%2f/search.cdc.raw.queue" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["messages"])'
}

meta_state() {
  docker exec project-canal-server-1 sh -lc \
    'cat /home/admin/canal-server/conf/search_cdc_raw/meta.dat'
}

master_state() {
  docker exec project-mysql-1 mysql -N -uroot -proot \
    -e "SHOW BINARY LOG STATUS"
}

post_id="${1:-}"
if [[ -z "${post_id}" ]]; then
  post_id="$(
    docker exec project-mysql-1 mysql -N -uroot -proot -D nexus_social \
      -e "select post_id from content_post limit 1" | head -n 1
  )"
fi

if [[ -z "${post_id}" ]]; then
  echo "NO_POST_ID"
  exit 0
fi

before="$(queue_messages)"
before_meta="$(meta_state)"
before_master="$(master_state)"
docker exec project-mysql-1 mysql -uroot -proot -D nexus_social \
  -e "update content_post set is_edited = 1 - is_edited where post_id = ${post_id}" >/dev/null
sleep 8
after="$(queue_messages)"
after_meta="$(meta_state)"
after_master="$(master_state)"

echo "BEFORE=${before}"
echo "BEFORE_MASTER=${before_master}"
echo "BEFORE_META=${before_meta}"
echo "POST_ID=${post_id}"
echo "AFTER=${after}"
echo "AFTER_MASTER=${after_master}"
echo "AFTER_META=${after_meta}"
