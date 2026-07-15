#!/usr/bin/env bash
set -euo pipefail

# Starts Dockerized dependencies and the five core Java services on the host.
# Usage: scripts/run-local.sh [--deps-only|--services-only]

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"
COMPOSE=(docker compose --env-file "$ROOT/infra/local/.env" -f "$ROOT/infra/local/docker-compose.yml")
MVN="${MVN:-mvn}"
LOG_DIR="$ROOT/.local/logs"
PID_DIR="$ROOT/.local/pids"

case "$MODE" in all|--deps-only|--services-only) ;; *) echo "Usage: $0 [--deps-only|--services-only]" >&2; exit 2;; esac

wait_port() {
  local host="$1" port="$2" name="$3"; local deadline=$((SECONDS + 120))
  until (echo >/dev/tcp/"$host"/"$port") 2>/dev/null; do
    (( SECONDS < deadline )) || { echo "Timed out waiting for $name" >&2; exit 1; }
    sleep 1
  done
}

if [[ "$MODE" != "--services-only" ]]; then
  "${COMPOSE[@]}" up -d spanner zookeeper kafka redis elasticsearch
  wait_port localhost 9010 Spanner
  wait_port localhost 29092 Kafka
  wait_port localhost 6379 Redis
  wait_port localhost 9200 Elasticsearch
  "${COMPOSE[@]}" run --rm --entrypoint bash spanner-tools spanner/bootstrap.sh
  bash "$ROOT/infra/local/kafka/topics.sh"
fi

if [[ "$MODE" == "--deps-only" ]]; then
  echo "Dependencies are ready. Run $0 --services-only to start Java services."
  exit 0
fi

export SPANNER_PROJECT=local-project SPANNER_INSTANCE=local-instance SPANNER_DATABASE=local-db
export SPANNER_EMULATOR_HOST=localhost:9010 SPANNER_EMULATOR_ENABLED=true
export KAFKA_BOOTSTRAP_SERVERS=localhost:29092 REDIS_HOST=localhost REDIS_PORT=6379
export ELASTICSEARCH_URL=http://localhost:9200 CATALOG_BASE_URL=http://localhost:8080
export SPRING_PROFILES_ACTIVE=local
export SPRING_AUTOCONFIGURE_EXCLUDE=com.google.cloud.spring.autoconfigure.spanner.GcpSpannerAutoConfiguration,com.google.cloud.spring.autoconfigure.spanner.SpannerTransactionManagerAutoConfiguration

mkdir -p "$LOG_DIR" "$PID_DIR"
"$MVN" -q -DskipTests install

start_service() {
  local module="$1" name="$2" port="$3"
  if [[ -f "$PID_DIR/$name.pid" ]] && kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null; then
    echo "$name is already running"
    return
  fi
  nohup "$MVN" -q -pl "$module" org.springframework.boot:spring-boot-maven-plugin:3.5.9:run \
    -Dspring-boot.run.profiles=local >"$LOG_DIR/$name-$(date +%Y%m%d%H%M%S).log" 2>&1 &
  echo $! >"$PID_DIR/$name.pid"
  wait_port localhost "$port" "$name"
  curl --fail --silent "http://localhost:$port/actuator/health" >/dev/null
}

start_service services/catalog-service catalog-service 8080
start_service services/search-service search-service 8081
start_service services/inventory-service inventory-service 8082
start_service services/order-service order-service 8083
start_service services/payment-service payment-service 8084

echo "Core services are ready. Run scripts/smoke-local.sh for the minimal transaction smoke test."
