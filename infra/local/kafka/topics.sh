#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-$ROOT/infra/local/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/infra/local/docker-compose.yml}"

[[ -f "$ENV_FILE" ]] || { echo "Kafka env file not found: $ENV_FILE" >&2; exit 1; }
[[ -f "$COMPOSE_FILE" ]] || { echo "Kafka Compose file not found: $COMPOSE_FILE" >&2; exit 1; }

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

# Keep this manifest synchronized with libs/contracts/.../Topics.java.
# Legacy aliases are intentionally not created here; code topic constants are the
# only source of truth for application event topics.
#
# Main event topics use the project's existing six-partition convention. The
# explicit flash-sale outbox DLQs retain three partitions because they are only
# used for low-throughput terminal failures. Generic consumer DLTs use six
# partitions: common-kafka preserves the source partition when publishing to
# "<source-topic>.DLT".
TOPICS=(
  "catalog.sku-upserted.v1:6"
  "inventory.flashsale-reserved.v1:6"
  "inventory.flashsale-reserved.dlq.v1:3"
  "inventory.flashsale-reserved.v2:6"
  "inventory.flashsale-reserved.dlq.v2:3"
  "order.timeout-scheduled.v1:6"
  "order.cancelled.v1:6"
  "inventory.release-requested.v1:6"
  "payment.succeeded.v1:6"
  "order.paid.v1:6"

  "catalog.sku-upserted.v1.DLT:6"
  "inventory.flashsale-reserved.v2.DLT:6"
  "order.timeout-scheduled.v1.DLT:6"
  "order.cancelled.v1.DLT:6"
  "inventory.release-requested.v1.DLT:6"
  "payment.succeeded.v1.DLT:6"
)

for entry in "${TOPICS[@]}"; do
  IFS=":" read -r topic partitions <<<"$entry"
  echo "Ensuring topic $topic (partitions=$partitions)"
  # Confluent Platform 7.6 exposes kafka-topics without the legacy .sh suffix.
  "${COMPOSE[@]}" exec -T kafka kafka-topics \
    --bootstrap-server kafka:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --replication-factor 1 \
    --partitions "$partitions"
done

