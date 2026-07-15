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

kafka_diagnostics() {
  local state log_path
  state="$(docker inspect --format '{{.State.Status}}|{{.RestartCount}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' kafka 2>/dev/null || true)"
  log_path="$(docker inspect --format '{{.LogPath}}' kafka 2>/dev/null || true)"
  echo "Kafka container state: ${state:-not found}" >&2
  echo "Docker log path: ${log_path:-unknown}" >&2
  echo "Kafka log command: docker compose --env-file infra/local/.env -f infra/local/docker-compose.yml logs kafka --tail 150" >&2
  "${COMPOSE[@]}" logs kafka --tail 150 >&2 || true
}

wait_kafka_ready() {
  local state status restarts health
  for _ in $(seq 1 60); do
    state="$(docker inspect --format '{{.State.Status}}|{{.RestartCount}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' kafka 2>/dev/null || true)"
    IFS='|' read -r status restarts health <<<"$state"
    if [[ "$status" == "restarting" || "$status" == "exited" || "$status" == "dead" || "${restarts:-0}" -gt 0 ]]; then
      kafka_diagnostics
      echo "Kafka container crashed before becoming ready." >&2
      return 1
    fi
    if [[ "$status" == "running" && "$health" == "healthy" ]] && (echo >/dev/tcp/localhost/29092) 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  kafka_diagnostics
  echo "Timed out waiting for Kafka health and localhost:29092 after 120 seconds." >&2
  return 1
}

initialize_kafka_topics() {
  local attempt
  for attempt in $(seq 1 5); do
    if bash "$ROOT/infra/local/kafka/topics.sh"; then return 0; fi
    echo "Kafka topic initialization attempt $attempt/5 failed; retrying in 3 seconds..." >&2
    sleep 3
  done
  kafka_diagnostics
  echo "Kafka topic initialization failed after 5 attempts. See the Kafka logs above." >&2
  return 1
}

if [[ "$MODE" != "--services-only" ]]; then
  "${COMPOSE[@]}" up -d spanner kafka redis elasticsearch
  wait_port localhost 9010 Spanner
  wait_kafka_ready
  wait_port localhost 6379 Redis
  wait_port localhost 9200 Elasticsearch
  "${COMPOSE[@]}" run --rm --entrypoint bash spanner-tools spanner/bootstrap.sh
  initialize_kafka_topics
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
    if curl --fail --silent "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'; then
      echo "$name is already running and healthy"
      return
    fi
    echo "$name has a live PID but no healthy endpoint; restarting it." >&2
    kill "$(cat "$PID_DIR/$name.pid")" 2>/dev/null || true
    rm -f "$PID_DIR/$name.pid"
  fi
  local log="$LOG_DIR/$name-$(date +%Y%m%d%H%M%S).log"
  nohup "$MVN" -q -pl "$module" org.springframework.boot:spring-boot-maven-plugin:3.5.9:run \
    -Dspring-boot.run.profiles=local >"$log" 2>&1 &
  echo $! >"$PID_DIR/$name.pid"
  local healthy=0 deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if curl --fail --silent "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'; then
      healthy=$((healthy + 1))
    else
      healthy=0
    fi
    [[ "$healthy" -ge 3 ]] && return 0
    kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null || break
    sleep 1
  done
  echo "$name did not reach three consecutive healthy responses. Startup log: $log" >&2
  tail -n 100 "$log" >&2 || true
  return 1
}

start_service services/catalog-service catalog-service 8080
start_service services/search-service search-service 8081
start_service services/inventory-service inventory-service 8082
start_service services/order-service order-service 8083
start_service services/payment-service payment-service 8084

echo "Core services are ready. Run scripts/smoke-local.sh for the minimal transaction smoke test."
