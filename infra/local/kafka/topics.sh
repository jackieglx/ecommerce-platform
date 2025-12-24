#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-$ROOT/.env}"
if [[ ! -f "$ENV_FILE" && -f "$ROOT/env.example" ]]; then
  ENV_FILE="$ROOT/env.example"
fi

COMPOSE="docker compose --env-file $ENV_FILE -f docker-compose.yml"

TOPICS=(
  "order-lifecycle-events:6"
  "sales-item-events:6"
  "order-timeout-schedule:6"
  "order-timeout-dlt:3"
  "inventory.flashsale-reserved.v1:6"
  "inventory.flashsale-reserved.dlq.v1:3"
  "inventory.flashsale-reserved.v2:6"
  "inventory.flashsale-reserved.dlq.v2:3"
)

for entry in "${TOPICS[@]}"; do
  IFS=":" read -r topic partitions <<<"$entry"
  echo "Ensuring topic $topic (partitions=$partitions)"
  $COMPOSE exec -T kafka kafka-topics.sh \
    --bootstrap-server kafka:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --replication-factor 1 \
    --partitions "$partitions"
done

