#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "${ROOT_DIR}/docker-compose.middleware.yml")

if [[ -z "${HOTKEY_PUBLIC_IP:-}" ]]; then
  HOTKEY_PUBLIC_IP="$(hostname -I | awk '{print $1}')"
fi

export HOTKEY_PUBLIC_IP

echo "Using HOTKEY_PUBLIC_IP=${HOTKEY_PUBLIC_IP}"

if [[ "$#" -eq 0 ]]; then
  mapfile -t REQUESTED_SERVICES < <("${COMPOSE[@]}" config --services)
else
  REQUESTED_SERVICES=("$@")
fi

BASE_SERVICES=()
HOTKEY_SERVICES=()

for service in "${REQUESTED_SERVICES[@]}"; do
  if [[ "${service}" == hotkey-* ]]; then
    HOTKEY_SERVICES+=("${service}")
  else
    BASE_SERVICES+=("${service}")
  fi
done

if [[ "${#HOTKEY_SERVICES[@]}" -gt 0 ]]; then
  for dep in mysql mysql-extra-init etcd; do
    if [[ ! " ${BASE_SERVICES[*]} " =~ " ${dep} " ]]; then
      BASE_SERVICES+=("${dep}")
    fi
  done
fi

if [[ "${#BASE_SERVICES[@]}" -gt 0 ]]; then
  "${COMPOSE[@]}" up -d --build "${BASE_SERVICES[@]}"
fi

if [[ " ${BASE_SERVICES[*]} " =~ " mysql-extra-init " ]]; then
  docker wait project-mysql-extra-init-1 >/dev/null 2>&1 || true
fi

if [[ "${#HOTKEY_SERVICES[@]}" -gt 0 ]]; then
  "${COMPOSE[@]}" build "${HOTKEY_SERVICES[@]}"
  "${COMPOSE[@]}" create "${HOTKEY_SERVICES[@]}"
  "${COMPOSE[@]}" start "${HOTKEY_SERVICES[@]}"
fi
