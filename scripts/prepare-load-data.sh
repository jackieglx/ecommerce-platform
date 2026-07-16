#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/infra/local/docker-compose.yml"
COMPOSE_ENV="$ROOT/infra/local/.env"
CATALOG_BASE_URL="${CATALOG_BASE_URL:-http://localhost:8080}"
INVENTORY_BASE_URL="${BASE_URL:-http://localhost:8082}"
RUN_ID="${RUN_ID:-}"
TEST_PROFILE="${TEST_PROFILE:-limited_stock}"
SKU_MODE="${SKU_MODE:-single}"
TARGET_RPS="${TARGET_RPS:-10}"
DURATION="${DURATION:-10s}"
INITIAL_STOCK="${INITIAL_STOCK:-100}"
SINGLE_SKU="loadtest-hot-sku-001"
WARMUP_SKU="loadtest-warmup-sku"
SHARDED_SKUS=(loadtest-shard-{00..07}-001)

[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$ ]] || { echo 'RUN_ID is required and must use 3-48 letters, digits, underscore, or hyphen.' >&2; exit 1; }
[[ "$TEST_PROFILE" == limited_stock || "$TEST_PROFILE" == success_capacity ]] || { echo "Unsupported TEST_PROFILE: $TEST_PROFILE" >&2; exit 1; }
[[ "$SKU_MODE" == single || "$SKU_MODE" == sharded ]] || { echo "Unsupported SKU_MODE: $SKU_MODE" >&2; exit 1; }
[[ "$TARGET_RPS" =~ ^[1-9][0-9]*$ ]] || { echo 'TARGET_RPS must be positive.' >&2; exit 1; }
[[ "$INITIAL_STOCK" =~ ^[1-9][0-9]*$ ]] || { echo 'INITIAL_STOCK must be positive.' >&2; exit 1; }
command -v curl >/dev/null || { echo 'curl is required.' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq is required.' >&2; exit 1; }
command -v docker >/dev/null || { echo 'docker is required.' >&2; exit 1; }

duration_seconds() {
  local value="$1" number unit
  [[ "$value" =~ ^([0-9]+)(s|m|h)$ ]] || { echo "DURATION must look like 30s, 2m, or 1h; got '$value'." >&2; return 1; }
  number="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
  (( number > 0 )) || { echo 'DURATION must be positive.' >&2; return 1; }
  case "$unit" in h) echo $((number * 3600));; m) echo $((number * 60));; *) echo "$number";; esac
}

