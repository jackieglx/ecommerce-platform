#!/usr/bin/env bash
set -euo pipefail

PHASE="${1:-}"
RUN_ID="${RUN_ID:-${2:-}}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"
[[ "$PHASE" == before || "$PHASE" == after ]] || { echo 'Usage: RUN_ID=... ./scripts/verify-load-results.sh before|after' >&2; exit 1; }
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$ ]] || { echo 'RUN_ID is required and invalid.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq is required.' >&2; exit 1; }
command -v docker >/dev/null || { echo 'docker is required.' >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/infra/local/docker-compose.yml"
COMPOSE_ENV="$ROOT/infra/local/.env"
RESULT_DIR="$ROOT/perf/results/$RUN_ID"
PARAMETERS="$RESULT_DIR/parameters.json"
[[ -f "$PARAMETERS" ]] || { echo "Run prepare-load-data first; missing $PARAMETERS" >&2; exit 1; }

COMPOSE=(docker compose)
[[ -f "$COMPOSE_ENV" ]] && COMPOSE+=(--env-file "$COMPOSE_ENV")
COMPOSE+=(-f "$COMPOSE_FILE")

spanner_query() {
  local sql="$1"
  "${COMPOSE[@]}" exec -T \
    -e SPANNER_EMULATOR_HOST=spanner:9010 \
    -e CLOUDSDK_AUTH_DISABLE_CREDENTIALS=true \
    -e CLOUDSDK_CORE_PROJECT=local-project \
    -e CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER=http://spanner:9020/ \
    spanner-tools gcloud spanner databases execute-sql local-db \
    --instance=local-instance --sql="$sql" --format=json --quiet
}

redis_raw() {
  "${COMPOSE[@]}" exec -T redis redis-cli --raw "$@" | tr -d '\r'
}

kafka_state() {
  local output lag partitions
  output="$("${COMPOSE[@]}" exec -T kafka kafka-consumer-groups --bootstrap-server kafka:9092 --describe --group order-flashsale-v2)"
  lag="$(awk '$2 == "inventory.flashsale-reserved.v2" {sum += $6} END {print sum + 0}' <<<"$output")"
  partitions="$(awk '$2 == "inventory.flashsale-reserved.v2" {count++} END {print count + 0}' <<<"$output")"
  (( partitions > 0 )) || { echo "Kafka group order-flashsale-v2 returned no topic partitions: $output" >&2; return 1; }
  jq -cn --arg group order-flashsale-v2 --arg topic inventory.flashsale-reserved.v2 --argjson partitions "$partitions" --argjson lag "$lag" \
    '{group:$group,topic:$topic,partitions:$partitions,totalLag:$lag}'
}

snapshot() {
  local sku_file stream_file sku entry shard tag stock_key redis_stock inventory stream_key group length groups group_data pending lag
  sku_file="$(mktemp)"; stream_file="$(mktemp)"
  declare -A seen_streams=()

  while IFS= read -r sku; do
    entry="$(jq -c --arg sku "$sku" '.skuInventory[] | select(.skuId == $sku)' "$PARAMETERS")"
    [[ -n "$entry" ]] || { echo "No prepared SKU metadata for $sku" >&2; return 1; }
    shard="$(jq -r '.streamShard' <<<"$entry")"; tag="{A100:$shard}"
    stock_key="fs:stock:${tag}:sku:${sku}"; redis_stock="$(redis_raw GET "$stock_key")"
    [[ "$redis_stock" =~ ^-?[0-9]+$ ]] || { echo "Invalid Redis stock for $sku: '$redis_stock'" >&2; return 1; }
    inventory="$(spanner_query "SELECT OnHand AS onHand, Reserved AS reserved, OnHand - Reserved AS available FROM Inventory WHERE SkuId = '$sku'")"
    [[ "$(jq 'length' <<<"$inventory")" == 1 ]] || { echo "Inventory row not found for $sku" >&2; return 1; }
    jq -cn --arg sku "$sku" --argjson redis "$redis_stock" --argjson row "$(jq '.[0]' <<<"$inventory")" \
      '{skuId:$sku,redisStock:$redis,spannerOnHand:($row.onHand|tonumber),spannerReserved:($row.reserved|tonumber),spannerAvailable:($row.available|tonumber)}' >> "$sku_file"

    stream_key="$(jq -r '.streamKey' <<<"$entry")"
    if [[ -z "${seen_streams[$stream_key]:-}" ]]; then
      seen_streams[$stream_key]=1; group="fs-publisher:$shard"; length="$(redis_raw XLEN "$stream_key")"
      groups="$("${COMPOSE[@]}" exec -T redis redis-cli --json XINFO GROUPS "$stream_key" | tr -d '\r')"
      group_data="$(jq -c --arg group "$group" 'map([range(0; length; 2) as $i | {key: .[$i], value: .[$i+1]}] | from_entries) | map(select(.name == $group)) | first // null' <<<"$groups")"
      if [[ "$group_data" == null ]]; then pending=0; lag=0; found=false; else pending="$(jq -r '.pending' <<<"$group_data")"; lag="$(jq -r '.lag // 0' <<<"$group_data")"; found=true; fi
      jq -cn --arg stream "$stream_key" --arg group "$group" --argjson found "$found" --argjson length "$length" --argjson pending "$pending" --argjson lag "$lag" \
        '{streamKey:$stream,group:$group,groupFound:$found,length:$length,pending:$pending,lag:$lag}' >> "$stream_file"
    fi
  done < <(jq -r '.activeSkuIds[]' "$PARAMETERS")

  local orders kafka captured
  orders="$(spanner_query "SELECT COUNT(*) AS totalOrders, COUNTIF(STARTS_WITH(UserId, 'load-$RUN_ID-')) AS runOrders FROM Orders" | jq '.[0] | {totalOrders:(.totalOrders|tonumber),runOrders:(.runOrders|tonumber)}')"
  kafka="$(kafka_state)"; captured="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local result
  result="$(jq -sn --arg captured "$captured" --slurpfile sku "$sku_file" --slurpfile streams "$stream_file" --argjson orders "$orders" --argjson kafka "$kafka" \
    '{capturedAt:$captured,skuStates:$sku,totalRedisStock:([$sku[].redisStock]|add//0),totalSpannerAvailable:([$sku[].spannerAvailable]|add//0),orders:$orders,redisStreams:$streams,kafka:$kafka}'
  )"
  rm -f "$sku_file" "$stream_file"
  printf '%s\n' "$result"
}

