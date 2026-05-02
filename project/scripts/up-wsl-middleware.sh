#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "${ROOT_DIR}/docker-compose.middleware.yml")

BUILD_FLAG=""
ORPHAN_FLAG=""
SERVICE_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --build)
      BUILD_FLAG="--build"
      ;;
    --remove-orphans)
      ORPHAN_FLAG="--remove-orphans"
      ;;
    *)
      SERVICE_ARGS+=("$arg")
      ;;
  esac
done

if [[ "${#SERVICE_ARGS[@]}" -eq 0 ]]; then
  mapfile -t REQUESTED_SERVICES < <("${COMPOSE[@]}" config --services)
else
  REQUESTED_SERVICES=("${SERVICE_ARGS[@]}")
fi

"${COMPOSE[@]}" up -d $BUILD_FLAG $ORPHAN_FLAG "${REQUESTED_SERVICES[@]}"

if [[ " ${REQUESTED_SERVICES[*]} " =~ " mysql-extra-init " ]]; then
  docker wait project-mysql-extra-init-1 >/dev/null 2>&1 || true
fi

if [[ " ${REQUESTED_SERVICES[*]} " =~ " rabbitmq-init " ]]; then
  docker wait project-rabbitmq-init-1 >/dev/null 2>&1 || true
fi
