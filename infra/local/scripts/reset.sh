#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-$ROOT/.env}"
if [[ ! -f "$ENV_FILE" && -f "$ROOT/env.example" ]]; then
  ENV_FILE="$ROOT/env.example"
fi

docker compose --env-file "$ENV_FILE" -f docker-compose.yml down -v --remove-orphans
docker volume ls -q --filter "name=ecommerce-local" | xargs -r docker volume rm

