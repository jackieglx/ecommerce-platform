#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose --env-file "$ROOT/infra/local/.env" -f "$ROOT/infra/local/docker-compose.yml")
SKU_ID="smoke-sku-$(date +%s)"
USER_ID="smoke-user"

order_api_has_status() {
  local order_id="$1" status="$2"
  curl --fail --silent "http://localhost:8083/api/v1/orders/${order_id}" | grep -q "\"status\":\"${status}\""
}

for port in 8080 8081 8082 8083 8084; do
  curl --fail --silent "http://localhost:$port/actuator/health" >/dev/null
done

curl --fail --silent --show-error -X POST http://localhost:8080/internal/admin/v1/skus \
  -H 'Content-Type: application/json' \
  -d "{\"skuId\":\"$SKU_ID\",\"productId\":\"smoke-product\",\"title\":\"Smoke SKU\",\"status\":\"ACTIVE\",\"brand\":\"local\",\"priceCents\":1999,\"currency\":\"USD\"}" >/dev/null
curl --fail --silent --show-error -X POST http://localhost:8082/internal/inventory/seed \
  -H 'Content-Type: application/json' -d "{\"skuId\":\"$SKU_ID\",\"onHand\":5}" >/dev/null

reserve="$(curl --fail --silent --show-error -X POST http://localhost:8082/api/v1/flashsale/reservations \
  -H "X-User-Id: $USER_ID" -H "Idempotency-Key: smoke-$(date +%s%N)" \
  -H 'Content-Type: application/json' -d "{\"skuId\":\"$SKU_ID\",\"qty\":1}")"
ORDER_ID="$(printf '%s' "$reserve" | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p')"
[[ -n "$ORDER_ID" ]] || { echo "Reservation did not return orderId: $reserve" >&2; exit 1; }

deadline=$((SECONDS + 45))
until order_api_has_status "$ORDER_ID" PENDING_PAYMENT; do
  (( SECONDS < deadline )) || { echo "Order was not created from reservation: $ORDER_ID" >&2; exit 1; }
  sleep 1
done

curl --fail --silent --show-error -X POST http://localhost:8084/api/v1/payments/succeed \
  -H "X-User-Id: $USER_ID" -H 'Content-Type: application/json' -d "{\"orderId\":\"$ORDER_ID\"}" >/dev/null

deadline=$((SECONDS + 45))
until order_api_has_status "$ORDER_ID" PAID; do
  (( SECONDS < deadline )) || { echo "Order did not advance to PAID: $ORDER_ID" >&2; exit 1; }
  sleep 1
done
echo "Smoke test passed: SKU=$SKU_ID order=$ORDER_ID status=PAID"