java_hash() {
  local value="$1" hash=0 code i char
  LC_ALL=C
  for ((i=0; i<${#value}; i++)); do
    char="${value:i:1}"
    printf -v code '%d' "'$char"
    hash=$(( (31 * hash + code) & 0xffffffff ))
  done
  if (( hash >= 2147483648 )); then echo $((hash - 4294967296)); else echo "$hash"; fi
}

stream_shard() {
  local hash
  hash="$(java_hash "$1")"
  echo $(( (hash % 8 + 8) % 8 ))
}

COMPOSE=(docker compose)
[[ -f "$COMPOSE_ENV" ]] && COMPOSE+=(--env-file "$COMPOSE_ENV")
COMPOSE+=(-f "$COMPOSE_FILE")

ensure_catalog_sku() {
  local sku="$1" product="$2" title="$3" price="$4" status update create
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$CATALOG_BASE_URL/api/v1/skus/$sku")"
  update="$(jq -cn --arg title "$title" --arg status ACTIVE --arg brand 'Load Test' --argjson price "$price" '{title:$title,status:$status,brand:$brand,priceCents:$price,currency:"USD"}')"
  if [[ "$status" == 200 ]]; then
    curl --fail-with-body --silent --show-error -X PATCH "$CATALOG_BASE_URL/internal/admin/v1/skus/$sku" -H 'Content-Type: application/json' -d "$update" >/dev/null
    echo "Updated Catalog SKU $sku"
  elif [[ "$status" == 404 ]]; then
    create="$(jq -cn --arg sku "$sku" --arg product "$product" --arg title "$title" --argjson price "$price" '{skuId:$sku,productId:$product,title:$title,status:"ACTIVE",brand:"Load Test",priceCents:$price,currency:"USD"}')"
    curl --fail-with-body --silent --show-error -X POST "$CATALOG_BASE_URL/internal/admin/v1/skus" -H 'Content-Type: application/json' -d "$create" >/dev/null
    echo "Created Catalog SKU $sku"
  else
    echo "Catalog lookup failed for $sku with HTTP $status" >&2; exit 1
  fi
}

seed_and_verify() {
  local sku="$1" stock="$2" expected_price="$3" shard tag price_key actual_price catalog available hash
  curl --fail-with-body --silent --show-error -X POST "$INVENTORY_BASE_URL/internal/inventory/seed" -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg sku "$sku" --argjson stock "$stock" '{skuId:$sku,onHand:$stock}')" >/dev/null
  catalog="$(curl --fail-with-body --silent --show-error "$CATALOG_BASE_URL/api/v1/skus/$sku")"
  [[ "$(jq -r '.skuId' <<<"$catalog")" == "$sku" && "$(jq -r '.priceCents' <<<"$catalog")" == "$expected_price" ]] || { echo "Catalog verification failed for $sku" >&2; exit 1; }
  available="$(curl --fail-with-body --silent --show-error "$INVENTORY_BASE_URL/internal/inventory/$sku")"
  [[ "$available" == "$stock" ]] || { echo "Inventory verification failed for $sku: expected $stock, got $available" >&2; exit 1; }
  shard="$(stream_shard "$sku")"; printf -v tag '{A100:%02d}' "$shard"
  price_key="fs:price:${tag}:sku:${sku}"
  actual_price="$("${COMPOSE[@]}" exec -T redis redis-cli --raw HGET "$price_key" priceCents | tr -d '\r')"
  [[ "$actual_price" == "$expected_price" ]] || { echo "Redis price preheat verification failed for $sku: '$actual_price'" >&2; exit 1; }
  hash="$(java_hash "$sku")"
  jq -cn --arg sku "$sku" --argjson stock "$stock" --argjson hash "$hash" --arg shard "$(printf '%02d' "$shard")" \
    --arg stream "fs:stream:${tag}" --argjson price "$expected_price" \
    '{skuId:$sku,initialStock:$stock,javaHashCode:$hash,streamShard:$shard,streamKey:$stream,priceCents:$price}'
}

DURATION_SECONDS="$(duration_seconds "$DURATION")"
PLANNED_REQUESTS=$((TARGET_RPS * DURATION_SECONDS))
if [[ "$TEST_PROFILE" == success_capacity ]]; then
  REQUIRED_STOCK=$(((PLANNED_REQUESTS * 12 + 9) / 10))
else
  REQUIRED_STOCK="$INITIAL_STOCK"
fi

for expected in {0..7}; do
  sku="${SHARDED_SKUS[$expected]}"; actual="$(stream_shard "$sku")"
  (( actual == expected )) || { echo "Fixed sharded SKU mapping is invalid: $sku -> $actual" >&2; exit 1; }
done

curl --fail --silent "$CATALOG_BASE_URL/actuator/health" | jq -e '.status == "UP"' >/dev/null
curl --fail --silent "$INVENTORY_BASE_URL/actuator/health" | jq -e '.status == "UP"' >/dev/null

RESULT_DIR="$ROOT/perf/results/$RUN_ID"
mkdir -p "$RESULT_DIR"
SKU_RESULTS="$RESULT_DIR/.sku-inventory.jsonl"
: > "$SKU_RESULTS"

ensure_catalog_sku "$SINGLE_SKU" loadtest-product-hot-001 'Load Test Hot SKU' 1999
seed_and_verify "$SINGLE_SKU" "$REQUIRED_STOCK" 1999 >> "$SKU_RESULTS"
echo "Ready SKU=$SINGLE_SKU stock=$REQUIRED_STOCK shard=$(printf '%02d' "$(stream_shard "$SINGLE_SKU")")"

ensure_catalog_sku "$WARMUP_SKU" loadtest-product-warmup 'Load Test Warmup SKU' 999
seed_and_verify "$WARMUP_SKU" 100 999 >> "$SKU_RESULTS"
echo "Ready SKU=$WARMUP_SKU stock=100 shard=$(printf '%02d' "$(stream_shard "$WARMUP_SKU")")"

for shard in {0..7}; do
  sku="${SHARDED_SKUS[$shard]}"; stock=$((REQUIRED_STOCK / 8)); (( shard < REQUIRED_STOCK % 8 )) && stock=$((stock + 1))
  price=$((1499 + shard))
  ensure_catalog_sku "$sku" "${sku/sku/product}" "Load Test Shard $(printf '%02d' "$shard")" "$price"
  seed_and_verify "$sku" "$stock" "$price" >> "$SKU_RESULTS"
  echo "Ready SKU=$sku stock=$stock shard=$(printf '%02d' "$shard")"
done

if [[ "$SKU_MODE" == single ]]; then ACTIVE_SKUS="$(jq -cn --arg sku "$SINGLE_SKU" '[$sku]')"; else ACTIVE_SKUS="$(printf '%s\n' "${SHARDED_SKUS[@]}" | jq -R . | jq -s .)"; fi
jq -s --arg run "$RUN_ID" --arg prepared "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg profile "$TEST_PROFILE" --arg mode "$SKU_MODE" \
  --argjson target "$TARGET_RPS" --arg duration "$DURATION" --argjson seconds "$DURATION_SECONDS" --argjson planned "$PLANNED_REQUESTS" \
  --argjson initial "$INITIAL_STOCK" --argjson preparedStock "$REQUIRED_STOCK" --argjson preVus "${PRE_ALLOCATED_VUS:-20}" --argjson maxVus "${MAX_VUS:-100}" \
  --arg base "$INVENTORY_BASE_URL" --argjson active "$ACTIVE_SKUS" \
  '{runId:$run,preparedAt:$prepared,testProfile:$profile,skuMode:$mode,targetRps:$target,duration:$duration,durationSeconds:$seconds,plannedMaxRequests:$planned,initialStock:$initial,preparedTestStock:$preparedStock,preAllocatedVUs:$preVus,maxVUs:$maxVus,baseUrl:$base,activeSkuIds:$active,skuInventory:.,demoDataAffected:false}' \
  "$SKU_RESULTS" > "$RESULT_DIR/parameters.json"
rm -f "$SKU_RESULTS"

echo "Load-test data prepared. profile=$TEST_PROFILE mode=$SKU_MODE plannedRequests=$PLANNED_REQUESTS preparedTestStock=$REQUIRED_STOCK"
echo "Parameters: $RESULT_DIR/parameters.json"
echo 'WARNING: preparation seeds dedicated loadtest SKUs and resets their inventory. Do not run it while a load test is active.' >&2
