#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for pid_file in "$ROOT"/.local/pids/*.pid; do
  [[ -e "$pid_file" ]] || continue
  pid="$(cat "$pid_file")"
  if command -v taskkill.exe >/dev/null 2>&1; then
    taskkill.exe //PID "$pid" //T //F >/dev/null 2>&1 || true
  else
    kill "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
done
docker compose --env-file "$ROOT/infra/local/.env" -f "$ROOT/infra/local/docker-compose.yml" down