if [[ "$PHASE" == before ]]; then
  state="$(snapshot)"; started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  run_orders="$(jq '.orders.runOrders' <<<"$state")"; redis_stock="$(jq '.totalRedisStock' <<<"$state")"; spanner_stock="$(jq '.totalSpannerAvailable' <<<"$state")"; prepared_stock="$(jq '.preparedTestStock' "$PARAMETERS")"
  (( run_orders == 0 )) || { echo "RUN_ID '$RUN_ID' already has $run_orders orders. Use a new RUN_ID." >&2; exit 1; }
  (( redis_stock == prepared_stock && spanner_stock == prepared_stock )) || { echo "Prepared stock mismatch: expected $prepared_stock, Redis=$redis_stock, Spanner=$spanner_stock" >&2; exit 1; }
  jq -n --arg run "$RUN_ID" --arg started "$started" --argjson state "$state" '{runId:$run,testStartedAt:$started,state:$state}' > "$RESULT_DIR/baseline.json"
  jq -r '"Baseline recorded: RedisStock=\(.state.totalRedisStock) SpannerAvailable=\(.state.totalSpannerAvailable) Orders=\(.state.orders.totalOrders) RunOrders=\(.state.orders.runOrders) KafkaLag=\(.state.kafka.totalLag)"' "$RESULT_DIR/baseline.json"
  exit 0
fi

BASELINE="$RESULT_DIR/baseline.json"; K6_RESULT="$RESULT_DIR/k6-result.json"
[[ -f "$BASELINE" ]] || { echo 'Run the before phase before load testing; baseline.json is missing.' >&2; exit 1; }
[[ -f "$K6_RESULT" ]] || { echo 'k6-result.json is missing; the k6 run did not produce its machine-readable result.' >&2; exit 1; }

reserved="$(jq -r '.businessResults.reserved' "$K6_RESULT")"
verification_start_epoch="$(date +%s)"; deadline=$((verification_start_epoch + DRAIN_TIMEOUT_SECONDS)); drained=false
while :; do
  final="$(snapshot)"
  redis_lag="$(jq '[.redisStreams[].lag]|add//0' <<<"$final")"; redis_pending="$(jq '[.redisStreams[].pending]|add//0' <<<"$final")"
  kafka_lag="$(jq '.kafka.totalLag' <<<"$final")"
  baseline_run_orders="$(jq '.state.orders.runOrders' "$BASELINE")"; run_orders="$(jq '.orders.runOrders' <<<"$final")"; new_orders=$((run_orders - baseline_run_orders))
  echo "Drain: RedisLag=$redis_lag RedisPending=$redis_pending KafkaLag=$kafka_lag NewOrders=$new_orders/$reserved" >&2
  if (( redis_lag == 0 && redis_pending == 0 && kafka_lag == 0 && new_orders == reserved )); then drained=true; break; fi
  (( $(date +%s) < deadline )) || break
  sleep "$POLL_INTERVAL_SECONDS"
done

finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
load_finished_epoch="$(jq -r '.generatedAt | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601' "$K6_RESULT")"
drain_time=$(( $(date +%s) - load_finished_epoch ))
attempts="$(jq '.throughput.attempts' "$K6_RESULT")"; prepared_stock="$(jq '.preparedTestStock' "$PARAMETERS")"
expected_reserved="$attempts"; (( expected_reserved > prepared_stock )) && expected_reserved="$prepared_stock"
initial_redis="$(jq '.state.totalRedisStock' "$BASELINE")"; final_redis="$(jq '.totalRedisStock' <<<"$final")"
initial_spanner="$(jq '.state.totalSpannerAvailable' "$BASELINE")"; final_spanner="$(jq '.totalSpannerAvailable' <<<"$final")"
oversold=false; (( final_redis < 0 || final_spanner < 0 || reserved > prepared_stock )) && oversold=true

jq -n --arg run "$RUN_ID" --arg finished "$finished" --argjson drainTime "$drain_time" --argjson drained "$drained" \
  --argjson initialRedis "$initial_redis" --argjson finalRedis "$final_redis" --argjson initialSpanner "$initial_spanner" --argjson finalSpanner "$final_spanner" \
  --argjson oversold "$oversold" --argjson reserved "$reserved" --argjson expected "$expected_reserved" --argjson newOrders "$new_orders" \
  --argjson final "$final" --slurpfile baseline "$BASELINE" --slurpfile k6 "$K6_RESULT" --slurpfile params "$PARAMETERS" '
  . as $root | ($k6[0]) as $k | ($params[0]) as $p |
  {runId:$run,testStartedAt:$baseline[0].testStartedAt,verificationFinishedAt:$finished,drainTimeSeconds:$drainTime,drainedWithinTimeout:$drained,
   initialRedisStock:$initialRedis,finalRedisStock:$finalRedis,initialSpannerAvailable:$initialSpanner,finalSpannerAvailable:$finalSpanner,
   oversold:$oversold,reserved:$reserved,expectedReserved:$expected,newOrders:$newOrders,finalState:$final,k6:$k,
   checks:{droppedIterationsZero:($k.throughput.droppedIterations==0),httpTechnicalErrorBelowPointOnePercent:($k.httpTechnicalErrorRate<0.001),
    duplicateZero:($k.businessResults.duplicate==0),failedZero:($k.businessResults.failed==0),
    parseUnknownContractErrorsZero:(($k.businessResults.parseError+$k.businessResults.unknown+$k.businessResults.contractError)==0),
    expectedReserved:($reserved==$expected),limitedStockExhaustedWhenEnoughAttempts:(($p.testProfile!="limited_stock") or ($k.throughput.attempts<$p.preparedTestStock) or (($reserved==$p.preparedTestStock) and ($finalRedis==0))),
    noOversell:($oversold|not),asyncOrdersConverged:($newOrders==$reserved),backlogDrained:$drained}} |
   .passed=([.checks[]]|all)
  ' > "$RESULT_DIR/verification.json"

jq -r '
"# Inventory load test result: \(.runId)\n
- Result: \(if .passed then "PASS" else "FAIL" end)
- Target / actual sent / completed QPS: \(.k6.throughput.targetQps) / \(.k6.throughput.actualSentQps) / \(.k6.throughput.actualCompletedQps)
- RESERVED QPS: \(.k6.throughput.reservedQps)
- RESERVED / SOLD_OUT / DUPLICATE / FAILED: \(.reserved) / \(.k6.businessResults.soldOut) / \(.k6.businessResults.duplicate) / \(.k6.businessResults.failed)
- Initial / final Redis stock: \(.initialRedisStock) / \(.finalRedisStock)
- Initial / final Spanner available: \(.initialSpannerAvailable) / \(.finalSpannerAvailable)
- Oversold: \(.oversold)
- New orders / RESERVED: \(.newOrders) / \(.reserved)
- Backlog drained: \(.drainedWithinTimeout)
- Verification drain wait: \(.drainTimeSeconds) seconds
- Dropped iterations: \(.k6.throughput.droppedIterations)
- HTTP technical error rate: \(.k6.httpTechnicalErrorRate * 100)%
- Overall p50/p95/p99: \(.k6.latency.overall.p50Ms) / \(.k6.latency.overall.p95Ms) / \(.k6.latency.overall.p99Ms) ms
- RESERVED p50/p95/p99: \(.k6.latency.reserved.p50Ms) / \(.k6.latency.reserved.p95Ms) / \(.k6.latency.reserved.p99Ms) ms
- SOLD_OUT p50/p95/p99: \(.k6.latency.soldOut.p50Ms) / \(.k6.latency.soldOut.p95Ms) / \(.k6.latency.soldOut.p99Ms) ms"
' "$RESULT_DIR/verification.json" > "$RESULT_DIR/report.md"

if jq -e '.passed' "$RESULT_DIR/verification.json" >/dev/null; then echo "Verification PASSED: $RESULT_DIR/report.md"; else echo "Verification FAILED: $RESULT_DIR/report.md" >&2; exit 1; fi
